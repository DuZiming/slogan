package com.slogan.tv.player

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MpvVersionInstrumentedTest {

    @Test
    fun mpvLoadsAndReportsVersion() {
        val version = MpvInitializer.probeVersion(ApplicationProvider.getApplicationContext())
        assertNotNull("mpv-version 属性应可读（libmpv 应正常加载）: null", version)
        assertTrue("版本号应含 mpv，实际: $version", version!!.contains("mpv", ignoreCase = true))
    }
}
