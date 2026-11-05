package com.slogan.tv.library.source.smb

import com.slogan.tv.library.source.MediaSourceConfig
import com.slogan.tv.library.source.PlainCredentialCipher
import com.slogan.tv.library.source.ScanUpdate
import com.slogan.tv.library.source.SourceFactory
import com.slogan.tv.library.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实共享门控测试（计划 2 遗留的 smbj 真机验收）：
 * 仅当项目根存在 local.smb-test.properties 时运行（CI 无该文件自动跳过）。
 * 格式：host= / shareRoot= / username= / password=
 */
class SmbRealShareTest {

    private val config: Properties? = run {
        var dir: File? = File(System.getProperty("user.dir"))
        var found: File? = null
        repeat(3) {
            val candidate = dir?.resolve("local.smb-test.properties")
            if (candidate?.exists() == true) {
                found = candidate
                return@repeat
            }
            dir = dir?.parentFile
        }
        if (found != null) found!!.inputStream().reader(Charsets.UTF_8).use { Properties().apply { load(it) } } else null
    }

    private fun assumeConfig() {
        assumeTrue("local.smb-test.properties 不存在，跳过真实共享测试", config != null)
    }

    @Test
    fun `scan real share finds video files`() = runBlocking<Unit> {
        assumeConfig()
        val cfg = MediaSourceConfig(
            id = "realsmb",
            name = "真机SMB",
            type = SourceType.SMB,
            host = config!!.getProperty("host"),
            shareRoot = config.getProperty("shareRoot"),
            username = config.getProperty("username")?.takeIf { it.isNotBlank() },
        )
        val password = config.getProperty("password")?.takeIf { it.isNotBlank() }

        val factory = SourceFactory(PlainCredentialCipher, { password })
        val hints = mutableListOf<ScanUpdate.ErrorHint>()
        val result = factory.create(cfg).scan { u -> if (u is ScanUpdate.ErrorHint) hints += u }

        assertEquals(emptyList(), hints, "真实共享扫描不应有错误提示: $hints")
        assertEquals(null, result.fatalError)
        assertTrue(result.files.isNotEmpty(), "应能扫到视频文件")
        assertTrue(
            result.files.all { it.relativePath!!.startsWith(config.getProperty("shareRoot")) },
            "relativePath 应以共享根前缀开始",
        )
    }
}
