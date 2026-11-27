package com.linbox.apps.browser

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

internal class FlashResources(
    private val context: Context,
    private val diagnostics: FlashDiagnostics? = null
) {
    @Volatile var pageUrl: String = ""
    @Volatile private var has4399Flash = false
    private val movieDirectories = ConcurrentHashMap.newKeySet<String>()

    fun onNavigation(url: String) {
        pageUrl = url
        has4399Flash = activates4399FlashNavigation(url)
        movieDirectories.clear()
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.host == "linbox.local" && uri.path == "/flash-bootstrap.js") {
            return WebResourceResponse("text/javascript", "UTF-8", context.assets.open("flash-bootstrap.js")).apply {
                responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache")
            }
        }
        if (isSwf(uri)) {
            if (activates4399Proxy(uri.host, null)) has4399Flash = true
            if (request.isForMainFrame) return playerDocument(uri.toString())
        }
        if (request.isForMainFrame) return null
        LocalFlashStore.serve(context, uri)?.let { return it }
        if (uri.scheme !in setOf("http", "https") || uri.host == "linbox.local") return null
        val url = uri.toString()
        if (isSwf(uri)) {
            movieDirectories.add(directory(url))
            val refererHost = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }
                ?.value?.let { Uri.parse(it).host }
            if (activates4399Proxy(uri.host, refererHost)) has4399Flash = true
        }
        val path = uri.path.orEmpty().lowercase()
        val extension = path.substringAfterLast('.', "")
        val knownAsset = directory(url) in movieDirectories || movieDirectories.any { url.startsWith(it) }
        val flashAsset = isSwf(uri) ||
            shouldProxy4399Subresource(uri.host, has4399Flash) ||
            knownAsset && (extension.isEmpty() || extension in setOf(
                "xml", "json", "txt", "bin", "dat", "data", "pack", "pck", "bytes", "ini", "cfg",
                "zip", "swz", "atf", "png", "jpg", "jpeg", "gif", "mp3", "wav", "ogg", "aac",
                "m4a", "flv", "f4v", "mp4", "webm", "wasm", "ttf", "otf", "woff", "woff2"
            ))
        if (!flashAsset) return null
        if (request.method == "OPTIONS") {
            val requestedMethod = request.requestHeaders.entries.firstOrNull {
                it.key.equals("Access-Control-Request-Method", true)
            }?.value?.uppercase()
            if (requestedMethod != null && requestedMethod !in setOf("GET", "HEAD")) return null
            val requestedHeaders = request.requestHeaders.entries.firstOrNull {
                it.key.equals("Access-Control-Request-Headers", true)
            }?.value.orEmpty().split(',').map(String::trim).filter { it.matches(Regex("[A-Za-z0-9-]+")) }
            diagnostics?.recordResource(uri, status = 204, detail = "预检通过", method = "OPTIONS")
            return WebResourceResponse(
                "text/plain", "UTF-8", 204, "No Content", cors(request, requestedHeaders),
                ByteArrayInputStream(ByteArray(0))
            )
        }
        // WebView does not expose POST bodies; those requests must retain Chromium's network path.
        if (request.method !in setOf("GET", "HEAD")) return null
        val documentUrl = request.requestHeaders.entries.firstOrNull { it.key.equals("Origin", true) }?.value
            ?: request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
            ?: pageUrl
        if (origin(url) == origin(documentUrl)) return null
        return fetch(request)
    }

    private fun cors(request: WebResourceRequest, requestedHeaders: List<String> = emptyList()): Map<String, String> {
        val origin = request.requestHeaders.entries.firstOrNull { it.key.equals("Origin", true) }?.value
        return buildMap {
            put("Access-Control-Allow-Origin", origin?.takeIf { it != "null" } ?: "*")
            if (origin != null && origin != "null") put("Access-Control-Allow-Credentials", "true")
            put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            if (requestedHeaders.isNotEmpty()) put("Access-Control-Allow-Headers", requestedHeaders.distinct().joinToString(", "))
            put("Vary", "Origin")
        }
    }

    private fun errorResponse(request: WebResourceRequest, status: Int, message: String) =
        WebResourceResponse(
            "text/plain", "UTF-8", status, message, cors(request),
            ByteArrayInputStream(message.toByteArray(Charsets.UTF_8))
        )

    private fun fetch(request: WebResourceRequest): WebResourceResponse? {
        var current = request.url.toString()
        val requestOrigin = request.requestHeaders.entries.firstOrNull { it.key.equals("Origin", true) }?.value
        val requestReferer = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val documentUrl = requestOrigin?.takeIf { Uri.parse(it).host != null && it != "null" }
            ?: requestReferer?.takeIf { Uri.parse(it).host != null }
            ?: pageUrl
        var connection: HttpURLConnection? = null
        try {
            repeat(6) {
                val currentUri = Uri.parse(current)
                val documentUri = Uri.parse(documentUrl)
                val sameOrigin = origin(current) == origin(documentUrl)
                val refererHost = requestReferer?.let { Uri.parse(it).host }
                val game4399Host = when {
                    is4399Host(refererHost) -> refererHost
                    is4399Host(documentUri.host) -> documentUri.host
                    is4399Host(Uri.parse(pageUrl).host) -> Uri.parse(pageUrl).host
                    else -> null
                }
                val same4399Game = has4399Flash && is4399Host(currentUri.host) && game4399Host != null
                if (!sameOrigin && !isPublicHost(currentUri.host)) {
                    diagnostics?.recordResource(request.url, status = 403, detail = "网络策略", method = request.method)
                    return errorResponse(request, 403, "Blocked non-public Flash resource host")
                }
                val conn = URL(current).openConnection() as HttpURLConnection
                connection = conn
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = false
                conn.requestMethod = request.method
                request.requestHeaders.forEach { (name, value) ->
                    if (name.lowercase() !in setOf(
                        "host", "connection", "cookie", "authorization", "origin", "referer", "accept-encoding",
                        "content-length", "if-none-match", "if-modified-since"
                    )) conn.setRequestProperty(name, value)
                }
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (same4399Game && requestOrigin != null && requestOrigin != "null" &&
                    is4399Host(Uri.parse(requestOrigin).host)
                ) conn.setRequestProperty("Origin", requestOrigin)
                if (sameOrigin || same4399Game) {
                    CookieManager.getInstance().getCookie(current)?.takeIf { it.isNotBlank() }?.let {
                        conn.setRequestProperty("Cookie", it)
                    }
                    if (origin(current) == origin(request.url.toString())) {
                        request.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", true) }?.let {
                            conn.setRequestProperty(it.key, it.value)
                        }
                    }
                }
                val referer = requestReferer?.let {
                    val source = Uri.parse(it)
                    if (origin(current) != origin(request.url.toString())) {
                        source.buildUpon().encodedPath("/").clearQuery().fragment(null).build().toString()
                    } else source.buildUpon().fragment(null).build().toString()
                }
                if (referer != null && !(referer.startsWith("https:") && current.startsWith("http:"))) {
                    conn.setRequestProperty("Referer", referer)
                } else if (same4399Game && current.startsWith("https:")) {
                    conn.setRequestProperty("Referer", "https://$game4399Host/")
                }
                val status = conn.responseCode
                val isRangeRequest = request.requestHeaders.keys.any { it.equals("Range", true) }
                if (status >= 400 || isRangeRequest) {
                    diagnostics?.recordResource(
                        request.url, status = status, detail = if (isRangeRequest) "Range" else null, method = request.method
                    )
                }
                if (sameOrigin || same4399Game) conn.headerFields.entries.filter { it.key?.equals("Set-Cookie", true) == true }
                    .flatMap { it.value.orEmpty() }.forEach { CookieManager.getInstance().setCookie(current, it) }
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location") ?: error("Redirect without Location")
                    val next = URL(URL(current), location).toString()
                    if (!next.startsWith("http://") && !next.startsWith("https://")) error("Unsupported redirect")
                    if (current.startsWith("https://") && next.startsWith("http://")) {
                        diagnostics?.recordResource(request.url, status = 403, detail = "重定向", method = request.method)
                        return errorResponse(request, 403, "Blocked insecure Flash redirect")
                    }
                    conn.disconnect()
                    connection = null
                    current = next
                } else {
                    val headers = cors(request).toMutableMap()
                    for (name in listOf("Content-Length", "Content-Range", "Accept-Ranges", "Cache-Control", "ETag", "Last-Modified")) {
                        conn.getHeaderField(name)?.let { headers[name] = it }
                    }
                    val contentType = conn.contentType.orEmpty()
                    val mime = contentType.substringBefore(';').takeIf { it.isNotBlank() && it != "application/octet-stream" }
                        ?: mime(Uri.parse(current))
                    val encoding = Regex("charset=([^; ]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
                    val raw = if (request.method == "HEAD") ByteArrayInputStream(ByteArray(0))
                        else if (status >= 400) conn.errorStream ?: ByteArrayInputStream(ByteArray(0))
                        else conn.inputStream
                    if (status in 200..299 && conn.contentLengthLong >= 0) {
                        ResourceSniffer.updateSize(request.url.toString(), conn.contentLengthLong)
                    }
                    val stream = object : FilterInputStream(raw) {
                        override fun close() { try { super.close() } finally { conn.disconnect() } }
                    }
                    val response = WebResourceResponse(
                        mime, encoding, status, conn.responseMessage?.takeIf { it.isNotBlank() } ?: "Response", headers, stream
                    )
                    connection = null
                    return response
                }
            }
        } catch (error: Exception) {
            diagnostics?.recordResource(request.url, errorType = error.javaClass.simpleName, method = request.method)
            Log.w("FlashResources", "Resource load failed for ${request.url.host}${request.url.path}: ${error.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
        diagnostics?.recordResource(request.url, status = 502, errorType = "IOException", method = request.method)
        return errorResponse(request, 502, "Flash resource proxy could not reach the upstream host")
    }

    companion object {
        fun isSwf(uri: Uri): Boolean = uri.path?.endsWith(".swf", true) == true ||
            (uri.host == "linbox.local" && uri.path?.startsWith("/local-flash/") == true &&
                uri.getQueryParameter("linboxSwf") == "1")
        internal fun activates4399FlashNavigation(url: String): Boolean {
            val uri = Uri.parse(url)
            return isSwf(uri) && is4399Host(uri.host)
        }

        internal fun activates4399Proxy(movieHost: String?, pageHost: String?): Boolean =
            is4399Host(movieHost) || is4399Host(pageHost)

        internal fun shouldProxy4399Subresource(host: String?, has4399Flash: Boolean): Boolean =
            has4399Flash && is4399Host(host)

        internal fun is4399Host(host: String?): Boolean {
            val value = host?.lowercase() ?: return false
            return listOf("4399.com", "me4399.com", "my4399.com", "4399pk.com").any {
                value == it || value.endsWith(".$it")
            }
        }
        private fun origin(url: String): String = try {
            val uri = Uri.parse(url)
            "${uri.scheme}://${uri.host}:${if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80}"
        } catch (_: Exception) { "" }
        private fun isPublicHost(host: String?): Boolean {
            if (host.isNullOrBlank()) return false
            return InetAddress.getAllByName(host).all { address ->
                val firstByte = address.address[0].toInt() and 0xff
                !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress && !address.isMulticastAddress &&
                    !(address.address.size == 16 && (firstByte and 0xfe) == 0xfc)
            }
        }
        private fun directory(url: String): String {
            val uri = Uri.parse(url)
            val parent = uri.encodedPath.orEmpty().substringBeforeLast('/', "") + "/"
            return uri.buildUpon().encodedPath(parent).clearQuery().fragment(null).build().toString()
        }
        private fun mime(uri: Uri): String = when (val ext = uri.path.orEmpty().substringAfterLast('.', "").lowercase()) {
            "swf" -> "application/x-shockwave-flash"
            "xml" -> "application/xml"
            "json" -> "application/json"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        private fun playerDocument(url: String): WebResourceResponse {
            val options = JSONObject().put("url", url).put("base", directory(url)).put("allowScriptAccess", false).toString()
                .replace("<", "\\u003c")
            val html = """
                <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>SWF 播放器</title><style>html,body{margin:0;width:100%;height:100%;background:#000;color:#fff}#game{width:100%;height:100%}ruffle-player{width:100%;height:100%}#error{position:absolute;inset:10px;white-space:pre-wrap}</style>
                </head><body><div id="game"></div><div id="error" hidden></div><script src="https://linbox.local/flash-bootstrap.js"></script><script>
                window.__linboxLoadRuffle().then(function(source){
                    var player=source.createPlayer();document.getElementById('game').appendChild(player);
                    return player.ruffle().load($options);
                }).catch(function(error){var output=document.getElementById('error');output.hidden=false;output.textContent='SWF 加载失败：'+error.message;});
                </script></body></html>
            """.trimIndent()
            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        }
    }
}
