package com.slogan.tv.library.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseTest {

    private fun newDb(): LibraryDatabase {
        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LibraryDatabase.Schema.create(driver)
        return LibraryDatabase(driver)
    }

    @Test
    fun `sources table round trip`() {
        val db = newDb()
        db.sourcesQueries.insertSource("s1", "NAS", "SMB", "192.168.1.10", 445L, "media", "user", null)
        val sources = db.sourcesQueries.selectAllSources().executeAsList()
        assertEquals(1, sources.size)
        assertEquals("s1", sources.single().id)
        assertEquals("media", sources.single().share_root)
    }

    @Test
    fun `snapshot row returns generated key and last time`() {
        val db = newDb()
        db.snapshotQueries.insertSnapshot(123_456L)
        val key = db.snapshotQueries.lastInsertRowId().executeAsOne()
        assertTrue(key > 0)
        assertEquals(123_456L, db.snapshotQueries.lastSnapshotTime().executeAsOne())
    }

    @Test
    fun `media files upsert by source and path`() {
        val db = newDb()
        db.mediaFilesQueries.insertFile("s1", "a.mkv", "a.mkv", 1L, 2L, "a.mkv", 0L)
        db.mediaFilesQueries.insertFile("s1", "a.mkv", "a.mkv", 9L, 2L, "a.mkv", 0L) // 同主键覆盖
        db.mediaFilesQueries.insertFile("s1", "b.mkv", "b.mkv", 1L, 2L, "b.mkv", 1L) // bdmv 标记
        val files = db.mediaFilesQueries.selectAllFiles().executeAsList()
        val bdmv = db.mediaFilesQueries.selectAllBdmv().executeAsList()
        assertEquals(1, files.size)
        assertEquals(9L, files.single().size_bytes) // 覆盖后取新值
        assertEquals(1, bdmv.size)
    }
}
