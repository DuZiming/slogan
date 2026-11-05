package com.slogan.tv.library.source.smb

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmbMediaSourceTest {

    private fun tree(): Map<String, List<SmbEntry>> = mapOf(
        "media" to listOf(
            SmbEntry("Movies", true, 0L, 10L),
            SmbEntry("\$RECYCLE.BIN", true, 0L, 10L),
            SmbEntry("note.txt", false, 5L, 10L),
            SmbEntry("poster.jpg", false, 5L, 10L),
            SmbEntry(".hidden.mkv", false, 5L, 10L),
            SmbEntry("ok.mkv", false, 9L, 10L),
        ),
        "media/\$RECYCLE.BIN" to listOf(SmbEntry("deleted.mkv", false, 8L, 10L)),
        "media/Movies" to listOf(
            SmbEntry("沙丘 2（2024）.2160p.mkv", false, 67_000L, 20L),
            SmbEntry("BDMV", true, 0L, 20L),
            SmbEntry("第2季", true, 0L, 20L),
            SmbEntry("empty.mkv", false, 0L, 20L),
        ),
        "media/Movies/BDMV" to listOf(SmbEntry("STREAM", true, 0L, 20L)),
        "media/Movies/BDMV/STREAM" to listOf(SmbEntry("00001.m2ts", false, 1000L, 20L)),
        "media/Movies/第2季" to listOf(SmbEntry("三体.S02E05.mkv", false, 8_000L, 30L)),
    )

    private fun source(fake: FakeSmbFileClient) =
        SmbMediaSource("nas", "NAS", "media") { fake }

    @Test
    fun `scans share with full rules`() = runBlocking<Unit> {
        val r = source(FakeSmbFileClient(tree())).scan()

        val names = r.files.map { it.name }.toSet()
        assertEquals(setOf("沙丘 2（2024）.2160p.mkv", "三体.S02E05.mkv", "ok.mkv"), names)
        assertEquals(
            "media/Movies/沙丘 2（2024）.2160p.mkv",
            r.files.first { it.name.contains("沙丘") }.relativePath,
        )
        // skipped: $RECYCLE.BIN 目录、.hidden.mkv、empty.mkv(0字节)、BDMV 子树
        assertEquals(4, r.skippedCount)
    }

    @Test
    fun `bdmv subtree becomes placeholder`() = runBlocking<Unit> {
        val r = source(FakeSmbFileClient(tree())).scan()

        assertEquals(1, r.bdmvDirs.size)
        val bdmv = r.bdmvDirs.single()
        assertEquals("Movies/BDMV", bdmv.name)
        assertEquals("media/Movies/BDMV", bdmv.relativePath)
        assertTrue(r.files.none { it.name.endsWith(".m2ts") }, "STREAM internals must not leak")
    }

    @Test
    fun `dir failure skips subtree and reports hint`() = runBlocking<Unit> {
        val fake = FakeSmbFileClient(tree())
        fake.failWith("media/Movies", SmbCommunicationException("boom", SmbCommunicationException.Kind.IO))
        val hints = mutableListOf<com.slogan.tv.library.source.ScanUpdate.ErrorHint>()

        val r = SmbMediaSource("nas", "NAS", "media") { fake }
            .scan { u -> if (u is com.slogan.tv.library.source.ScanUpdate.ErrorHint) hints += u }

        assertEquals(listOf("ok.mkv"), r.files.map { it.name })
        assertEquals(1, hints.size)
    }
}
