package com.linbox.apps.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.linbox.LinBoxApp
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// ===========================================================================
// v1.3.0：应用内下载引擎（用户："下载为何调用的是自带浏览器？用我的项目
// 浏览器直接下载啊，加入下载进度条在手机状态栏"）
// ===========================================================================

/**
 * 应用内 HTTP 下载引擎 + 状态栏进度通知，整体替换系统 DownloadManager：
 *
 * - **全程应用内**：HttpURLConnection 直连（Cookie/Referer/UA 转发，登录态
 *   可用），不再经 DownloadManager —— 完成通知由本应用发出、点击直接
 *   FileProvider 打开文件（chooser 选应用），不再跳系统下载器/自带浏览器；
 * - **状态栏进度条**：下载中通知 setProgress（大小已知显示百分比与
 *   "已下载/总大小"，未知显示不确定进度），可从通知「取消」或划掉即取消；
 * - **落盘**：下载到 cacheDir 临时文件 → 完成后 [BrowserDownloads.saveTempFileTo]
 *   三级回退（公共 Downloads 直写 → MediaStore.Downloads → 应用专属目录），
 *   支持 `Download/<subdir>/…` 子目录（资源嗅探批量下载保留站点目录结构）；
 * - **限并发**：最多 3 个并行下载，其余排队（批量嗅探下载不冲垮网络）。
 */
object AppDownloader {

    private const val CHANNEL_ID = "linbox_downloads"
    private const val ACTION_CANCEL = "com.linbox.browser.ACTION_CANCEL_DOWNLOAD"

    /** 通知 id（>0；0 会显示不出来）。 */
    private val nextId = AtomicInteger(1)

    /** 活跃下载表：notificationId → 取消句柄。 */
    private val active = ConcurrentHashMap<Int, () -> Unit>()

    /** 下载线程池：3 并发（批量嗅探限流）。 */
    private val pool: ExecutorService = Executors.newFixedThreadPool(3)

    /** 单个下载任务描述（[BrowserDownloads.startConfirmed] 与嗅探批量共用）。 */
    data class Job(
        val url: String,
        val filename: String,
        val mime: String?,
        val userAgent: String?,
        val cookie: String?,
        val referer: String?,
        /** 落盘子目录（空 = Download 根；嗅探批量 = Sniffer/<host>/<dir>）。 */
        val subdir: String = ""
    )

    /** 入队一个下载（队列满自动排队）。 */
    fun enqueue(job: Job) {
        pool.execute { run(job) }
    }

    /** 嗅探批量下载便捷入口：按站点相对目录组织落盘。 */
    fun enqueueAll(jobs: List<Job>) {
        jobs.forEach { enqueue(it) }
    }

    // ---- 引擎主体 ----

    private fun run(job: Job) {
        val ctx = LinBoxApp.get()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = nextId.incrementAndGet()
        ensureChannel(nm)

        val tmp = File(ctx.cacheDir, "appdl_${System.currentTimeMillis()}_${id}.tmp")
        // 取消标志（跨线程可见；@Volatile 不能用于局部变量 → AtomicBoolean）
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        active[id] = { cancelled.set(true) }

        // 下载中通知（先挂"取消"语义：点击通知取消 + 划掉取消）
        val cancelPi = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ACTION_CANCEL).setPackage(ctx.packageName).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        var lastNotify = 0L
        var connection: HttpURLConnection? = null
        fun notifyProgress(done: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (now - lastNotify < 400 && !(done >= total && total > 0)) return
            lastNotify = now
            val text = when {
                total > 0 -> "${BrowserDownloads.fmtBytes(done)} / ${BrowserDownloads.fmtBytes(total)}"
                done > 0 -> BrowserDownloads.fmtBytes(done)
                else -> "连接中…"
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(job.filename)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(
                    if (total > 0) 100 else 0,
                    if (total > 0) ((done * 100) / total).toInt() else 0,
                    total <= 0
                )
                .addAction(android.R.drawable.ic_delete, "取消", cancelPi)
                .setDeleteIntent(cancelPi)
                .build()
            runCatching { nm.notify(id, n) }
        }

        try {
            notifyProgress(0, -1)
            val conn = open(job.url, job).also { connection = it }
            val total = runCatching { conn.contentLengthLong }.getOrDefault(-1L)
            if (total == 0L) throw IllegalStateException("空文件")
            // disconnect 前缓存 content-type（落盘 MIME 兼备用）
            val responseMime = conn.contentType?.substringBefore(';')?.trim()
            val effectiveMime = responseMime?.takeUnless { it.equals("application/octet-stream", true) }
                ?: job.mime?.takeUnless { it.equals("application/octet-stream", true) }
                ?: BrowserDownloads.mimeForUrl(job.url)
            val effectiveFilename = BrowserDownloads.ensureExtension(
                job.filename,
                effectiveMime,
                conn.getHeaderField("Content-Disposition"),
                job.url
            )

            var done = 0L
            conn.inputStream.use { ins: InputStream ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) throw InterruptedException()
                        val r = ins.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        notifyProgress(done, total)
                    }
                    out.flush()
                }
            }
            conn.disconnect()
            if (cancelled.get() || tmp.length() <= 0L) throw InterruptedException()
            if (total > 0 && done < total) throw IllegalStateException("连接中断（$done/$total）")

            // 落盘（三级回退 + 子目录）→ 完成通知（点击打开）
            val saved = BrowserDownloads.saveTempFileTo(
                ctx, tmp, effectiveFilename, effectiveMime, job.subdir
            )
            if (saved == null) {
                tmp.delete()
                notifyFailed(nm, id, effectiveFilename, "存储不可用")
            } else {
                notifyDone(nm, id, effectiveFilename, saved)
            }
        } catch (_: InterruptedException) {
            tmp.delete()
            runCatching { nm.cancel(id) }
            BrowserDownloads.toast(ctx, "已取消：${job.filename}")
        } catch (e: Exception) {
            tmp.delete()
            val reason = (e.message ?: e.javaClass.simpleName).take(60)
            // http 站点网络失败 → 自动换 https 重试一次（部分 CDN 只支持 https）
            if (job.url.startsWith("http://") && e !is IllegalStateException) {
                connection?.disconnect()
                connection = null
                val alt = "https://" + job.url.substring(7)
                active.remove(id)
                runCatching { nm.cancel(id) }
                run(job.copy(url = alt))
                return
            }
            notifyFailed(nm, id, job.filename, reason)
        } finally {
            connection?.disconnect()
            active.remove(id)
        }
    }

    /** 打开连接（Cookie/Referer/UA 转发 + 跟随重定向 + 非 2xx 即抛错）。 */
    private fun open(url: String, job: Job): HttpURLConnection {
        val originalHost = URL(url).host
        var currentUrl = url
        repeat(8) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "identity")
            job.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            val cookie = if (URL(currentUrl).host.equals(originalHost, ignoreCase = true)) {
                job.cookie
            } else {
                CookieManager.getInstance().getCookie(currentUrl)
            }
            cookie?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
            job.referer?.let { conn.setRequestProperty("Referer", it) }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IllegalStateException("HTTP $code：缺少重定向地址")
                currentUrl = URL(URL(currentUrl), location).toExternalForm()
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code")
            }
            return conn
        }
        throw IllegalStateException("重定向次数过多")
    }

    // ---- 通知 ----

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "文件下载", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "LinBrowser 应用内下载进度"
                    setShowBadge(false)
                }
            )
        }
    }

    /** 落盘结果（直写/应用目录 = File；MediaStore = content Uri）。 */
    data class Saved(val file: File?, val uri: Uri?, val display: String)

    private fun notifyDone(nm: NotificationManager, id: Int, name: String, saved: Saved) {
        val ctx = LinBoxApp.get()
        // 点击打开：FileProvider（直写 File / 应用专属目录）或 MediaStore uri
        val openUri: Uri? = when {
            saved.uri != null -> saved.uri
            saved.file != null -> runCatching {
                FileProvider.getUriForFile(
                    ctx, ctx.packageName + ".fileprovider", saved.file
                )
            }.getOrNull()
            else -> null
        }
        val openPi = if (openUri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    openUri,
                    if (saved.file != null) {
                        BrowserDownloads.guessMime(name)
                    } else {
                        ctx.contentResolver.getType(openUri) ?: "application/octet-stream"
                    }
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                PendingIntent.getActivity(
                    ctx, id, Intent.createChooser(intent, "选择打开方式"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }.getOrNull()
        } else null

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText("${name} → ${saved.display}")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply { openPi?.let { setContentIntent(it) } }
            .build()
        runCatching { nm.notify(id, n) }
        BrowserDownloads.toast(ctx, "下载完成：$name")
    }

    private fun notifyFailed(nm: NotificationManager, id: Int, name: String, reason: String) {
        val ctx = LinBoxApp.get()
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("下载失败")
            .setContentText("$name（$reason）")
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
        BrowserDownloads.toast(ctx, "下载失败：$name（$reason）")
    }

    /** 取消指定通知 id 的活跃下载（[DownloadCancelReceiver] 回调）。 */
    fun cancelById(id: Int) {
        active[id]?.invoke()
    }
}

/** 通知「取消」action / 划掉通知 → 取消下载。 */
class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (id > 0) AppDownloader.cancelById(id)
    }
}
