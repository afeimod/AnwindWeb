package com.linbox.apps.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object LocalFlashStore {

    data class Game(val name: String, val url: String)

    private data class Document(val uri: Uri, val mime: String)

    private const val HOST = "linbox.local"
    private const val PREFIX = "/local-flash/"
    private const val MAX_DEPTH = 32
    private const val SWF_MIME = "application/x-shockwave-flash"
    private val sessions = ConcurrentHashMap<String, Map<String, Document>>()
    private val headers = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers" to "*",
        "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff"
    )

    fun openFile(context: Context, uri: Uri): Game {
        require(uri.scheme == "content") { "请选择 SAF 文件" }
        persistReadPermission(context, uri)
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("无法读取文件名称")
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } ?: throw IOException("无法读取文件名称")
        require(isSafeSegment(name)) { "文件名称包含非法路径字符" }
        val mime = resolver.getType(uri)
        val magic = hasSwfMagic(context, uri)
        require(isSwf(name, mime) || magic) { "所选文件不是 SWF" }
        val token = UUID.randomUUID().toString()
        sessions[token] = mapOf(name to Document(uri, SWF_MIME))
        return Game(name, buildUrl(token, name))
    }

    fun openDirectory(context: Context, uri: Uri): List<Game> {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "请选择 SAF 目录" }
        persistReadPermission(context, uri)
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
        resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() ||
                cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) !=
                DocumentsContract.Document.MIME_TYPE_DIR
            ) throw IOException("所选文档不是目录")
        } ?: throw IOException("无法读取目录")

        val files = LinkedHashMap<String, Document>()
        val gamePaths = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        val paths = mutableSetOf<String>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        fun scan(documentId: String, parentPath: String, depth: Int) {
            if (depth > MAX_DEPTH) throw IOException("目录层级超过 $MAX_DEPTH 层")
            if (!visited.add(documentId)) throw IOException("目录存在循环或重复引用")
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn)
                    val mime = cursor.getString(mimeColumn)
                    if (childId.isNullOrEmpty() || !isSafeSegment(name)) {
                        throw IOException("目录包含非法文档名称或标识")
                    }
                    val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                    if (!paths.add(path)) throw IOException("目录包含重复路径：$path")
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        scan(childId, path, depth + 1)
                    } else {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, childId)
                        val swf = isSwf(name, mime) ||
                            (mime.isNullOrBlank() || mime == "application/octet-stream") && hasSwfMagic(context, fileUri)
                        files[path] = Document(fileUri, if (swf) SWF_MIME else resourceMime(name, mime))
                        if (swf) gamePaths.add(path)
                    }
                }
                if (cursor.extras.getString(DocumentsContract.EXTRA_ERROR) != null ||
                    cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)
                ) throw IOException("目录提供方未能返回完整内容")
            } ?: throw IOException("无法读取目录：$parentPath")
        }

        scan(rootId, "", 0)
        val token = UUID.randomUUID().toString()
        val games = gamePaths.map { path -> Game(path, buildUrl(token, path)) }
        // 完整扫描成功后才发布，WebView 线程不会看到部分映射。
        sessions[token] = files.toMap()
        return games
    }

    fun serve(context: Context, uri: Uri): WebResourceResponse? {
        if (!HOST.equals(uri.host, ignoreCase = true) || uri.path?.startsWith(PREFIX) != true) return null
        if (uri.scheme != "https" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) {
            return errorResponse(403, "Forbidden")
        }
        val segments = uri.encodedPath.orEmpty().removePrefix("/").split('/').map { Uri.decode(it) }
        if (segments.size < 3 || segments.first() != "local-flash" || segments.any { !isSafeSegment(it) }) {
            return errorResponse(403, "Forbidden")
        }
        val token = segments[1]
        val path = segments.drop(2).joinToString("/")
        val document = sessions[token]?.get(path) ?: return errorResponse(404, "Not Found")
        return try {
            val stream = context.contentResolver.openInputStream(document.uri)
                ?: return errorResponse(404, "Not Found")
            WebResourceResponse(document.mime, null, 200, "OK", headers, stream)
        } catch (_: IOException) {
            errorResponse(404, "Not Found")
        } catch (_: SecurityException) {
            errorResponse(404, "Not Found")
        }
    }

    private fun persistReadPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // 部分提供方仅支持当前会话授权，仍可使用现有读取权限。
        } catch (_: UnsupportedOperationException) {
            // 提供方不支持持久授权。
        }
    }

    private fun isSafeSegment(name: String?): Boolean =
        !name.isNullOrEmpty() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    private fun isSwf(name: String, mime: String?): Boolean =
        name.endsWith(".swf", ignoreCase = true) ||
            mime?.substringBefore(';')?.trim()?.equals(SWF_MIME, ignoreCase = true) == true ||
            mime?.substringBefore(';')?.trim()?.equals("application/vnd.adobe.flash.movie", ignoreCase = true) == true

    private fun hasSwfMagic(context: Context, uri: Uri): Boolean {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开文件")
        return stream.use { input ->
            val first = input.read()
            val second = input.read()
            val third = input.read()
            (first == 'F'.code || first == 'C'.code || first == 'Z'.code) &&
                second == 'W'.code && third == 'S'.code
        }
    }

    private fun resourceMime(name: String, mime: String?): String {
        val declared = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (!declared.isNullOrEmpty() && declared != "application/octet-stream") return declared
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun buildUrl(token: String, path: String): String {
        val builder = Uri.Builder().scheme("https").authority(HOST).appendPath("local-flash").appendPath(token)
        path.split('/').forEach { builder.appendPath(it) }
        return builder.appendQueryParameter("linboxSwf", "1").build().toString()
    }

    private fun errorResponse(status: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "UTF-8", status, reason, headers,
            ByteArrayInputStream(reason.toByteArray(Charsets.UTF_8))
        )
}
