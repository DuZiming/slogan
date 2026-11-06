package com.slogan.tv.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.share.DiskShare
import com.slogan.tv.library.audio.PassthroughMode
import com.slogan.tv.library.stream.LoopbackStreamServer
import com.slogan.tv.library.stream.PlaybackUriResolver
import com.slogan.tv.library.stream.SmbStreamSource
import com.slogan.tv.player.audio.AndroidAudioCapabilities
import com.slogan.tv.player.engine.MpvEngine
import com.slogan.tv.player.engine.PlaybackOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 真机验收工具（仅 debug 构建）。
 *
 * 用法（adb）：
 *  本地文件： am start -n com.slogan.tv/.app.DebugPlayerActivity --es url /sdcard/sample.mp4
 *  SMB 流：   am start -n com.slogan.tv/.app.DebugPlayerActivity \
 *             --es host 192.168.1.188 --es share 电影 --es user admin --es pass **** [--es path 子目录]
 *  直通模式： --es spdif auto|force|pcm
 */
class DebugPlayerActivity : ComponentActivity() {

    private lateinit var engine: MpvEngine  // 后台线程延迟初始化，用 isInitialized 检查
    private lateinit var stateOverlay: TextView
    private var server: LoopbackStreamServer? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 强制窗口有背景 → 首帧可绘制 → splash screen 退出 → TextureView surface 可创建
        window.setBackgroundDrawableResource(android.R.color.black)

        stateOverlay = TextView(this).apply {
            setTextColor(Color.GREEN)
            setBackgroundColor(0x66000000)
            textSize = 14f
            setPadding(24, 24, 24, 24)
        }
        val surfaceView = android.view.SurfaceView(this)
        surfaceView.setZOrderMediaOverlay(true)
        surfaceView.holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        overlayParams.gravity = Gravity.TOP or Gravity.START
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    surfaceView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(stateOverlay, overlayParams)
            },
        )

        // 延迟初始化：mpv 创建/初始化在后台线程，不阻塞主线程首帧绘制
        lifecycleScope.launch(Dispatchers.IO) {
            val eng = MpvEngine(this@DebugPlayerActivity)
            withContext(Dispatchers.Main) {
                engine = eng
                stateOverlay.text = "engine ready"
            }
            launch {
                eng.state.collect { state ->
                    withContext(Dispatchers.Main) {
                        stateOverlay.text = "state: $state\n直通: ${intent.getStringExtra("spdif") ?: "auto"}"
                    }
                }
            }
        }

        surfaceView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                android.util.Log.d("DebugPlayer", "surfaceCreated!")
                lifecycleScope.launch(Dispatchers.IO) {
                    var wait = 0
                    while (::engine.isInitialized.not() && wait < 10000) {
                        Thread.sleep(100)
                        wait += 100
                    }
                    if (::engine.isInitialized) {
                        val ok = engine.attach(holder.surface)
                        withContext(Dispatchers.Main) {
                            stateOverlay.append("\nattach=$ok")
                        }
                        startPlayback()
                    }
                }
            }

            override fun surfaceChanged(holder: android.view.SurfaceHolder, f: Int, w: Int, h: Int) = Unit
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                if (::engine.isInitialized) engine.detach()
            }
        })
    }

    private suspend fun startPlayback() {
        val url = intent.getStringExtra("url")
        val host = intent.getStringExtra("host")

        val playUrl = when {
            url != null -> url
            host != null -> openSmbViaLoopback(host)
            else -> {
                stateOverlay.text = "需要 url 或 host 参数"
                return
            }
        }

        val caps = AndroidAudioCapabilities(this)()
        val mode = when (intent.getStringExtra("spdif")) {
            "force" -> PassthroughMode.FORCE_PASSTHROUGH
            "pcm" -> PassthroughMode.FORCE_PCM
            else -> PassthroughMode.AUTO
        }
        val codec = intent.getStringExtra("acodec")
        val decision = com.slogan.tv.library.audio.PassthroughPolicy.resolve(mode, caps, codec)
        stateOverlay.append("\n设备编码: ${caps.supportedEncodings}\n决策: ${decision.reason}")

        engine.load(
            playUrl,
            PlaybackOptions(
                hwdec = true,
                audioSpdif = decision.spdifCodecs,
            ),
        )
    }

    /** SMB 验收路径：smbj 扫到第一个视频 → 回环流 → 播放（凭据由 adb 传入，不落盘）。 */
    private suspend fun openSmbViaLoopback(host: String): String = withContext(Dispatchers.IO) {
        val share = intent.getStringExtra("share") ?: error("缺 share")
        val user = intent.getStringExtra("user")
        val pass = intent.getStringExtra("pass")
        val sub = intent.getStringExtra("path") ?: ""

        val config = SmbConfig.builder().withTimeout(10, TimeUnit.SECONDS).withSoTimeout(10, TimeUnit.SECONDS).build()
        val client = SMBClient(config)
        val connection = client.connect(host)
        val session = connection.authenticate(
            com.hierynomus.smbj.auth.AuthenticationContext(
                user ?: "",
                (pass ?: "").toCharArray(),
                "",
            ),
        )
        val disk = session.connectShare(share) as DiskShare
        val dirPath = sub.removePrefix("/").removeSuffix("/")
        val video = disk.list(dirPath).firstOrNull {
            (it.fileAttributes and 0x10L) == 0L &&
                it.fileName.substringAfterLast('.', "").lowercase() in VIDEO_EXTS
        } ?: error("共享里没有视频文件")

        val remotePath = if (dirPath.isEmpty()) video.fileName else "$dirPath/${video.fileName}"
        val server = LoopbackStreamServer().also { server = it }
        val resolver = PlaybackUriResolver(server) { file ->
            SmbStreamSource(video.endOfFile) { offset ->
                runCatching {
                    val f = disk.openFile(
                        remotePath,
                        setOf(AccessMask.GENERIC_READ),
                        emptySet(),
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        emptySet(),
                    )
                    f.inputStream.use { s -> s.skip(offset); s }
                }.getOrThrow()
            }
        }
        resolver.resolve(
            com.slogan.tv.library.model.MediaFile("smb", remotePath, video.fileName, video.endOfFile, 1L, remotePath),
        )
    }

    private companion object {
        val VIDEO_EXTS = setOf("mkv", "mp4", "avi", "ts", "m2ts")
    }

    override fun onDestroy() {
        engine.release()
        server?.stop()
        super.onDestroy()
    }
}
