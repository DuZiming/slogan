package com.slogan.tv.player.core

import android.content.Context
import android.util.Log
import android.view.Surface

/**
 * 自有 mpv 核心封装（替换 dev.jdtech.mpv AAR 的 MPVLib）。
 * 底层由 slogan_mpv_jni.c 桥接 LGPL libmpv，内置 EGL 渲染桥。
 * 与 MPVLib 接口面兼容，MpvEngine 改 import 即可切换。
 */
class MpvCore private constructor(context: Context) {

    val nativePtr: Long = nativeCreate()

    companion object {
        init {
            System.loadLibrary("slogan-mpv")
        }

        fun create(context: Context): MpvCore? = try {
            MpvCore(context)
        } catch (e: Exception) {
            Log.e("MpvCore", "create failed", e)
            null
        }
    }

    fun init(): Boolean = nativeInit(nativePtr)

    fun destroy() = nativeDestroy(nativePtr)

    fun attachSurface(surface: Surface): Boolean = nativeAttachSurface(nativePtr, surface)

    fun detachSurface() = nativeDetachSurface(nativePtr)

    fun command(vararg args: String): Int = nativeCommand(nativePtr, args as Array<String>)

    fun setOptionString(name: String, value: String): Int =
        nativeSetOptionString(nativePtr, name, value)

    fun getPropertyInt(name: String): Int? = runCatching { nativeGetPropertyInt(nativePtr, name) }.getOrNull()

    fun getPropertyDouble(name: String): Double? = runCatching { nativeGetPropertyDouble(nativePtr, name) }.getOrNull()

    fun getPropertyString(name: String): String? = nativeGetPropertyString(nativePtr, name)

    fun getPropertyBoolean(name: String): Boolean? = runCatching { nativeGetPropertyBoolean(nativePtr, name) }.getOrNull()

    fun setPropertyInt(name: String, value: Int): Int = nativeSetPropertyInt(nativePtr, name, value)

    fun setPropertyDouble(name: String, value: Double): Int = nativeSetPropertyDouble(nativePtr, name, value)

    fun setPropertyString(name: String, value: String): Int = nativeSetPropertyString(nativePtr, name, value)

    fun setPropertyBoolean(name: String, value: Boolean): Int = nativeSetPropertyBoolean(nativePtr, name, value)

    fun observeProperty(name: String, id: Int): Int = nativeObserveProperty(nativePtr, name, id)

    /** mpv 内核有事件待处理时由 native 调用 → Kotlin 侧处理属性变更 */
    fun onMpvEvent(dummy: Int) {
        nativeProcessEvents(nativePtr)
    }

    /** native 回调：属性变更（Double） */
    fun eventProperty(name: String, value: Double) {
        listeners.forEach { it.eventProperty(name, value) }
    }

    /** native 回调：属性变更（Boolean） */
    fun eventProperty(name: String, value: Boolean) {
        listeners.forEach { it.eventProperty(name, value) }
    }

    /** native 回调：属性变更（String） */
    fun eventProperty(name: String, value: String) {
        listeners.forEach { it.eventProperty(name, value) }
    }

    /** native 回调：属性变更（Long） */
    fun eventProperty(name: String, value: Long) {
        listeners.forEach { it.eventProperty(name, value) }
    }

    /** native 回调：属性不可用 */
    fun eventProperty(name: String) {
        listeners.forEach { it.eventProperty(name) }
    }

    /** native 回调：mpv 事件（FILE_LOADED 等） */
    fun mpvEvent(eventId: Int) {
        listeners.forEach { it.mpvEvent(eventId) }
    }

    /** native 回调：日志 */
    fun logMessage(prefix: String, level: Int, text: String) {
        listeners.forEach { it.logMessage(prefix, level, text) }
    }

    interface EventListener {
        fun eventProperty(name: String, value: Double) {}
        fun eventProperty(name: String, value: Boolean) {}
        fun eventProperty(name: String, value: String) {}
        fun eventProperty(name: String, value: Long) {}
        fun eventProperty(name: String) {}
        fun mpvEvent(eventId: Int) {}
        fun logMessage(prefix: String, level: Int, text: String) {}
    }

    private val listeners = mutableListOf<EventListener>()

    fun addEventListener(listener: EventListener) {
        listeners.add(listener)
    }

    fun removeEventListener(listener: EventListener) {
        listeners.remove(listener)
    }

    // ---- native 方法 ----
    private external fun nativeCreate(): Long
    private external fun nativeInit(ptr: Long): Boolean
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeAttachSurface(ptr: Long, surface: Surface): Boolean
    private external fun nativeDetachSurface(ptr: Long)
    private external fun nativeCommand(ptr: Long, args: Array<String>): Int
    private external fun nativeSetOptionString(ptr: Long, name: String, value: String): Int
    private external fun nativeGetPropertyInt(ptr: Long, name: String): Int
    private external fun nativeGetPropertyDouble(ptr: Long, name: String): Double
    private external fun nativeGetPropertyString(ptr: Long, name: String): String?
    private external fun nativeGetPropertyBoolean(ptr: Long, name: String): Boolean
    private external fun nativeSetPropertyInt(ptr: Long, name: String, value: Int): Int
    private external fun nativeSetPropertyDouble(ptr: Long, name: String, value: Double): Int
    private external fun nativeSetPropertyString(ptr: Long, name: String, value: String): Int
    private external fun nativeSetPropertyBoolean(ptr: Long, name: String, value: Boolean): Int
    private external fun nativeObserveProperty(ptr: Long, name: String, id: Int): Int
    private external fun nativeProcessEvents(ptr: Long)
    private external fun nativeGetMpvHandle(ptr: Long): Long
}
