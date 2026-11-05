package com.slogan.tv.library.audio

/** 设备 HDMI 输出上报的可用音频编码集（小写规范名：ac3/eac3/dts/truehd/atmos…）。 */
data class AudioCapabilities(
    val supportedEncodings: Set<String>,
) {
    fun supports(codec: String): Boolean = codec.lowercase() in supportedEncodings
}

/** 直通三态（spec §5.6：自动探测 + 手动锁）。 */
enum class PassthroughMode { AUTO, FORCE_PASSTHROUGH, FORCE_PCM }

data class PassthroughDecision(
    val usePassthrough: Boolean,
    val spdifCodecs: Set<String>,
    val ao: String? = null,
    val reason: String,
)

/**
 * 直通决策矩阵（spec §5.6"能通则通，不能通无缝回退 PCM"）。
 *
 * - AUTO：设备支持 且 文件音轨已知 且 家族匹配 → 直通该编码；否则 PCM
 * - FORCE_PASSTHROUGH：尊重用户强制（设备未上报支持时仍直通，reason 提示风险）
 * - FORCE_PCM：永不直通
 */
object PassthroughPolicy {

    /** 文件音轨编码名 → spdif 编码族（mpv --audio-spdif 取值）。 */
    private val FAMILY = mapOf(
        "ac3" to "ac3",
        "eac3" to "eac3",
        "dts" to "dts",
        "dts-hd" to "dts",
        "dts-hd-ma" to "dts",
        "truehd" to "truehd",
        "atmos" to "truehd",
        "eac3-atmos" to "eac3",
    )

    fun resolve(
        mode: PassthroughMode,
        caps: AudioCapabilities,
        fileAudioCodec: String?,
    ): PassthroughDecision {
        if (mode == PassthroughMode.FORCE_PCM) {
            return PassthroughDecision(false, emptySet(), reason = "用户强制 PCM 输出")
        }
        val codec = fileAudioCodec?.lowercase()
        val family = codec?.let { FAMILY[it] }
        if (codec == null || family == null) {
            return PassthroughDecision(
                false,
                emptySet(),
                reason = if (mode == PassthroughMode.AUTO) "文件音轨未知或非可直通编码，PCM 输出" else "文件音轨未知或非可直通编码",
            )
        }
        if (caps.supports(family)) {
            return PassthroughDecision(true, setOf(family), reason = "设备支持 $family 直通")
        }
        return if (mode == PassthroughMode.FORCE_PASSTHROUGH) {
            PassthroughDecision(
                true,
                setOf(family),
                reason = "设备未报告支持 $family，用户强制直通（若无声音请改回自动）",
            )
        } else {
            PassthroughDecision(false, emptySet(), reason = "设备不支持 $family 直通，PCM 输出")
        }
    }
}
