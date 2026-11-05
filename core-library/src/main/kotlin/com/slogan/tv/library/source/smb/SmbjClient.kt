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
 * smbj 真实适配器（薄层，无单测——真机 A55+NAS 验收）。
 * 路径映射：SmbMediaSource 传入的 dir 带 shareRoot 前缀（首段=共享名），
 * 本适配器剥掉共享名前缀，映射为共享内路径。
 */
class SmbjClient(
    private val config: MediaSourceConfig,
    private val password: String?,
) : SmbFileClient {

    private val shareName: String = config.shareRoot!!.substringBefore('/')
    private val rootInShare: String = config.shareRoot!!.substringAfter('/', "")

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
            val auth = AuthenticationContext(
                config.username,
                password?.toCharArray() ?: CharArray(0),
                config.domain ?: "",
            )
            val session = connection.authenticate(auth)
            val disk = session.connectShare(shareName) as DiskShare
            share = disk
            return disk
        } catch (e: SMBApiException) {
            throw e.toCommunicationException()
        } catch (e: TransportException) {
            throw SmbCommunicationException(e.message ?: "连接失败", SmbCommunicationException.Kind.TIMEOUT)
        } catch (e: IOException) {
            throw SmbCommunicationException(e.message ?: "连接失败", SmbCommunicationException.Kind.TIMEOUT)
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            val kind =
                if (msg.contains("SMB1", ignoreCase = true)) SmbCommunicationException.Kind.PROTOCOL
                else SmbCommunicationException.Kind.IO
            throw SmbCommunicationException(msg, kind)
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
                -> emptyList()
                else -> throw e.toCommunicationException()
            }
        }
    }

    private fun SMBApiException.toCommunicationException(): SmbCommunicationException = when (status) {
        NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCESS_DENIED ->
            SmbCommunicationException(message ?: "认证失败", SmbCommunicationException.Kind.AUTH)
        else -> SmbCommunicationException(message ?: "smb error", SmbCommunicationException.Kind.IO)
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

    private companion object {
        const val TIMEOUT_SEC = 10L
        const val FILE_ATTRIBUTE_DIRECTORY = 0x10L
    }
}
