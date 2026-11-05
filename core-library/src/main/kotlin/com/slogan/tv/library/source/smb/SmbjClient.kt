package com.slogan.tv.library.source.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.slogan.tv.library.source.MediaSourceConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * smbj 真实适配器（薄层——认证构造有单测，连接/列目录行为真机 A55+NAS 验收）。
 * 路径映射：SmbMediaSource 传入的 dir 带 shareRoot 前缀（首段=共享名），
 * 本适配器剥掉共享名前缀，映射为共享内路径。
 * 所有异常统一分类为 SmbCommunicationException，绝不裸抛（MediaSource 契约）。
 */
class SmbjClient(
    private val config: MediaSourceConfig,
    private val password: String?,
) : SmbFileClient {

    private val normalizedRoot: String = config.normalizedShareRoot!!
    private val shareName: String = normalizedRoot.substringBefore('/')
    private val rootInShare: String = normalizedRoot.substringAfter('/', "")

    private var client: SMBClient? = null
    private var share: DiskShare? = null

    @Synchronized
    private fun ensureConnected(): DiskShare {
        share?.let { return it }
        try {
            val smbClient = client ?: SMBClient(
                SmbConfig.builder()
                    .withTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                    .withSoTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                    .build(),
            ).also { client = it }
            val connection = smbClient.connect(config.host, config.effectivePort)
            val session = connection.authenticate(buildAuthContext(config, password))
            val disk = try {
                session.connectShare(shareName) as DiskShare
            } catch (e: SMBApiException) {
                // 共享名打错是高频输入错误（评审 I7）：单独给出可读文案
                throw when (e.status) {
                    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
                    NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
                    -> SmbCommunicationException("共享名不存在：$shareName", SmbCommunicationException.Kind.IO)
                    else -> e.toCommunicationException()
                }
            }
            share = disk
            return disk
        } catch (e: SmbCommunicationException) {
            throw e
        } catch (e: Exception) {
            throw e.toCommunicationException()
        }
    }

    override suspend fun list(dir: String): List<SmbEntry> {
        val disk = ensureConnected()
        val inShare = when {
            dir == shareName -> rootInShare
            dir.startsWith("$shareName/") -> dir.substring(shareName.length + 1)
            else -> throw SmbCommunicationException("unexpected dir prefix: $dir", SmbCommunicationException.Kind.IO)
        }
        return try {
            disk.list(inShare).map { info ->
                SmbEntry(
                    name = info.fileName,
                    isDirectory = info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY != 0L,
                    sizeBytes = info.endOfFile,
                    lastModifiedEpochMs = info.lastWriteTime?.toEpochMillis() ?: 0L,
                )
            }
        } catch (e: SMBApiException) {
            when (e.status) {
                NtStatus.STATUS_NO_SUCH_FILE,
                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
                NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
                -> emptyList() // 深层子目录不存在：视为空目录
                else -> {
                    invalidateIfFatal()
                    throw e.toCommunicationException()
                }
            }
        } catch (e: Exception) {
            // 断连类运行时异常统一兜底（评审 I3），并使缓存失效以便下次重连
            invalidateIfFatal()
            throw e.toCommunicationException()
        }
    }

    /** 连接级异常后清缓存共享，避免后续目录全部打在死连接上。 */
    @Synchronized
    private fun invalidateIfFatal() {
        try {
            share?.close()
        } catch (expected: Exception) {
            // 连接已死，关闭失败忽略
        }
        share = null
    }

    private fun SMBApiException.toCommunicationException(): SmbCommunicationException = when (status) {
        NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCESS_DENIED ->
            SmbCommunicationException(message ?: "认证失败", SmbCommunicationException.Kind.AUTH)
        else -> SmbCommunicationException(message ?: "smb error", SmbCommunicationException.Kind.IO)
    }

    private fun Exception.toCommunicationException(): SmbCommunicationException {
        val msg = message ?: javaClass.simpleName
        return when (this) {
            is TransportException, is IOException ->
                SmbCommunicationException(msg, SmbCommunicationException.Kind.TIMEOUT)
            else ->
                SmbCommunicationException(msg, SmbCommunicationException.Kind.IO)
        }
    }

    @Synchronized
    override fun close() {
        try {
            share?.close()
        } finally {
            client?.close()
            share = null
            client = null
        }
    }

    internal companion object {
        const val TIMEOUT_SEC = 10L
        const val FILE_ATTRIBUTE_DIRECTORY = 0x10L

        /** 匿名/凭据登录统一构造（评审 C1：null username 走 anonymous()，防 smbj 内部 NPE）。 */
        fun buildAuthContext(config: MediaSourceConfig, password: String?): AuthenticationContext =
            if (config.username.isNullOrBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(
                    config.username,
                    password?.toCharArray() ?: CharArray(0),
                    config.domain ?: "",
                )
            }
    }
}
