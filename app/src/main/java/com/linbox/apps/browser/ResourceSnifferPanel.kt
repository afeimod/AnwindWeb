package com.linbox.apps.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ===========================================================================
// v1.3.0：资源嗅探面板（用户："嗅探 swf 以及相关文件和文件夹识别后
// 选择性下载，以及嗅探网页后台下载的资源文件以及相关文件夹"）
// ===========================================================================

/**
 * 嗅探面板：悬浮球菜单「资源嗅探」打开。
 *
 * - 类型过滤 chips：全部 / SWF / 图片 / 视频 / 音频 / 脚本 / 其他；
 * - 目录树：host → 目录（勾选目录 = 级联全选目录内可见文件）→ 文件；
 * - 勾选批量下载：AppDownloader 队列（3 并发 + 状态栏进度通知），
 *   落盘 `Download/Sniffer/<host>/<站点目录>/`（保留站点文件夹结构）；
 * - 登录态：下载时带该 URL Cookie + 当前页 Referer + WebView UA。
 */
@Composable
fun ResourceSnifferPanel() {
    if (!ResourceSnifferPanelBus.visible) return

    // 数据版本驱动刷新（record 在 IO 线程 bump version）
    val version by remember { derivedStateOf { ResourceSniffer.version } }
    val resources = remember(version) { ResourceSniffer.snapshot() }

    var filter by remember { mutableStateOf<ResourceSniffer.Kind?>(null) }
    val selected = remember { mutableStateOf(setOf<String>()) }
    val expandedDirs = remember { mutableStateOf(setOf<String>()) }

    // 过滤结果（chips 变化时重算；目录展开默认全展开）
    val filtered = remember(version, filter) {
        resources.filter { filter == null || it.kind == filter }
    }
    LaunchedEffect(version, filter) {
        expandedDirs.value = buildSet {
            filtered.forEach { add("${it.host}/${it.dir}") }
        }
    }

    // 分组：host → dir → 文件
    data class DirGroup(val host: String, val dir: String, val files: List<ResourceSniffer.Res>)
    val groups = remember(version, filter) {
        filtered.groupBy { it.host to it.dir }
            .map { (k, v) -> DirGroup(k.first, k.second, v) }
            .sortedWith(
                compareBy(
                    { it.host != ResourceSniffer.pageHost() },
                    { it.host }, { it.dir }
                )
            )
    }

    fun dirKey(g: DirGroup) = "${g.host}/${g.dir}"
    fun dirVisibleFiles(g: DirGroup) = g.files
    fun toggleDir(g: DirGroup) {
        val key = dirKey(g)
        val visibles = dirVisibleFiles(g).map { it.url }.toSet()
        val allIn = visibles.all { it in selected.value }
        selected.value = if (allIn) selected.value - visibles else selected.value + visibles
    }

    val selectedCount = selected.value.size
    val selectedBytes = remember(selected.value, version) {
        resources.filter { it.url in selected.value }.sumOf { if (it.size > 0) it.size else 0L }
    }

    Dialog(
        onDismissRequest = { ResourceSnifferPanelBus.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .background(Color(0xF2162434), RoundedCornerShape(18.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp))
        ) {
            // ---- 标题栏 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("资源嗅探", color = Color.White, fontSize = 17.sp)
                    Text(
                        "${ResourceSniffer.pageHost().ifBlank { "当前页" }} · 共 ${resources.size} 项" +
                            (ResourceSniffer.pageUrl.takeIf { it.isNotBlank() }
                                ?.let { " · ${it.take(60)}" } ?: ""),
                        color = Color(0xFF9FB3C8), fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = {
                    ResourceSniffer.clear()
                    selected.value = emptySet()
                }) { Text("清空", fontSize = 13.sp) }
                TextButton(onClick = { ResourceSnifferPanelBus.close() }) {
                    Text("关闭", fontSize = 13.sp)
                }
            }

            // ---- 类型过滤 chips ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                FilterChip("全部 ${resources.size}", filter == null) { filter = null }
                ResourceSniffer.Kind.entries.forEach { k ->
                    val n = resources.count { it.kind == k }
                    if (n > 0) FilterChip("${k.label} $n", filter == k) { filter = k }
                }
            }

            // ---- 目录树列表 ----
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
            ) {
                items(groups, key = { dirKey(it) + "#" + it.files.hashCode() }) { g ->
                    val key = dirKey(g)
                    val expanded = key in expandedDirs.value
                    val visibles = dirVisibleFiles(g)
                    val allIn = visibles.all { it.url in selected.value }
                    val someIn = visibles.any { it.url in selected.value }

                    // 目录行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedDirs.value =
                                    if (expanded) expandedDirs.value - key
                                    else expandedDirs.value + key
                            }
                            .padding(vertical = 7.dp, horizontal = 6.dp)
                    ) {
                        Text(
                            if (expanded) "▾" else "▸",
                            color = Color(0xFF9FB3C8), fontSize = 13.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        CheckboxTri(allIn, someIn) { toggleDir(g) }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                g.host,
                                color = Color(0xFF8FD6FF),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (g.dir.isNotBlank()) {
                                Text(
                                    "/${g.dir}",
                                    color = Color(0xFF9FB3C8),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Text(
                            "${visibles.size} 项",
                            color = Color(0xFF9FB3C8), fontSize = 11.sp
                        )
                    }

                    // 文件行
                    if (expanded) {
                        visibles.forEach { res ->
                            val checked = res.url in selected.value
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected.value =
                                            if (checked) selected.value - res.url
                                            else selected.value + res.url
                                    }
                                    .padding(
                                        start = 34.dp, end = 6.dp,
                                        top = 5.dp, bottom = 5.dp
                                    )
                            ) {
                                CheckboxTri(checked, false) {
                                    selected.value =
                                        if (checked) selected.value - res.url
                                        else selected.value + res.url
                                }
                                Spacer(Modifier.width(8.dp))
                                KindDot(res.kind)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    res.name,
                                    color = Color(0xFFE6EDF3),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (res.size > 0) formatBytesInternal(res.size) else "—",
                                    color = Color(0xFF9FB3C8), fontSize = 10.sp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    res.kind.label,
                                    color = Color(0xFF9FB3C8), fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
                item {
                    if (groups.isEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 60.dp)
                        ) {
                            Text(
                                "暂无嗅探记录\n打开网页后页面加载的图片/脚本/媒体/SWF 会出现在这里",
                                color = Color(0xFF9FB3C8), fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            // ---- 底部：已选 + 批量下载 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "已选 $selectedCount 项",
                        color = Color.White, fontSize = 14.sp
                    )
                    Text(
                        if (selectedBytes > 0) "约 ${formatBytesInternal(selectedBytes)}（未知大小项不计）"
                        else "大小以下载时通知为准",
                        color = Color(0xFF9FB3C8), fontSize = 11.sp
                    )
                }
                TextButton(
                    onClick = {
                        val picked = resources.filter { it.url in selected.value }
                        if (picked.isEmpty()) return@TextButton
                        val pageReferer = ResourceSniffer.pageUrl
                            .takeIf { it.startsWith("http") }
                        val ua = android.webkit.WebSettings.getDefaultUserAgent(
                            com.linbox.LinBoxApp.get()
                        )
                        AppDownloader.enqueueAll(
                            picked.map { r ->
                                AppDownloader.Job(
                                    url = r.url,
                                    filename = r.name.ifBlank { "resource_${r.ts}" },
                                    mime = kindMime(r),
                                    userAgent = ua,
                                    cookie = runCatching {
                                        android.webkit.CookieManager.getInstance()
                                            .getCookie(r.url)
                                    }.getOrNull(),
                                    referer = pageReferer,
                                    subdir = listOf("Sniffer", r.host, r.dir)
                                        .filter { it.isNotBlank() }
                                        .joinToString("/")
                                )
                            }
                        )
                        selected.value = emptySet()
                        ResourceSnifferPanelBus.close()
                        BrowserDownloads.toast(
                            com.linbox.LinBoxApp.get(), "已加入下载队列：${picked.size} 项"
                        )
                    },
                    enabled = selectedCount > 0
                ) {
                    Text(
                        if (selectedCount > 0) "下载所选（$selectedCount）" else "下载所选",
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

// ---- 面板内小组件 ----

/** 类型过滤 chip。 */
@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .background(
                if (active) Color(0xFF2E7CF6) else Color(0x1FFFFFFF),
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                if (active) Color(0xFF5C9DFF) else Color(0x22FFFFFF),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

/** 三态勾选框（全选 / 部分选 / 空）。 */
@Composable
private fun CheckboxTri(checked: Boolean, partial: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(18.dp)
            .border(
                1.5.dp,
                if (checked || partial) Color(0xFF2E7CF6) else Color(0x66FFFFFF),
                RoundedCornerShape(4.dp)
            )
            .background(
                if (checked) Color(0xFF2E7CF6) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
    ) {
        when {
            checked -> Text("✓", color = Color.White, fontSize = 12.sp)
            partial -> Box(
                Modifier
                    .size(8.dp)
                    .background(Color(0xFF2E7CF6), RoundedCornerShape(2.dp))
            )
        }
    }
}

/** 类型徽标点。 */
@Composable
private fun KindDot(kind: ResourceSniffer.Kind) {
    val c = when (kind) {
        ResourceSniffer.Kind.SWF -> Color(0xFFE5484D)
        ResourceSniffer.Kind.IMAGE -> Color(0xFF46A758)
        ResourceSniffer.Kind.VIDEO -> Color(0xFF8E4EC6)
        ResourceSniffer.Kind.AUDIO -> Color(0xFFFFB224)
        ResourceSniffer.Kind.SCRIPT -> Color(0xFF2E7CF6)
        ResourceSniffer.Kind.OTHER -> Color(0xFF9FB3C8)
    }
    Box(
        Modifier
            .size(8.dp)
            .background(c, CircleShape)
    )
}

/** 嗅探资源的常见 MIME（落盘扩展名/MediaStore 用；未知返回 null）。 */
private fun kindMime(r: ResourceSniffer.Res): String? = when (r.kind) {
    ResourceSniffer.Kind.SWF -> "application/x-shockwave-flash"
    ResourceSniffer.Kind.IMAGE -> when (r.ext) {
        "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
        "svg" -> "image/svg+xml"; "bmp" -> "image/bmp"; "avif" -> "image/avif"
        else -> "image/jpeg"
    }
    ResourceSniffer.Kind.VIDEO -> when (r.ext) {
        "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"
        else -> "video/mp4"
    }
    ResourceSniffer.Kind.AUDIO -> when (r.ext) {
        "m4a" -> "audio/mp4"; "ogg" -> "audio/ogg"; "wav" -> "audio/x-wav"
        "flac" -> "audio/flac"; "mid" -> "audio/midi"; "wma" -> "audio/x-ms-wma"
        else -> "audio/mpeg"
    }
    ResourceSniffer.Kind.SCRIPT -> when (r.ext) {
        "js", "mjs" -> "application/javascript"; "css" -> "text/css"
        "json" -> "application/json"; "xml" -> "application/xml"
        else -> "text/html"
    }
    ResourceSniffer.Kind.OTHER -> null
}

/** 字节数（面板内轻量版；DownloadCenter 的 internal formatBytes 同源语义）。 */
private fun formatBytesInternal(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
}
