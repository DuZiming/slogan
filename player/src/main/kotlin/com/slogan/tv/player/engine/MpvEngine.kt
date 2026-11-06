package com.slogan.tv.player.engine

import android.content.Context
import android.util.Log
import android.view.Surface
import com.slogan.tv.player.core.MpvCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** libmpv C API 事件 ID（与 mpv/client.h 一致） */
object MpvEventId {
    const val SHUTDOWN = 1
    const val LOG_MESSAGE = 2
    const val START_FILE = 6
    const val END_FILE = 7
    const val FILE_LOADED = 8
    const val IDLE = 11
}

/**
 * libmpv 引擎实现（LGPL 自建内核，经 MpvCore JNI 桥调用）。
 * 渲染由 MpvCore 的 native 层 EGL 渲染桥处理（attachSurface 内建）。
 */
class MpvEngine(context: Context) : PlayerEngine {

    private val mpv: MpvCore = checkNotNull(MpvCore.create(context)) { "MpvCore.create 失败" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var durationMs = 0L
    private var positionMs = 0L
    private var paused = false
    private var buffering = false
    private var surfaceAttached = false

    private val listener = object : MpvCore.EventListener {
        override fun eventProperty(name: String, value: Double) {
            when (name) {
                "time-pos" -> update(positionMs = (value * 1000).toLong())
                "duration" -> update(durationMs = (value * 1000).toLong())
            }
        }

        override fun eventProperty(name: String, value: Boolean) {
            when (name) {
                "pause" -> {
                    val atEof = runCatching { mpv.getPropertyBoolean("eof-reached") }.getOrNull() == true
                    if (value && atEof) {
                        scope.launch { _state.value = EngineState.Ended }
                    } else {
                        update(paused = value)
                    }
                }
                "paused-for-cache" -> update(cachePaused = value)
                "eof-reached" -> if (value) scope.launch { _state.value = EngineState.Ended }
            }
        }

        override fun eventProperty(name: String, value: String) {
            // time-pos/duration 可能以 String 回调（native 桥按字符串投递）
            when (name) {
                "time-pos" -> value.toDoubleOrNull()?.let { update(positionMs = (it * 1000).toLong()) }
                "duration" -> value.toDoubleOrNull()?.let { update(durationMs = (it * 1000).toLong()) }
            }
        }

        override fun eventProperty(name: String, value: Long) {
            Log.d("MpvEngine", "event[$name] long=$value")
        }

        override fun eventProperty(name: String) {
            Log.d("MpvEngine", "event[$name] (no value)")
        }

        override fun mpvEvent(eventId: Int) {
            Log.d("MpvEngine", "mpvevent id=$eventId")
            when (eventId) {
                MpvEventId.FILE_LOADED -> {
                    val duration = runCatching { mpv.getPropertyDouble("duration") }.getOrNull()
                    duration?.let { if (it > 0) update(durationMs = (it * 1000).toLong()) }
                }
                MpvEventId.END_FILE -> {
                    // 文件结束（正常或错误）——检查是否是错误
                    val idle = runCatching { mpv.getPropertyBoolean("idle-active") }.getOrNull()
                    if (idle == true && _state.value is EngineState.Preparing) {
                        scope.launch { _state.value = EngineState.Error("文件加载失败") }
                    }
                }
            }
        }

        override fun logMessage(prefix: String, level: Int, text: String) {
            Log.d("MpvEngine", "log[$prefix/$level] $text")
        }
    }

    init {
        mpv.setOptionString("hwdec", "auto")
        mpv.setOptionString("keep-open", "always")
        mpv.init()
        mpv.addEventListener(listener)
        mpv.observeProperty("time-pos", 1)
        mpv.observeProperty("duration", 2)
        mpv.observeProperty("pause", 3)
        mpv.observeProperty("paused-for-cache", 4)
        mpv.observeProperty("eof-reached", 5)
    }

    override fun tracks(): List<TrackInfo> {
        val count = runCatching { mpv.getPropertyInt("track-list/count") }.getOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { n ->
            val type = runCatching { mpv.getPropertyString("track-list/$n/type") }.getOrNull()
                ?: return@mapNotNull null
            val kind = when (type) {
                "audio" -> TrackKind.AUDIO
                "sub" -> TrackKind.SUB
                "video" -> TrackKind.VIDEO
                else -> return@mapNotNull null
            }
            TrackInfo(
                id = runCatching { mpv.getPropertyInt("track-list/$n/id") }.getOrNull() ?: return@mapNotNull null,
                kind = kind,
                title = runCatching { mpv.getPropertyString("track-list/$n/title") }.getOrNull(),
                language = runCatching { mpv.getPropertyString("track-list/$n/lang") }.getOrNull(),
                codec = runCatching { mpv.getPropertyString("track-list/$n/codec") }.getOrNull(),
                isDefault = runCatching { mpv.getPropertyBoolean("track-list/$n/default") }.getOrNull() == true,
                isSelected = runCatching { mpv.getPropertyBoolean("track-list/$n/selected") }.getOrNull() == true,
            )
        }
    }

    override fun selectTrack(kind: TrackKind, id: Int?) {
        val property = when (kind) {
            TrackKind.AUDIO -> "aid"
            TrackKind.SUB -> "sid"
            TrackKind.VIDEO -> "vid"
        }
        if (id == null) {
            mpv.setPropertyString(property, "no")
        } else {
            mpv.setPropertyInt(property, id)
        }
    }

    override fun attach(surface: Surface) {
        surfaceAttached = mpv.attachSurface(surface)
        Log.d("MpvEngine", "attachSurface result=$surfaceAttached")
    }

    override fun detach() {
        mpv.detachSurface()
        surfaceAttached = false
    }

    override fun load(url: String, options: PlaybackOptions) {
        resetProgress()
        mpv.setOptionString("hwdec", if (options.hwdec) "auto" else "no")
        if (options.audioSpdif.isNotEmpty()) {
            mpv.setOptionString("audio-spdif", options.audioSpdif.joinToString(","))
        } else {
            mpv.setOptionString("audio-spdif", "")
        }
        options.ao?.let { mpv.setOptionString("ao", it) }
        _state.value = EngineState.Preparing
        val startOption = options.startMs?.let { "start=+${it / 1000.0}" }
        if (startOption != null) {
            mpv.command("loadfile", url, "replace", startOption)
        } else {
            mpv.command("loadfile", url, "replace")
        }
        mpv.setPropertyBoolean("pause", false)
    }

    override fun play() {
        mpv.setPropertyBoolean("pause", false)
    }

    override fun pause() {
        mpv.setPropertyBoolean("pause", true)
    }

    override fun seekTo(positionMs: Long) {
        mpv.command("seek", (positionMs / 1000.0).toString(), "absolute")
    }

    override fun stop() {
        resetProgress()
        mpv.command("stop")
        _state.value = EngineState.Idle
    }

    override fun release() {
        runCatching { mpv.destroy() }
        scope.cancel()
    }

    private fun resetProgress() {
        durationMs = 0L
        positionMs = 0L
        paused = false
        buffering = false
    }

    /** 按最新观测值重算状态（limitedParallelism(1) 保证串行）。 */
    private fun update(
        positionMs: Long? = null,
        durationMs: Long? = null,
        paused: Boolean? = null,
        cachePaused: Boolean? = null,
    ) {
        scope.launch {
            positionMs?.let { this@MpvEngine.positionMs = it }
            durationMs?.let { this@MpvEngine.durationMs = it }
            paused?.let { this@MpvEngine.paused = it }
            cachePaused?.let { this@MpvEngine.buffering = it }
            _state.value = when {
                _state.value is EngineState.Idle || _state.value is EngineState.Error -> return@launch
                this@MpvEngine.paused -> EngineState.Paused(this@MpvEngine.positionMs, this@MpvEngine.durationMs)
                this@MpvEngine.buffering -> EngineState.Buffering(this@MpvEngine.positionMs)
                this@MpvEngine.durationMs > 0 -> EngineState.Playing(this@MpvEngine.positionMs, this@MpvEngine.durationMs)
                else -> EngineState.Preparing
            }
        }
    }
}
