package com.slogan.tv.library.repo

import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.source.MediaSourceConfig
import com.slogan.tv.library.source.SourceType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 契约测试：InMemory 与 SqlDelight 两个实现必须通过同一套断言。 */
abstract class LibraryStoreContract {

    abstract fun newStore(): LibraryStore

    private fun file(path: String, name: String = path) =
        MediaFile("s1", path, name, 1L, 2L, relativePath = path)

    private val config = MediaSourceConfig(
        id = "s1", name = "NAS", type = SourceType.SMB,
        host = "192.168.1.10", shareRoot = "media", username = "u", port = 445,
    )

    @Test
    fun `sources round trip`() = runBlocking<Unit> {
        val store = newStore()
        store.saveSources(listOf(config))
        assertEquals(listOf(config), store.loadSources())
    }

    @Test
    fun `save twice does not duplicate`() = runBlocking<Unit> {
        val store = newStore()
        store.saveScanResult("s1", listOf(file("a.mkv")), emptyList())
        store.saveScanResult("s1", listOf(file("a.mkv"), file("b.mkv")), emptyList())
        assertEquals(2, store.loadAllFiles().size) // Review Focus #3
    }

    @Test
    fun `different sources do not interfere`() = runBlocking<Unit> {
        val store = newStore()
        store.saveScanResult("s1", listOf(file("a.mkv")), emptyList())
        store.saveScanResult("s2", listOf(file("a.mkv")), emptyList())
        assertEquals(2, store.loadAllFiles().size)
        store.saveScanResult("s1", emptyList(), emptyList())
        assertEquals(1, store.loadAllFiles().size) // s2 保留
    }

    @Test
    fun `bdmv stored and loaded separately`() = runBlocking<Unit> {
        val store = newStore()
        store.saveScanResult("s1", listOf(file("a.mkv")), listOf(file("Movie/BDMV", "Movie/BDMV")))
        assertEquals(1, store.loadAllFiles().size)
        assertEquals(1, store.loadAllBdmvDirs().size)
    }

    @Test
    fun `snapshot time null then value`() = runBlocking<Unit> {
        val store = newStore()
        assertNull(store.lastSnapshotTime())
        store.recordSnapshotTime(42L)
        assertEquals(42L, store.lastSnapshotTime())
    }

    @Test
    fun `relative path survives round trip`() = runBlocking<Unit> {
        val store = newStore()
        store.saveScanResult("s1", listOf(file("media/沙丘 2（2024）.mkv", "沙丘 2（2024）.mkv")), emptyList())
        val loaded = store.loadAllFiles().single()
        assertEquals("media/沙丘 2（2024）.mkv", loaded.relativePath)
        assertTrue(loaded.sizeBytes == 1L && loaded.lastModifiedEpochMs == 2L)
    }

    @Test
    fun `removing a source drops its rows`() = runBlocking<Unit> {
        val store = newStore()
        val configB = MediaSourceConfig(
            id = "s2", name = "U盘", type = SourceType.LOCAL, shareRoot = "D:/x",
        )
        store.saveSources(listOf(config, configB))
        store.saveScanResult("s2", listOf(MediaFile("s2", "b.mkv", "b.mkv", 1L, 2L, "b.mkv")), emptyList())

        store.saveSources(listOf(config)) // 只留 s1

        assertEquals(listOf(config), store.loadSources())
        assertEquals(0, store.loadAllFiles().size) // s2 的行级联清除，无幽灵文件
    }
}

class InMemoryLibraryStoreTest : LibraryStoreContract() {
    override fun newStore() = InMemoryLibraryStore()
}

class SqlDelightLibraryStoreTest : LibraryStoreContract() {
    override fun newStore(): LibraryStore {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        com.slogan.tv.library.db.LibraryDatabase.Schema.create(driver)
        return SqlDelightLibraryStore(com.slogan.tv.library.db.LibraryDatabase(driver))
    }
}
