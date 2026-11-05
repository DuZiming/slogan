package com.slogan.tv.library.source

import kotlin.test.Test
import kotlin.test.assertTrue

class ScannerApiCompatibilityTest {

    private val nioPrefix = "java.nio.file"

    /** spec §6 钉死 minSdk 23（Android 6.0），java.nio.file 要 API 26——公共 API 一律禁用。 */
    @Test
    fun `scanner public api avoids java nio file types`() {
        val ctor = LocalMediaSource::class.java.constructors.single()
        assertTrue(
            ctor.parameterTypes.none { it.name.startsWith(nioPrefix) },
            "LocalMediaSource 构造器不得使用 java.nio.file 类型，实际: ${ctor.parameterTypes.joinToString()}",
        )
        assertTrue(
            MediaSource::class.java.declaredMethods.none { m ->
                m.parameterTypes.any { it.name.startsWith(nioPrefix) } ||
                    m.returnType.name.startsWith(nioPrefix)
            },
            "MediaSource SPI 方法签名不得使用 java.nio.file 类型",
        )
    }
}
