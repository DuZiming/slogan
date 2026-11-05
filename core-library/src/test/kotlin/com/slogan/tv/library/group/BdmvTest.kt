package com.slogan.tv.library.group

import com.slogan.tv.library.model.LibraryEntry
import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.model.QualityToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BdmvTest {

    private fun mf(name: String) =
        MediaFile(sourceId = "local", path = "/media/$name", name = name, sizeBytes = 1L, lastModifiedEpochMs = 1L)

    @Test
    fun `bdmv folder becomes one movie`() {
        val e = LibraryGrouper.group(emptyList(), listOf(mf("让子弹飞.2010/BDMV")))
        val m = e.single() as LibraryEntry.Movie
        assertEquals("让子弹飞", m.parsed.title)
        assertTrue(m.parsed.qualityTokens.contains(QualityToken.BLURAY_DISC))
    }
}
