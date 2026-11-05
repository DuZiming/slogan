package com.slogan.tv.player.nfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** libnfs JNI 装载与错误路径（真实 NFS 冒烟在验收清单：需 NAS 开 NFS 导出）。 */
class NfsClientInstrumentedTest {

    @Test
    fun libraryLoadsAndErrorPathsAreGraceful() {
        val client = NfsClient() // System.loadLibrary 成功即通过构造

        // 未连接先 list → null 不崩
        assertNull(client.list("/anything"))

        // 不可达主机 → connect false 不崩（超时由 libnfs 内部控制）
        val connected = client.connect(host = "192.168.231.231", export = "/export", user = null, password = null)
        assertFalse(connected)

        // 未连接 openRead → null
        assertNull(client.openRead("/x.mkv"))

        client.disconnect() // 幂等不崩
        assertTrue(true)
        assertEquals(0L, client.context.get())
    }
}
