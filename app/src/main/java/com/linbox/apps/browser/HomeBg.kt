package com.linbox.apps.browser

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.activity.result.ActivityResultLauncher
import com.linbox.LinBoxApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * v1.2.0（用户需求 5）：自定义主页背景（图片或视频）。
 *
 * 链路：
 * - 主页右上角「背景」按钮 → [pick] → MainActivity 注册的
 *   ActivityResultContracts.OpenDocument（接受图片与视频两类 MIME）
 * - 选中的 content:// 立即拷贝到 filesDir/home_bg/bg.<ext>
 *   （SAF 授权不可长期依赖，落私有目录稳定可靠；旧文件清掉防膨胀）
 * - 写偏好 "image:/路径" / "video:/路径" → 主页 Compose 读
 *   settingsStore.homepageBg 实时换装
 *
 * 视频形态见 [VideoBgView]：循环 + 静音 + 裁剪铺满，作纯背景氛围。
 */
object HomeBgManager {

    /** MainActivity.onCreate 注入的文档选择器（OpenDocument）。 */
    var pickerLauncher: ActivityResultLauncher<Array<String>>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 打开系统选择器（同时接受图片与视频）。 */
    fun pick() {
        val launcher = pickerLauncher ?: return
        runCatching { launcher.launch(arrayOf("image/*", "video/*")) }
    }

    /** 用户选定文件后：拷贝落盘 + 写偏好（IO 线程）。 */
    fun onPicked(context: Context, uri: Uri) {
        scope.launch {
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: ""
                val isVideo = mime.startsWith("video/")
                val isImage = mime.startsWith("image/")
                if (!isVideo && !isImage) return@launch

                // 扩展名取 MIME 子段（image/jpeg → jpeg）；异常长度兜底
                val sub = mime.substringAfter('/', "").substringBefore(';').lowercase()
                val ext = if (sub.length in 2..5) sub else if (isVideo) "mp4" else "png"

                val dir = File(context.filesDir, "home_bg").apply { mkdirs() }
                // 单背景策略：清旧再写新
                dir.listFiles()?.forEach { it.delete() }
                val dest = File(dir, "bg.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: return@launch

                val value = (if (isVideo) "video:" else "image:") + dest.absolutePath
                LinBoxApp.get().settingsStore.setHomepageBg(value)
            }
        }
    }

    /** 清除自定义背景（回到默认纯色主页）。 */
    fun clear() {
        scope.launch {
            runCatching {
                LinBoxApp.get().settingsStore.setHomepageBg("")
                // 背景文件留在磁盘也无妨，但顺手清掉更干净
                LinBoxApp.get().filesDir.resolve("home_bg").listFiles()?.forEach { it.delete() }
            }
        }
    }
}

/**
 * 主页背景视频视图：TextureView + MediaPlayer。
 * - 循环播放（isLooping）
 * - 静音（setVolume(0,0)，纯氛围背景不干扰）
 * - VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING 居中裁剪铺满
 * - Surface 尚未就绪时挂起路径，available 回调里启动
 * - 释放：AndroidView onRelease → [releasePlayer]（防主页离开后仍解码耗电）
 */
class VideoBgView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private var player: MediaPlayer? = null
    private var pendingPath: String? = null

    init {
        surfaceTextureListener = this
    }

    fun setSource(path: String) {
        // 重组幂等：同一路径且播放器健在 → 不重启（AndroidView.update 会反复调用）
        if (path == pendingPath && player != null) return
        pendingPath = path
        if (isAvailable) startPlayer(path)
    }

    private fun startPlayer(path: String) {
        releasePlayer()
        player = MediaPlayer().apply {
            runCatching {
                setDataSource(path)
                setSurface(Surface(surfaceTexture))
                setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { it.start() }
                prepareAsync()
            }.onFailure {
                runCatching { release() }
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        pendingPath?.let { startPlayer(it) }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun releasePlayer() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
    }
}
