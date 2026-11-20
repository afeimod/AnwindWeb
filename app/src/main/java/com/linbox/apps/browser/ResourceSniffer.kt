package com.linbox.apps.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URI
import kotlin.concurrent.withLock

// ===========================================================================
// v1.3.0：资源嗅探器（用户："嗅探 swf 以及相关文件和文件夹识别后选择性
// 下载，以及嗅探网页后台下载的资源文件以及相关文件夹"）
// ===========================================================================

/**
 * 页面资源嗅探器：钩在 WebViewClient.shouldInterceptRequest 上被动采集
 * 页面加载的全部 http/https 子资源（图片 / 脚本 / 样式 / 媒体 / XHR / swf），
 * 供 [ResourceSnifferPanel] 展示与勾选批量下载。
 *
 * - **零额外流量**：只记录 URL（请求本身就发生），不预取；
 * - **大小**：URL 本身拿不到 Content-Length —— swf 因 Ruffle 拦截路径
 *   （interceptSwf 全量下载后供给）会回写真实大小，其余显示"—"，
 *   勾选下载后在下载通知里显示实际大小；
 * - **去重**：同一 URL 只记一次（ LinkedHashMap 保留首次出现的顺序，
 *   上限 [MAX] 条环形截断，防长会话内存膨胀）；
 * - **线程**：record 在 WebView IO 线程调用，snapshot 在主线程 —— 全部
 *   持锁（数据量小，无性能问题）。
 */
object ResourceSniffer {

    private const val MAX = 1500
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /** 资源大类（过滤 chips 与徽标渲染）。 */
    enum class Kind(val label: String) {
        SWF("SWF"), IMAGE("图片"), VIDEO("视频"), AUDIO("音频"),
        SCRIPT("脚本"), OTHER("其他")
    }

    /** 一条被嗅探到的资源。 */
    class Res(
        val url: String,
        val host: String,
        /** 站点内目录（以 / 分隔，可含多级；根目录为空串）。 */
        val dir: String,
        /** 文件名（去查询串；无扩展名时为整段）。 */
        val name: String,
        val ext: String,
        val kind: Kind,
        /** 已知大小（swf 拦截路径回写；-1 = 未知）。 */
        var size: Long,
        /** 首次出现时间（排序用）。 */
        val ts: Long
    )

    /** url → 资源（保序去重）。 */
    private val seen = LinkedHashMap<String, Res>()

    /** 当前主文档 URL（嗅探面板展示 + 目录树以该 host 置顶）。 */
    @Volatile
    var pageUrl: String = ""

    /** 数据版本号（Compose 观察刷新面板列表）。 */
    var version by mutableStateOf(0)
        private set

    /** 嗅探到的资源条数（Compose 观察菜单角标/入口用）。 */
    var count by mutableStateOf(0)
        private set

    /** shouldInterceptRequest 钩子：记录 http/https 子资源。 */
    fun record(urlRaw: String) {
        if (!urlRaw.startsWith("http://", ignoreCase = true) &&
            !urlRaw.startsWith("https://", ignoreCase = true)
        ) return
        lock.withLock {
            if (seen.containsKey(urlRaw)) return
            val res = parse(urlRaw) ?: return
            if (seen.size >= MAX) {
                // 环形截断：丢弃最早 1/4（批量清理比逐条省事）
                // 注意：迭代器不能命名为 it —— repeat 的 lambda 隐式参数也叫 it，
                // 会遮蔽外层局部变量导致 hasNext/next 解析失败
                val iter = seen.entries.iterator()
                repeat(MAX / 4) { if (iter.hasNext()) { iter.next(); iter.remove() } }
            }
            seen[urlRaw] = res
            count = seen.size
            version++
        }
    }

    /** swf 拦截路径回写真实大小（interceptSwf 下载完成后调用）。 */
    fun updateSize(url: String, bytes: Long) {
        lock.withLock {
            seen[url]?.let { it.size = bytes; version++ }
        }
    }

    /** 当前页主导航（pageUrl 跟随；不清历史 —— 跨页收集是特性不是 bug）。 */
    fun onPageStarted(url: String?) {
        if (url != null) pageUrl = url
    }

    /** 当前页 host（面板标题与排序）。 */
    fun pageHost(): String = hostOf(pageUrl)

    /** 快照（排序：当前页 host 优先 → host 字典序 → 目录 → 文件名）。 */
    fun snapshot(): List<Res> = lock.withLock { seen.values.toList() }.sortedWith(
        compareBy(
            { it.host != hostOf(pageUrl) },           // 当前页 host 置顶
            { it.host }, { it.dir }, { it.name }
        )
    )

    fun clear() {
        lock.withLock {
            seen.clear()
            count = 0
            version++
        }
    }

    // ---- 解析 ----

    private fun hostOf(url: String): String =
        runCatching { URI(url).host ?: "" }.getOrDefault("")

    /** URL → 名称 / 目录 / 扩展名 / 大类。 */
    private fun parse(url: String): Res? = runCatching {
        val u = URI(url)
        val host = u.host ?: return@runCatching null
        val rawPath = u.path ?: "/"
        val segs = rawPath.split('/').filter { it.isNotBlank() }
        // 文件名：末段（无则用查询前整段或 index）
        val file = segs.lastOrNull() ?: "index"
        val dir = if (segs.isEmpty()) "" else segs.subList(0, segs.size - 1)
            .joinToString("/")
        val ext = file.substringAfterLast('.', "").lowercase().take(8)
        val kind = classify(url, ext, host)
        Res(
            url = url, host = host, dir = dir,
            name = decode(file), ext = ext, kind = kind,
            size = -1L, ts = System.currentTimeMillis()
        )
    }.getOrNull()

    private fun decode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s).take(100)

    /** 大类判定：扩展名优先 → 4399 等 Flash 站 URL 模式 → 其他。 */
    private fun classify(url: String, ext: String, host: String): Kind = when (ext) {
        "swf" -> Kind.SWF
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif" -> Kind.IMAGE
        "mp4", "m3u8", "ts", "flv", "webm", "mov", "mkv", "avi" -> Kind.VIDEO
        "mp3", "m4a", "aac", "ogg", "wav", "flac", "mid", "wma" -> Kind.AUDIO
        "js", "mjs", "css", "html", "htm", "json", "xml", "vue" -> Kind.SCRIPT
        else -> when {
            // 与 BrowserEngine.isSwfRequest 同源的站点模式（无扩展名 SWF）
            url.contains(".swf?", ignoreCase = true) -> Kind.SWF
            host.contains("4399.com") &&
                (url.contains("/dw-") || url.contains("flash_tm3") || url.contains("flash20")) -> Kind.SWF
            else -> Kind.OTHER
        }
    }
}

// ===========================================================================
// 嗅探面板显示总线（悬浮球菜单 → BrowserShell 顶层面板）
// ===========================================================================

/** 悬浮球「资源嗅探」入口 → [ResourceSnifferPanel] 展示。 */
object ResourceSnifferPanelBus {
    var visible by mutableStateOf(false)
        private set

    fun open() { visible = true }
    fun close() { visible = false }
}
