package com.slogan.tv.library.stream

import java.io.InputStream

/**
 * SMB 字节源：读流由外部注入（smbj 的 DiskShare 打开与 seek 由 :player 侧装配）。
 */
class SmbStreamSource(
    override val lengthBytes: Long?,
    private val openAt: suspend (offset: Long) -> InputStream,
) : StreamSource {
    override fun open(offset: Long): InputStream =
        kotlinx.coroutines.runBlocking { openAt(offset) }
}
