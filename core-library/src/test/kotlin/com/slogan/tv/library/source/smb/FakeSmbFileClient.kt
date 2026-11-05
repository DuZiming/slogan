package com.slogan.tv.library.source.smb

/**
 * 测试设施：内存目录树版 SmbFileClient。
 * dirs 的 key 为 '/' 分隔路径，value 为该目录直系子项；
 * failWith 可对指定目录预置异常。
 */
class FakeSmbFileClient(
    private val dirs: MutableMap<String, List<SmbEntry>> = mutableMapOf(),
) : SmbFileClient {

    private val failures = mutableMapOf<String, SmbCommunicationException>()

    override suspend fun list(dir: String): List<SmbEntry> {
        failures[dir]?.let { throw it }
        return dirs[dir].orEmpty()
    }

    override fun close() = Unit

    fun failWith(dir: String, e: SmbCommunicationException) {
        failures[dir] = e
    }
}
