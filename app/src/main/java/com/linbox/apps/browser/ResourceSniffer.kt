package com.linbox.apps.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URI
import kotlin.concurrent.withLock

// ===========================================================================
// v1.3.0：资源嗅探器（用户："嗅探 swf 以及相关文件和文件夹识别后选择性
// 下载，以及嗅探网页后台下载的资源文件以及相关文件夹"）
//
// v1.3.1（用户反馈三连修）：
// ① 换站自动清空 —— 旧版"跨页收集是特性"导致换到别的网站后仍显示上个
//    站点的嗅探结果；现按【站点】（host 末两段标签，eTLD+1 近似）判定，
//    www.4399.com ↔ sda.4399.com 同站保留，4399.com → baidu.com 全清；
// ② ROM / 压缩包分类 —— .nes/.sfc/.gba/.nds/.n64/.iso/.bin/.md 等主机
//    ROM 与 .zip/.7z/.rar/.apk 等压缩包单独归类（ROM 站的真实下载源）；
// ③ DOM 下载链接扫描 —— ROM/游戏站的下载入口多为 <a href="xx.zip"> 形式，
//    不点击就不会加载、永不经过 shouldInterceptRequest；新增
//    [recordLinks] 由 BrowserEngine 在 onPageFinished 注入脚本把页面
//    DOM 里的下载型链接（a/embed/object/source）回传并入面板。
// ===========================================================================

/**
 * 页面资源嗅探器：两条采集路径 ——
 * 1. 钩在 WebViewClient.shouldInterceptRequest 上被动采集页面加载的全部
 *    http/https 子资源（图片 / 脚本 / 样式 / 媒体 / XHR / swf）；
 * 2. onPageFinished 注入脚本扫描页面 DOM 的下载型链接（.swf/.zip/.nes
 *    等），[recordLinks] 入库（标记 fromLink，面板显示「链接」徽标）；
 * 供 [ResourceSnifferPanel] 展示与勾选批量下载。
 *
 * - **零额外流量**：只记录 URL（请求本身就发生），不预取；
 * - **大小**：URL 本身拿不到 Content-Length —— swf 因 Ruffle 拦截路径
 *   （interceptSwf 全量下载后供给）会回写真实大小，其余显示"—"，
 *   勾选下载后在下载通知里显示实际大小；
 * - **去重**：同一 URL 只记一次（LinkedHashMap 保留首次出现的顺序，
 *   上限 [MAX] 条环形截断，防长会话内存膨胀）；
 * - **线程**：record 在 WebView IO 线程调用，recordLinks 在主线程，
 *   snapshot 在主线程 —— 全部持锁（数据量小，无性能问题）。
 */
object ResourceSniffer {

    private const val MAX = 1500
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /** 资源大类（过滤 chips 与徽标渲染）。 */
    enum class Kind(val label: String) {
        SWF("SWF"), IMAGE("图片"), VIDEO("视频"), AUDIO("音频"),
        SCRIPT("脚本"), ROM("ROM"), ARCHIVE("压缩包"), OTHER("其他")
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
        val ts: Long,
        /** true = 来自页面 DOM 链接扫描（真实下载源，页面尚未加载它）。 */
        val fromLink: Boolean = false
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
                // 注意：迭代器不能命名为 it —— repeat 的 lambda 隐式参数
                // 也叫 it，会遮蔽外层局部变量导致 hasNext/next 解析失败
                val iter = seen.entries.iterator()
                repeat(MAX / 4) { if (iter.hasNext()) { iter.next(); iter.remove() } }
            }
            seen[urlRaw] = res
            count = seen.size
            version++
        }
    }

    /**
     * v1.3.1：DOM 下载链接批量入库（onPageFinished 注入脚本回传）。
     * ROM/游戏站的 .swf/.zip/.nes 等真实下载源以 <a> 链接形式存在，
     * 不点击不加载、不经 shouldInterceptRequest —— 只能扫 DOM。
     * 与 [record] 共用去重表；已在表中的 URL（已加载）不覆盖不降级。
     */
    fun recordLinks(urls: List<String>) {
        if (urls.isEmpty()) return
        lock.withLock {
            var changed = false
            for (u in urls) {
                if (!u.startsWith("http://", ignoreCase = true) &&
                    !u.startsWith("https://", ignoreCase = true)
                ) continue
                if (seen.containsKey(u)) continue
                val res = parse(u, fromLink = true) ?: continue
                if (seen.size >= MAX) {
                    val iter = seen.entries.iterator()
                    repeat(MAX / 4) { if (iter.hasNext()) { iter.next(); iter.remove() } }
                }
                seen[u] = res
                changed = true
            }
            if (changed) {
                count = seen.size
                version++
            }
        }
    }

    /** swf 拦截路径回写真实大小（interceptSwf 下载完成后调用）。 */
    fun updateSize(url: String, bytes: Long) {
        lock.withLock {
            seen[url]?.let { it.size = bytes; version++ }
        }
    }

    /**
     * 当前页主导航。
     * v1.3.1：换站（站点级 host 变化）自动清空嗅探结果 —— 旧版跨站保留
     * 是用户明确反馈的问题；同站跨页继续累积（一个站的图片/脚本/swf
     * 分布在多个子域与页面上，属于"嗅探该站"的合理语义）。
     */
    fun onPageStarted(url: String?) {
        if (url == null) return
        val isHttp = url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        if (isHttp) {
            val oldSite = siteOf(hostOf(pageUrl))
            val newSite = siteOf(hostOf(url))
            if (oldSite.isNotBlank() && newSite.isNotBlank() && oldSite != newSite) {
                lock.withLock {
                    seen.clear()
                    count = 0
                    version++
                }
            }
            pageUrl = url
        } else if (hostOf(url).isNotBlank()) {
            // file:// 等本地页：跟随标题，不清空
            pageUrl = url
        }
        // about:blank / javascript: / data: 等无 host 特殊页：忽略
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

    /**
     * 站点标识（eTLD+1 近似：取 host 末两段标签；纯 IPv4 整串）。
     * www.4399.com 与 sda.4399.com → 同为 "4399.com"（不清空），
     * 4399.com 与 baidu.com → 不同（清空）。
     */
    private fun siteOf(host: String): String {
        if (host.isBlank() || !host.contains('.')) return host
        val parts = host.split('.')
        if (parts.last().all { it.isDigit() }) return host    // IPv4 直连
        return parts.takeLast(2).joinToString(".")
    }

    /** URL → 名称 / 目录 / 扩展名 / 大类。 */
    private fun parse(url: String, fromLink: Boolean = false): Res? = runCatching {
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
            size = -1L, ts = System.currentTimeMillis(),
            fromLink = fromLink
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
        // v1.3.1：主机 ROM（真实下载源 —— .nes/.sfc/.gba/.n64/.iso 等）
        "nes", "fds", "sfc", "smc", "gba", "gbc", "gb", "nds", "3ds", "n64",
        "z64", "v64", "md", "smd", "gen", "sms", "gg", "ws", "wsc", "ngp",
        "npc", "pce", "vb", "a26", "a78", "col", "int", "vec", "dsk",
        "cue", "iso", "img", "bin", "rom", "chd" -> Kind.ROM
        // v1.3.1：压缩包（ROM 站常以 .zip/.7z/.rar 分发游戏包）
        "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "apk", "xapk",
        "jar", "obb" -> Kind.ARCHIVE
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
