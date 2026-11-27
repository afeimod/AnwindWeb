package com.linbox.apps.browser

import android.net.Uri
import java.util.Locale

internal class FlashDiagnostics {
    private val lock = Any()
    private val entries = arrayOfNulls<String>(40)
    private val uniqueEntries = HashSet<String>()
    private var start = 0
    private var size = 0

    fun clear() = synchronized(lock) {
        entries.fill(null)
        uniqueEntries.clear()
        start = 0
        size = 0
    }

    fun recordConsole(message: String, sourceId: String) {
        if (sourceId.length > 2048) return
        val source = Uri.parse(sourceId)
        val text = message.take(4096)
        if (text.startsWith("[LinBoxResource]")) {
            val fields = resourceLogPattern.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            val host = fields["host"]?.takeIf { hostPattern.matches(it) }?.lowercase(Locale.ROOT) ?: return
            val extension = fields["ext"]?.lowercase(Locale.ROOT)?.takeIf { it in knownExtensions }
                ?.let { "；文件类型=.$it" }.orEmpty()
            val method = fields["method"]?.uppercase(Locale.ROOT)
                ?.takeIf { it in setOf("GET", "HEAD", "OPTIONS", "POST", "PUT", "DELETE", "OTHER") }
                ?.let { "；方法=$it" }.orEmpty()
            val status = fields["status"]?.toIntOrNull()?.takeIf { it in 0..599 }
                ?.let { "；HTTP=$it" }.orEmpty()
            val cors = if (fields["cors"] == "possible") "；CORS可能" else ""
            append("脚本请求失败：host=$host$extension$method$status$cors")
            return
        }
        val isRuffleSource = source.scheme == "https" && source.host == "linbox.local" &&
            source.port == -1 && source.userInfo == null && source.path?.startsWith("/ruffle/") == true
        if (isRuffleSource) instancePattern.find(text)?.let { match ->
            val simd = match.groupValues[1].uppercase(Locale.ROOT)
            val renderer = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "webgl" -> "WebGL"
                "webgpu" -> "WebGPU"
                "wgpu-webgl" -> "WGPU WebGL"
                "canvas" -> "Canvas"
                "canvas2d" -> "Canvas2D"
                else -> "未识别"
            }
            append("引擎：renderer=$renderer；SIMD=$simd")
            return
        }
        if (source.scheme !in setOf("https", "http") || !failurePattern.containsMatchIn(text)) return
        for (category in Category.values()) {
            if (category.pattern.containsMatchIn(text)) append("日志分类：${category.label}")
        }
        if (corsPattern.containsMatchIn(text)) {
            val sourceHost = source.host?.takeIf { hostPattern.matches(it) }?.lowercase(Locale.ROOT)
            if (sourceHost != null) append("CORS日志来源：host=$sourceHost")
            val failedUrl = failedUrlPattern.find(text)?.groupValues?.getOrNull(1)
            val uri = failedUrl?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val host = uri?.host?.takeIf { hostPattern.matches(it) }?.lowercase(Locale.ROOT)
            val extension = uri?.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
                ?.takeIf { it in knownExtensions }
            if (host != null) append("CORS失败资源：host=$host${extension?.let { "；文件类型=.$it" }.orEmpty()}")
            val header = blockedHeaderPattern.find(text)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
                ?.takeIf { it in knownHeaders }
            if (header != null) append("CORS预检未放行请求头：$header")
            if (missingCorsResponsePattern.containsMatchIn(text)) append("CORS响应缺少：Access-Control-Allow-Origin")
        }
    }

    fun recordScriptRequest(rawUrl: String?, method: String, status: Int, cors: Boolean, phase: String) {
        val uri = rawUrl?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val host = uri?.host?.takeIf { hostPattern.matches(it) }?.lowercase(Locale.ROOT) ?: "未知主机"
        val extension = uri?.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
            ?.takeIf { it in knownExtensions }?.let { "；文件类型=.$it" }.orEmpty()
        val safeMethod = method.uppercase(Locale.ROOT).takeIf { it in setOf("GET", "HEAD", "OPTIONS", "POST", "PUT", "DELETE") }
            ?.let { "；方法=$it" }.orEmpty()
        val safeStatus = if (status > 0) "；HTTP=$status" else ""
        val safePhase = when (phase) { "preflight" -> "；阶段=预检"; "redirect" -> "；阶段=重定向"; else -> "" }
        append("脚本请求失败：host=$host$extension$safeMethod$safeStatus；CORS=$cors$safePhase")
    }

    fun recordResource(
        uri: Uri,
        status: Int? = null,
        errorType: String? = null,
        detail: String? = null,
        method: String? = null,
        networkCode: Int? = null
    ) {
        if ((status == null || status < 400) && errorType == null && detail == null) return
        val host = uri.host?.takeIf { it.length <= 253 && hostPattern.matches(it) }
            ?.lowercase(Locale.ROOT) ?: "未知主机"
        val http = status?.let { "；HTTP=$it" }.orEmpty()
        val error = errorType?.let {
            val name = if (it.length <= 256 && exceptionPattern.matches(it)) {
                it.substringAfterLast('.').takeIf { simpleName -> simpleName in exceptionNames }
            } else null
            "；异常=${name ?: "其他异常"}"
        }.orEmpty()
        val safeDetail = detail?.takeIf { it in setOf("预检失败", "预检通过", "Range", "重定向", "网络策略") }?.let { "；$it" }.orEmpty()
        val safeMethod = method?.uppercase(Locale.ROOT)?.takeIf { it in setOf("GET", "HEAD", "OPTIONS", "POST", "PUT", "DELETE") }
            ?.let { "；方法=$it" }.orEmpty()
        val safeNetworkCode = networkCode?.takeIf { it in -30..-1 }?.let { "；WebView码=$it" }.orEmpty()
        val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
            ?.takeIf { it in knownExtensions }?.let { "；文件类型=.$it" }.orEmpty()
        append("资源：host=$host$extension$safeMethod$http$safeNetworkCode$error$safeDetail")
    }

    fun snapshot(): String = synchronized(lock) {
        if (size == 0) {
            "暂无 Flash 诊断信息，请等待 SWF 加载后再查看。"
        } else {
            buildString {
                append("Flash 黑屏诊断（仅保留最近 40 条去重信息）\n")
                append("以下为引擎报告和错误线索，不代表 GPU 是否开启。\n")
                repeat(size) { index ->
                    append(entries[(start + index) % entries.size])
                    if (index < size - 1) append('\n')
                }
            }
        }
    }

    private fun append(entry: String) = synchronized(lock) {
        if (!uniqueEntries.add(entry)) return@synchronized
        if (size == entries.size) {
            uniqueEntries.remove(entries[start])
            entries[start] = entry
            start = (start + 1) % entries.size
        } else {
            entries[(start + size) % entries.size] = entry
            size++
        }
    }

    private enum class Category(val label: String, expression: String) {
        SOCKET("Socket", "\\b(?:socket|xmlsocket)\\b"),
        STAGE3D("Stage3D", "\\bstage3d\\b"),
        CONTEXT3D("Context3D", "\\bcontext3d\\b"),
        AVM2("AVM2", "\\bavm2\\b"),
        UNIMPLEMENTED_API("未实现API", "unimplemented|not implemented|unsupported.*api|未实现|不支持.*API"),
        FETCH("fetch", "\\bfetch\\b|failed to load|networkerror"),
        CORS("CORS", "\\bcors\\b|cross[- ]origin"),
        WASM("WASM", "\\bwasm\\b|webassembly"),
        PANIC("panic", "\\bpanic(?:ked)?\\b");

        val pattern = Regex(expression, RegexOption.IGNORE_CASE)
    }

    private companion object {
        val instancePattern = Regex(
            "New Ruffle instance created \\(Version: [^|\\r\\n]{1,80}\\s*\\| WebAssembly extensions: (ON|OFF) \\| Used renderer: ([A-Za-z0-9-]{1,24})\\)",
            RegexOption.IGNORE_CASE
        )
        val failurePattern = Regex(
            "\\b(?:warn(?:ing)?|error|exception|failed|failure|panic(?:ked)?|unimplemented|unsupported)\\b|not implemented|未实现|错误|警告|失败|不支持",
            RegexOption.IGNORE_CASE
        )
        val hostPattern = Regex("[A-Za-z0-9.\\-:\\[\\]]+")
        val exceptionPattern = Regex("(?:[A-Za-z_][A-Za-z0-9_]*\\.)*[A-Za-z_][A-Za-z0-9_]*")
        val corsPattern = Regex("CORS|cross[- ]origin|blocked by access control", RegexOption.IGNORE_CASE)
        val failedUrlPattern = Regex("https?://[^\\s'\\\"<>]+", RegexOption.IGNORE_CASE)
        val blockedHeaderPattern = Regex("header(?:s)?[^\\n]{0,100}?[\\\"']([A-Za-z][A-Za-z0-9-]{0,63})[\\\"'][^\\n]{0,100}(?:not allowed|not included)", RegexOption.IGNORE_CASE)
        val missingCorsResponsePattern = Regex("No ['\\\"]Access-Control-Allow-Origin['\\\"] header is present", RegexOption.IGNORE_CASE)
        val resourceLogPattern = Regex("\\b(host|ext|method|status|cors)=([A-Za-z0-9.-]{1,253})")
        val knownExtensions = setOf(
            "swf", "xml", "json", "txt", "bin", "dat", "data", "pack", "pck", "bytes", "zip", "swz", "atf",
            "png", "jpg", "jpeg", "gif", "mp3", "wav", "ogg", "aac", "m4a", "flv", "f4v", "mp4", "webm",
            "wasm", "ttf", "otf", "woff", "woff2", "js", "m3u8", "ts", "flac", "ogg"
        )
        val knownHeaders = setOf("range", "content-type", "authorization", "x-requested-with", "accept")
        val exceptionNames = setOf(
            "IOException", "FileNotFoundException", "EOFException", "InterruptedIOException",
            "SocketException", "SocketTimeoutException", "UnknownHostException", "ConnectException",
            "NoRouteToHostException", "ProtocolException", "SSLException", "SSLHandshakeException",
            "SSLPeerUnverifiedException", "SecurityException", "IllegalArgumentException",
            "IllegalStateException", "RuntimeException", "Exception"
        )
    }
}
