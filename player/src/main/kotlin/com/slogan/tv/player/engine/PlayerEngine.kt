package com.slogan.tv.player.engine

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放引擎状态机（spec §5.6 播放页控件的数据源）。
 */
sealed interface EngineState {
    data object Idle : EngineState
    data object Preparing : EngineState
    data class Buffering(val positionMs: Long) : EngineState
    data class Playing(val positionMs: Long, val durationMs: Long) : EngineState
    data class Paused(val positionMs: Long, val durationMs: Long) : EngineState
    data object Ended : EngineState
    data class Error(val message: String) : EngineState
}

/**
 * 一次播放的选项（直通相关参数由 PassthroughPolicy 生成，见 audio/）。
 */
data class PlaybackOptions(
    val hwdec: Boolean = true,
    val audioSpdif: Set<String> = emptySet(),
    val ao: String? = null,
    val startMs: Long? = null,
)

/**
 * 播放引擎抽象（spec §6 引擎解耦）：UI 只面对本接口。
 * 实现约定：attach/detach 与 Surface 生命周期配对；load 可多次调用（换集）；
 * 所有回调经 StateFlow 在主线程可观察。
 */
interface PlayerEngine {
    val state: StateFlow<EngineState>

    fun attach(surface: Surface)

    fun detach()

    fun load(url: String, options: PlaybackOptions)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun stop()

    fun release()
}
