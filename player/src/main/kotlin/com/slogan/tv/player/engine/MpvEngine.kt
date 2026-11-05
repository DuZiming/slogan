package com.slogan.tv.player.engine

import android.content.Context
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * libmpv 引擎实现。MPVLib 的调用集中在创建线程；mpv 事件回调统一跳到
 * Default 派发器再更新 StateFlow（StateFlow 线程安全，UI 侧主线程观察）。
 */
class MpvEngine(context: Context) : PlayerEngine {

    private val mpv: MPVLib = checkNotNull(MPVLib.create(context)) { "MPVLib.create 失败" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var durationMs = 0L
    private var positionMs = 0L
    private var paused = false
    private var buffering = false
    private var surfaceAttached = false

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {
            android.util.Log.d("MpvEngine", "event[$property]")
        }

        override fun eventProperty(property: String, value: Long) {
            android.util.Log.d("MpvEngine", "event[$property] long=$value")
        }

        override fun eventProperty(property: String, value: Double) {
            android.util.Log.d("MpvEngine", "event[$property] double=$value")
            when (property) {
                "time-pos" -> update(positionMs = (value * 1000).toLong())
                "duration" -> update(durationMs = (value * 1000).toLong())
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            android.util.Log.d("MpvEngine", "event[$property] bool=$value")
            when (property) {
                "pause" -> {
                    // keep-open 场景播完会置 pause=true：结合 eof-reached 区分"播完"与"用户暂停"
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

        override fun eventProperty(property: String, value: String) {
            android.util.Log.d("MpvEngine", "event[$property] str=$value")
            // 实测 time-pos/duration 走 String 回调（native 桥按字符串投递）
            when (property) {
                "time-pos" -> value.toDoubleOrNull()?.let { update(positionMs = (it * 1000).toLong()) }
                "duration" -> value.toDoubleOrNull()?.let { update(durationMs = (it * 1000).toLong()) }
            }
        }

        override fun event(eventId: Int) {
            android.util.Log.d("MpvEngine", "mpvevent id=$eventId")
            if (eventId == MpvEvent.MPV_EVENT_FILE_LOADED) {
                // duration 的 observe 推送不可靠：装载完成时主动拉一次
                val duration = runCatching { mpv.getPropertyDouble("duration") }.getOrNull()
                duration?.let { if (it > 0) update(durationMs = (it * 1000).toLong()) }
            }
        }
    }

    init {
        mpv.setOptionString("hwdec", "auto")
        // 播完停在末帧（不进 Idle），Ended 状态由 eof-reached 驱动
        mpv.setOptionString("keep-open", "always")
        mpv.init()
        mpv.addObserver(observer)
        mpv.observeProperty("time-pos", 1)
        mpv.observeProperty("duration", 2)
        mpv.observeProperty("pause", 3)
        mpv.observeProperty("paused-for-cache", 4)
        mpv.observeProperty("eof-reached", 5)
    }

    override fun attach(surface: Surface) {
        mpv.attachSurface(surface)
        surfaceAttached = true
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
        // 未挂 Surface 时禁用视频输出，保证纯音频/无界面场景可走时钟
        mpv.setOptionString("vo", if (surfaceAttached) "libmpv" else "null")
        val startOption = options.startMs?.let { "start=+${it / 1000.0}" }
        _state.value = EngineState.Preparing
        mpv.command(
            if (startOption != null) {
                arrayOf("loadfile", url, "replace", startOption)
            } else {
                arrayOf("loadfile", url, "replace")
            },
        )
        // MPVLib 核心默认暂停态：load 即自动播放（引擎契约）
        mpv.setPropertyBoolean("pause", false)
    }

    override fun play() {
        mpv.setPropertyBoolean("pause", false)
    }

    override fun pause() {
        mpv.setPropertyBoolean("pause", true)
    }

    override fun seekTo(positionMs: Long) {
        mpv.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute"))
    }

    override fun stop() {
        resetProgress()
        mpv.command(arrayOf("stop"))
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

    /** 按最新观测值重算状态（事件线程安全：全部经 scope 串行应用）。 */
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
                // duration 事件通常与首个 time-pos 同时到达；未就绪前停在 Preparing
                else -> EngineState.Preparing
            }
        }
    }
}
