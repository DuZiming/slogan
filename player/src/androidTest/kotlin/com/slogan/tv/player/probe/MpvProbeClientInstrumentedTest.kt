package com.slogan.tv.player.probe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slogan.tv.library.model.MediaFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MpvProbeClientInstrumentedTest {

    private fun sampleFile(context: Context): MediaFile {
        val target = File(context.getExternalFilesDir(null), "probe-sample.mp4")
        if (!target.exists()) {
            context.assets.open("sample.mp4").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return MediaFile("local", target.absolutePath, "sample.mp4", 1L, 2L, "sample.mp4")
    }

    @Test
    fun probeReadsVideoProperties() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = MpvProbeClient(context)

        val info = probe.probe(sampleFile(context))

        assertEquals(640, info.width)
        assertEquals(360, info.height)
        val durationMs = info.durationMs
        assertTrue("时长应约 10s: $durationMs", durationMs != null && durationMs in 9_000..11_000)
        assertTrue("应有音轨编码信息: ${info.audioCodecs}", info.audioCodecs.isNotEmpty())
    }

    @Test
    fun probeMissingFileReturnsEmptyGracefully() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = MpvProbeClient(context)
        val missing = MediaFile("local", "/no/such/file.mp4", "file.mp4", 1L, 2L, "file.mp4")

        val info = probe.probe(missing)

        assertNotNull(info)
        assertEquals(null, info.width)
        assertTrue(info.audioCodecs.isEmpty())
    }
}
