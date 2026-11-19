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
 *    Activity Result launcher 回调统一回传 onReceiveValue（v1.2.3）。
 */

// ===========================================================================
// 文件上传总线（input[type=file] 断链修复）
// ===========================================================================

/**
 * WebView 文件选择回调总线：onShowFileChooser 登记回调 → 系统选择器 →
 * launcher 回调回传结果。同一时刻只挂一个选择会话（新会话开始时旧回调按
 * 取消收尾，防泄漏）。
 *
 * v1.2.2 勾选多选不落位修复（vivo 等厂商选择器）：
 * - 厂商照片/文件选择器勾选多选时结果走 `ClipData`（`intent.data` 为空），
 *   且部分机型 resultCode 不规范 → 结果收集改为「data + clipData 全量手动收集，
 *   不依赖 resultCode」；
 * - 厂商 provider 返回的 content:// URI 授权对 WebView 直读不总有效
 *   （点选单选恰好走得通，勾选批量则读不到）→ 回传前先复制到应用缓存目录，
 *   经 FileProvider（authorities=包名.fileprovider）交给 WebView，
 *   从根上绕开 provider 权限/授权怪癖；复制失败的单个文件回退原始 URI。
 *
 * v1.2.3：
 * - 迁移 Activity Result API（`onActivityResult` 已废弃，CI 无告警）；
 * - 会话一次性投递（取消 / 新会话 / 后台复制竞争安全）；
 * - 复制移出主线程（大文件上传不再阻塞 UI / ANR），完成后回主线程投递。
 */
object FileChooserBus {
    /** MainActivity 注入的上传选择器 launcher（onCreate 即注入）。 */
    @Volatile
    var launcher: androidx.activity.result.ActivityResultLauncher<Intent>? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 一次上传会话：保证回调恰好投递一次（竞争安全）。 */
    private class Session(val cb: ValueCallback<Array<Uri>>) {
        @Volatile var done = false
        fun deliver(uris: Array<Uri>?) {
            if (done) return
            done = true
            mainHandler.post { cb.onReceiveValue(uris) }
        }
    }

    private var pending: Session? = null

    /**
     * 启动系统文件选择器（onShowFileChooser 专用）。
     * 返回 true = 已接手（WebView 不再走默认行为）。
     */
    fun launch(
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        if (callback == null) return false
        // 旧会话未收尾 → 先按取消投递（防“上传一次后再也传不了”）
        pending?.deliver(null)
        val session = Session(callback)
        pending = session
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
        // v1.2.2：显式声明允许多选（createIntent 虽带，但部分厂商 ROM
        // 对该标志的读取有自己的实现，双保险后勾选界面必走 ClipData 返回）
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        val l = launcher
        return try {
            if (l != null) {
                l.launch(intent)
            } else {
                // 兜底：launcher 未注入（MainActivity onCreate 即注入，理论
                // 不可达）—— 直接按取消收尾，不悬挂回调
                pending = null
                session.deliver(null)
            }
            true
        } catch (_: Exception) {
            pending = null
            session.deliver(null)
            false
        }
    }

    /** 上传选择器结果回传（MainActivity 的 launcher 回调入口）。 */
    fun onResult(
        ctx: Context,
        resultCode: Int,
        data: Intent?
    ) {
        val session = pending ?: return
        pending = null

        // ---- 1) 全量收集 URIs（不依赖 resultCode：厂商选择器勾选路径
        //      常见 resultCode 不规范 + 结果只在 ClipData 里）----
        val collected = mutableListOf<Uri>()
        if (data != null) {
            data.data?.let { if (it !in collected) collected.add(it) }
            data.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) {
                    val item = cd.getItemAt(i)
                    item.uri?.let { if (it !in collected) collected.add(it) }
                    item.intent?.data?.let { if (it !in collected) collected.add(it) }
                    item.text?.toString()
                        ?.takeIf { it.startsWith("content:") || it.startsWith("file:") }
                        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        ?.let { if (it !in collected) collected.add(it) }
                }
            }
        }
        // 官方 parseResult 兜底（结果放在意外位置时的标准解析）
        if (collected.isEmpty()) {
            runCatching {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            }.getOrNull()?.let { collected.addAll(it) }
        }

        // ---- 2) 判定成功与否：resultCode OK 或收到了任何 URI 都算用户选了文件 ----
        val userPicked = resultCode == Activity.RESULT_OK || collected.isNotEmpty()
        if (!userPicked || collected.isEmpty()) {
            session.deliver(null)
            return
        }

        // ---- 3) 复制到应用缓存 → FileProvider 交给 WebView ----
        //      v1.2.3：复制移出主线程（大文件不阻塞 UI / ANR），
        //      完成后回主线程投递（WebView 回调要求主线程）
        val appCtx = ctx.applicationContext
        Thread {
            val delivered = runCatching { copyToCache(appCtx, collected) }
                .getOrDefault(collected)
            session.deliver(delivered.toTypedArray())
        }.start()
    }

    /**
     * 把选中的文件复制到 `cacheDir/uploads/<时间戳>/`，返回 FileProvider URI 列表。
     * 单文件复制失败 → 该位置回退原始 URI（保持数量一致）。
     */
    private fun copyToCache(ctx: Context, sources: List<Uri>): List<Uri> {
        val root = File(ctx.cacheDir, "uploads")
        // 清掉上一轮上传临时目录（会话结束即无用，防缓存膨胀）
        root.deleteRecursively()
        val dir = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        val authority = ctx.packageName + ".fileprovider"
        val out = mutableListOf<Uri>()
        sources.forEachIndexed { index, src ->
            // v1.2.3：val try 表达式（消除「初始化冗余」告警）
            val target: Uri? = try {
                val name = displayName(ctx, src, index)
                val dst = uniqueFile(dir, name)
                ctx.contentResolver.openInputStream(src)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                } ?: throw IllegalStateException("openInputStream returned null")
                if (dst.length() <= 0L) throw IllegalStateException("empty copy")
                runCatching {
                    androidx.core.content.FileProvider.getUriForFile(ctx, authority, dst)
                }.getOrNull() ?: Uri.fromFile(dst)
            } catch (_: Exception) {
                null
            }
            out.add(target ?: src)
        }
        return out
    }

    /** 解析显示名：content:// 查 DISPLAY_NAME → lastPathSegment → 时间戳默认名。 */
    private fun displayName(ctx: Context, uri: Uri, index: Int): String {
        if (uri.scheme == "content") {
            try {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (col >= 0 && c.moveToFirst()) {
                        c.getString(col)?.takeIf { it.isNotBlank() }?.let { return sanitizeName(it) }
                    }
                }
            } catch (_: Exception) {
            }
        }
        uri.lastPathSegment?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }?.let { return sanitizeName(it) }
        return "upload_${System.currentTimeMillis()}_$index"
    }

    /** 清洗文件名（去路径分隔符与非法字符，限长）。 */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.replace(Regex("[/\\\\:*?\"<>|\\n\\r\\t]"), "_").trim()
        return if (cleaned.isBlank()) "upload_file" else cleaned.take(120)
    }

    /** 同名自动 (n) 后缀，避免多选同重名互相覆盖。 */
    private fun uniqueFile(dir: File, name: String): File {
        val f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = File(dir, "$base($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
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
