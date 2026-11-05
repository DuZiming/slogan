package com.slogan.tv.library.source.nfs

import com.slogan.tv.library.source.MediaSourceConfig
import com.slogan.tv.library.source.SourceType
import com.slogan.tv.library.source.ScanUpdate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NfsMediaSourceTest {

    private class FakeNfsClient(
        private val tree: Map<String, List<NfsDirEntry>> = emptyMap(),
    ) : NfsFileClient {
        var connected: Pair<String, String>? = null
            private set

        override suspend fun connect(host: String, export: String, user: String?, password: String?): Boolean {
            connected = host to export
            return true
        }

        override suspend fun list(dir: String): List<NfsDirEntry> = tree[dir].orEmpty()

        override fun openRead(path: String): NfsReadHandle? = null

        override fun close() = Unit
    }

    private fun tree() = mapOf(
        "/export" to listOf(
            NfsDirEntry("Movies", true, 0, 10),
            NfsDirEntry("\$RECYCLE.BIN", true, 0, 10),
            NfsDirEntry(".hidden.mkv", false, 5, 10),
            NfsDirEntry("ok.mkv", false, 9, 10),
            NfsDirEntry("note.txt", false, 5, 10),
        ),
        "/export/Movies" to listOf(
            NfsDirEntry("沙丘 2（2024）.2160p.mkv", false, 67000, 20),
            NfsDirEntry("BDMV", true, 0, 20),
            NfsDirEntry("empty.mkv", false, 0, 20),
        ),
        "/export/Movies/BDMV" to listOf(NfsDirEntry("STREAM", true, 0, 20)),
    )

    @Test
    fun `scans export with same rules as smb`() = runBlocking<Unit> {
        val fake = FakeNfsClient(tree())
        val r = NfsMediaSource("nas", "NFS", host = "192.168.1.10", export = "/export") { fake }.scan()

        assertEquals(setOf("沙丘 2（2024）.2160p.mkv", "ok.mkv"), r.files.map { it.name }.toSet())
        assertEquals("电影/Movies/BDMV".removePrefix("电影/"), r.bdmvDirs.single().name)
        assertEquals("/export/Movies/BDMV", r.bdmvDirs.single().relativePath)
        assertEquals(4, r.skippedCount) // $RECYCLE.BIN + .hidden.mkv + empty.mkv + BDMV
    }

    @Test
    fun `connect failure is fatal error`() = runBlocking<Unit> {
        val failing = object : NfsFileClient {
            override suspend fun connect(host: String, export: String, user: String?, password: String?): Boolean = false
            override suspend fun list(dir: String): List<NfsDirEntry>? = null
            override fun openRead(path: String): NfsReadHandle? = null
            override fun close() = Unit
        }
        val hints = mutableListOf<ScanUpdate.ErrorHint>()
        val r = NfsMediaSource("nas", "NFS", "h", "/e") { failing }
            .scan { u -> if (u is ScanUpdate.ErrorHint) hints += u }
        assertTrue(r.fatalError != null, "连接失败应为致命错误")
        assertEquals(1, hints.size)
    }

    @Test
    fun `nfs config validation`() {
        val ok = MediaSourceConfig("n1", "NAS", SourceType.NFS, host = "192.168.1.10", shareRoot = "/volume1/media")
        assertEquals(emptyList(), ok.validate())
        val noHost = MediaSourceConfig("n1", "NAS", SourceType.NFS, shareRoot = "/volume1/media")
        assertTrue(noHost.validate().isNotEmpty())
    }

    @Test
    fun `factory requires injected nfs creator`() {
        val factory = com.slogan.tv.library.source.SourceFactory(
            credentialCipher = com.slogan.tv.library.source.PlainCredentialCipher,
            passwordStore = { null },
        )
        val cfg = MediaSourceConfig("n1", "NAS", SourceType.NFS, host = "192.168.1.10", shareRoot = "/volume1/media")
        assertFailsWith<IllegalStateException> { factory.create(cfg) }
    }
}
