package com.slogan.tv.player.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MpvEngineInstrumentedTest {

    private fun samplePath(context: Context): String {
        val target = File(context.getExternalFilesDir(null), "sample.mp4")
        if (!target.exists()) {
            context.assets.open("sample.mp4").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target.absolutePath
    }

    @Test
    fun loadPauseSeekStopAndReload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = MpvEngine(context)
        val sample = samplePath(context)

        try {
            engine.load(sample, PlaybackOptions())

            val playing = withTimeoutOrNull(20_000) {
                engine.state.first { it is EngineState.Playing }
            }
            assertNotNull("应在超时内进入 Playing，实际 state=${engine.state.value}", playing)
            val durationMs = (playing as EngineState.Playing).durationMs
            assertTrue("时长应大于 0，实际 $durationMs", durationMs > 0)

            engine.pause()
            val paused = withTimeoutOrNull(5_000) {
                engine.state.first { it is EngineState.Paused }
            }
            assertNotNull("暂停应生效", paused)

            engine.seekTo(1_000)
            engine.play()
            val resumed = withTimeoutOrNull(8_000) {
                engine.state.first { s -> s is EngineState.Playing && s.positionMs >= 500 }
            }
            assertNotNull("seek 后应恢复播放且位置前进", resumed)

            engine.stop()
            val idle = withTimeoutOrNull(5_000) {
                engine.state.first { it is EngineState.Idle }
            }
            assertNotNull("stop 后应回 Idle", idle)

            // 同一实例二次加载（复用不重init）
            engine.load(sample, PlaybackOptions())
            val second = withTimeoutOrNull(20_000) {
                engine.state.first { it is EngineState.Playing }
            } ?: fail("第二次加载未进入 Playing")
            assertTrue((second as EngineState.Playing).durationMs > 0)
        } finally {
            engine.release()
        }
    }
}
