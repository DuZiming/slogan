package com.slogan.tv.library.parser

import com.slogan.tv.library.model.QualityToken.ATMOS
import com.slogan.tv.library.model.QualityToken.BLURAY_DISC
import com.slogan.tv.library.model.QualityToken.DOLBY_VISION
import com.slogan.tv.library.model.QualityToken.DTS_HD_MA
import com.slogan.tv.library.model.QualityToken.FOUR_K
import com.slogan.tv.library.model.QualityToken.HDR10_PLUS
import com.slogan.tv.library.model.QualityToken.REMUX
import com.slogan.tv.library.model.QualityToken.TRUEHD
import kotlin.test.Test
import kotlin.test.assertEquals

class QualityTokenTest {

    @Test
    fun `4k hdr dv atmos detected`() {
        val r = FilenameParser.parse("Movie.2024.2160p.DoVi.HDR10Plus.TrueHD.Atmos.mkv")
        assertEquals(setOf(FOUR_K, DOLBY_VISION, HDR10_PLUS, TRUEHD, ATMOS), r.qualityTokens)
    }

    @Test
    fun `remux bluray`() {
        val r = FilenameParser.parse("M.2019.1080p.BluRay.REMUX.DTS-HD.MA.mkv")
        assertEquals(setOf(REMUX, BLURAY_DISC, DTS_HD_MA), r.qualityTokens)
    }
}
