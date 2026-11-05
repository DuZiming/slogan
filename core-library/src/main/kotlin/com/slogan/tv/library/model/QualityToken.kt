package com.slogan.tv.library.model

/**
 * 从文件名探测出的画质/音轨标记（spec §5.4 画质探测的文件名部分）。
 */
enum class QualityToken {
    FOUR_K,
    HDR10,
    HDR10_PLUS,
    DOLBY_VISION,
    REMUX,
    BLURAY_DISC,
    TRUEHD,
    DTS_HD_MA,
    DTS,
    EAC3,
    AC3,
    AAC,
    ATMOS,
}
