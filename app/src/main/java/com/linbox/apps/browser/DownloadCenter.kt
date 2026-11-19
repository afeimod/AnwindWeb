package com.linbox.apps.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * LinBrowser 下载中心（独立版新增）。
 *
 * 修复 LinBox 浏览器的两个下载问题：
 *
 * 1. **下载前重命名确认**（用户："下载时提示重命名"）：
 *    任何下载（http/https、blob:、data:）在真正落盘前弹出确认对话框 ——
 *    文件名可编辑（重命名）、显示大小/类型/来源，确认才开始下载；
 *    blob:/data: 在数据就绪后再确认（取消即丢弃，零残留）。
 *
 * 2. **文件上传断链修复**：原版 onShowFileChooser 用
 *    startActivityForResult(intent, 0) 启动选择器，但全工程没有任何
 *    onActivityResult 把结果回传给 filePathCallback —— 选择文件后页面
 *    永远收不到结果（回调泄漏）。这里用 [FileChooserBus] 集中登记，
 *    MainActivity.onActivityResult 统一回传 onReceiveValue。
 */

// ===========================================================================
// 文件上传总线（input[type=file] 断链修复）
// ===========================================================================

/**
 * WebView 文件选择回调总线：onShowFileChooser 登记回调 → 系统选择器 →
 * MainActivity.onActivityResult 回传结果。同一时刻只挂一个选择会话
 * （新会话开始时旧回调按取消收尾，防泄漏）。
 */
object FileChooserBus {
    private const val REQUEST_CODE = 0x6C62 // "lbr"

    private var pending: ValueCallback<Array<Uri>>? = null

    /**
     * 启动系统文件选择器（onShowFileChooser 专用）。
     * 返回 true = 已接手（WebView 不再走默认行为）。
     */
    fun launch(
        ctx: Context,
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        if (callback == null) return false
        // 旧会话未收尾 → 先按取消回传（防"上传一次后再也传不了"）
        pending?.onReceiveValue(null)
        pending = callback
        val intent = try {
            params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        } catch (_: Exception) {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        }
        return try {
            val activity = ctx as? Activity
                ?: com.linbox.util.ImmersiveMode.findActivity(ctx)
            if (activity != null) {
                intent.addFlags(0) // 同 Activity 内不需要 NEW_TASK
                activity.startActivityForResult(intent, REQUEST_CODE)
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }
            true
        } catch (_: Exception) {
            pending = null
            callback.onReceiveValue(null)
            false
        }
    }

    /** MainActivity.onActivityResult 转发入口；返回 true = 已消费。 */
    fun onActivityResult(
        ctx: Context,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val cb = pending ?: return true
        pending = null
        val ok = resultCode == Activity.RESULT_OK
        val uris = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        cb.onReceiveValue(if (ok && uris != null && uris.isNotEmpty()) uris else null)
        return true
    }
}

// ===========================================================================
// 下载请求模型 + 弹窗总线
// ===========================================================================

/** 下载请求快照（三种来源；数据在确认后就绪可写）。 */
sealed class DownloadRequest {
    abstract val suggestedName: String
    abstract val mime: String?
    abstract val contentLength: Long

    /** http/https：经系统 DownloadManager（登录态 Cookie/Referer/UA 已快照）。 */
    data class Http(
        override val suggestedName: String,
        override val mime: String?,
        override val contentLength: Long,
        val url: String,
        val userAgent: String?,
        val cookie: String?,
        val referer: String?
    ) : DownloadRequest()

    /** blob:：页内 XHR 已读回的临时文件（cache 目录，取消即删）。 */
    data class Blob(
        override val suggestedName: String,
        override val mime: String?,
        override val contentLength: Long,
        val tmpFile: File
    ) : DownloadRequest()

    /** data:：已解码字节。 */
    data class Data(
        override val suggestedName: String,
        override val mime: String?,
        override val contentLength: Long,
        val bytes: ByteArray
    ) : DownloadRequest()
}

/**
 * 下载确认弹窗总线：BrowserEngine 侧投递 [DownloadRequest] →
 * 顶层 [DownloadPromptDialog] 组合观察展示 → 确认/取消回投
 * [BrowserDownloads]。
 */
object DownloadPromptBus {
    /** 当前待确认的下载（null = 无弹窗）。 */
    var pending by mutableStateOf<DownloadRequest?>(null)
        private set

    /** 投递新请求（顶掉旧请求：旧 blob 临时文件清理）。 */
    fun prompt(request: DownloadRequest) {
        (pending as? DownloadRequest.Blob)?.tmpFile?.delete()
        pending = request
    }

    /** 用户确认：用（可能重命名的）文件名开始真正的下载/落盘。 */
    fun confirm(filename: String) {
        val req = pending ?: return
        pending = null
        BrowserDownloads.startConfirmed(req, filename)
    }

    /** 用户取消（blob 临时文件清理）。 */
    fun cancel() {
        val req = pending ?: return
        pending = null
        (req as? DownloadRequest.Blob)?.tmpFile?.delete()
    }
}

// ===========================================================================
// 下载确认对话框（重命名 + 大小/类型/来源）
// ===========================================================================

/** 字节数人性化（下载弹窗用）。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
    else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/**
 * 下载确认弹窗：文件名可编辑（重命名），显示大小/类型/来源与保存位置。
 * 挂在浏览器壳层最顶端（BrowserShell），全部页面共享。
 */
@Composable
fun DownloadPromptDialog() {
    val request = DownloadPromptBus.pending ?: return

    var name by remember(request) { mutableStateOf(request.suggestedName) }

    val origin = when (request) {
        is DownloadRequest.Http -> runCatching { Uri.parse(request.url).host }.getOrNull()
        is DownloadRequest.Blob -> "页面数据 (blob)"
        is DownloadRequest.Data -> "内嵌数据 (data)"
    } ?: "未知来源"

    AlertDialog(
        onDismissRequest = { DownloadPromptBus.cancel() },
        title = { Text("下载文件") },
        text = {
            Column {
                Text(
                    "文件名（可重命名）",
                    fontSize = 12.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(120) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
                Text(
                    buildString {
                        append("大小：").append(formatBytes(request.contentLength))
                        request.mime?.takeIf { it.isNotBlank() }?.let {
                            append("\n类型：").append(it)
                        }
                        append("\n来源：").append(origin)
                        append("\n保存到：Download 目录")
                    },
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clean = name.trim()
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .trim().trim('.')
                    if (clean.isEmpty()) {
                        DownloadPromptBus.cancel()
                    } else {
                        DownloadPromptBus.confirm(clean)
                    }
                }
            ) { Text("下载") }
        },
        dismissButton = {
            TextButton(onClick = { DownloadPromptBus.cancel() }) { Text("取消") }
        }
    )
}
