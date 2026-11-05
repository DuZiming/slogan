package com.slogan.tv.player.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MpvTracksInstrumentedTest {

    private fun samplePath(context: Context): String {
        val target = File(context.getExternalFilesDir(null), "sample-multi.mp4")
        if (!target.exists()) {
            context.assets.open("sample.mp4").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target.absolutePath
    }

    @Test
    fun listAndSwitchTracks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = MpvEngine(context)
        try {
            engine.load(samplePath(context), PlaybackOptions())
            assertNotNull(
                withTimeoutOrNull(20_000) { engine.state.first { it is EngineState.Playing } },
            )

            val tracks = engine.tracks()
            val audio = tracks.filter { it.kind == TrackKind.AUDIO }
            val subs = tracks.filter { it.kind == TrackKind.SUB }
            assertEquals("应有 2 条音轨", 2, audio.size)
            assertEquals("应有 2 条字幕轨", 2, subs.size)
            assertTrue(audio.all { it.codec != null })

            // 切到第二条音轨
            val secondAudio = audio[1]
            engine.selectTrack(TrackKind.AUDIO, secondAudio.id)
            Thread.sleep(500)
            val after = engine.tracks().filter { it.kind == TrackKind.AUDIO }
            assertEquals(secondAudio.id, after.first { it.isSelected }.id)

            // 关字幕（null = 禁用），再选回第二条
            engine.selectTrack(TrackKind.SUB, null)
            Thread.sleep(500)
            assertEquals(0, engine.tracks().count { it.kind == TrackKind.SUB && it.isSelected })
            engine.selectTrack(TrackKind.SUB, 1)
            Thread.sleep(500)
            assertEquals(1, engine.tracks().count { it.kind == TrackKind.SUB && it.isSelected })
        } finally {
            engine.release()
        }
    }
}
