package com.linbox.apps.browser

// v1.3.0：DownloadManager 已由应用内下载引擎 AppDownloader 整体替换
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.http.SslError
import android.webkit.WebView.HitTestResult
import org.json.JSONObject
import org.json.JSONTokener
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.linbox.LinBoxApp
import com.linbox.data.db.entity.HistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

/**
 * 浏览器内核引擎：WebView 的创建 / 配置 / 销毁，以及 WebViewClient、WebChromeClient。
 *
 * v2.8 重构要点：
 * 1. 移除 setLayerType(LAYER_TYPE_HARDWARE) —— WebView 默认已硬件加速，
 *    强制离屏硬件层在 MIUI / 部分 GPU 驱动上会导致"页面加载完成但首帧黑屏"。
 *    （录屏证实：DOM 已加载、标题已更新，但 WebView 永不绘制。）
 * 2. onCreateWindow 不再用"临时 WebView + 5 秒销毁"拦截 —— 那样：
 *    - 表单 POST / target=_blank 提交（百度"百度一下"）不回调
 *      shouldOverrideUrlLoading，导致点击完全没有反应；
 *    - 销毁临时 WebView 触发 cr_AwContents "Application attempted to call on
 *      a destroyed WebView"。
 *    现在：弹出的 WebView 直接作为新标签的【真实 WebView】通过 transport 交给
 *    Chromium，导航（GET/POST/JS 重定向）在真实标签中完成，全场景兼容。
 *
 * v2.14.2 WebView 优化（完整移植 afeimod/gamehtml 容器方案，移除 v2.14 自研
 * 灰屏像素检测 + 软件渲染切换 hack —— 灰屏是 WebView 自身渲染管线的时序问题，
 * gamehtml 的解法是强制重绘管线而非切换渲染器）：
 * 1. 强制重绘：onPageCommitVisible/onPageFinished invalidate + requestLayout +
 *    延迟 300ms 二次重绘 + document.body.offsetHeight 强制布局 —— 解决
 *    "页面加载完成（有声音/标题）但 Surface 不上屏"的灰屏问题。
 * 2. View Transitions API 补丁：WebView 对 startViewTransition 支持不完善，
 *    SPA 站点会出现回调不执行/页面卡住，polyfill 确保回调同步执行。
 * 4. PC 页面 viewport 自适配（无 viewport meta 的页面按 1200px 基准缩放）。
 * 5. 禁用 WebView 算法暗色（MIUI 强制深色不介入网页渲染）。
 *
 * v2.14.3 灰屏根因修复（弹窗时序 + 渲染层 + 渲染进程兜底，录屏/日志定位）：
 * 用户录屏：4399 首页正常，点击游戏（window.open 弹窗）瞬间整页浅灰，
 * 有声音无画面、无加载圈，持续 8s+ 无任何重绘。日志：弹窗 WebView
 * 以 0,0-0,0 创建（脱离视图树加载），被销毁时 1080x868 且 invalidated+dirty
 * ——帧在产、永不上屏，合成器/图层层面卡死。
 * 1. attach 后强制重绘梯子（本次核心）：onCreateWindow 的弹窗 WebView 经
 *    transport 交接后 Chromium 【立即在脱离视图树状态开始加载】，
 *    onPageCommitVisible/onPageFinished 全部在 attach 前触发完（invalidate
 *    对未 attach 视图无效），attach 后无人再触发重绘 → 永久灰屏。gamehtml
 *    是 Activity 根视图（setContentView 先 attach 后 loadUrl）且
 *    setSupportMultipleWindows(false) 无弹窗，根本不存在该时序；这里用
 *    addOnAttachStateChangeListener 在 attach 时刻补齐同样的重绘管线。
 *    切换标签/窗口最小化恢复的再 attach 同样被覆盖。
 * 2. 撤销 v2.14.2 照搬的 setLayerType(LAYER_TYPE_HARDWARE)：官方文档明确
 *    WebView 不支持强制硬件层（离屏缓冲由内核自管）；gamehtml 用在 Activity
 *    根视图上能活，但 LinBox 的 WebView 嵌在 Compose AndroidView 多窗口环境，
 *    v2.8 已用录屏证实强制硬件层导致"页面加载完成但永不绘制"。恢复默认
 *    LAYER_TYPE_NONE + 窗口级硬件加速（Manifest hardwareAccelerated=true）。
 * 3. onRenderProcessGone 兜底（WebView 自身问题）：不接管会被系统杀进程。
 *    渲染进程崩溃/OOM 后丢弃死亡 WebView，标签重置未加载态并 renderEpoch+1
 *    触发 key() 重组重建新 WebView 自动重载页面。
 *
 * v2.14.4 灰屏第二根因修复（网络层证据：ssl_client_socket_impl handshake
 * failed net_error -100 + miui_contents loadinfo script_count=13/
 * resource_count=0 + 音频进程活着）—— 页面主文档到达、JS 在跑、音频在播，
 * 但 CDN 上的引擎/子资源 TLS 被掐断（国内网络对 jsdelivr/unpkg 的典型干扰）：
 * 1. Ruffle 引擎全量本地化（本次核心）：5 个文件打包进 assets，经虚拟域
 *    https://linbox.local/ruffle/ 由 shouldInterceptRequest 供给，零 CDN 依赖。
 * 2. 弹窗 WebView 立即挂载（补齐 v2.14.3 梯子的源头缺口）：onCreateWindow
 *    在 transport 交接【前】就把弹窗 WebView 临时挂到 opener 的视图容器并
 *    手动 measure/layout 成 opener 尺寸 —— Chromium 交接后立即在真实 surface
 *    上开始加载（不再是 0,0-0,0 脱离视图树状态），首帧生产不再落空；Compose
 *    激活新标签时 factory 剥离临时父容器认领。AndroidViewHolder 只测量/布局
 *    自己的 typedView，第二个子 View 必须手动 layout。
 */
object BrowserEngine {

    /** 桌面版 Chrome UA（不含 Mobile/Android，让服务器返回 PC 版页面） */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 系统默认（移动版）UA —— 首次创建 WebView 时捕获，供手机模式恢复 */
    @Volatile
    private var defaultMobileUa: String? = null

    private const val TAG_DESTROYED = "linbox_webview_destroyed"

    fun desiredUa(uaMode: String, browserMode: String = "default"): String = when (browserMode) {
        "compatibility" -> IE_COMPAT_UA
        "speed" -> desktopUserAgent()
        else -> if (uaMode == "desktop") desktopUserAgent() else (defaultMobileUa ?: DESKTOP_UA)
    }

    private const val IE_COMPAT_UA =
        "Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko"

    private const val IE_COMPAT_SCRIPT = """
        (function(){
          try { Object.defineProperty(document,'documentMode',{get:function(){return 11;},configurable:true}); } catch(e) {}
          try { Object.defineProperty(document,'uniqueID',{get:function(){return '_ie_id_';},configurable:true}); } catch(e) {}
          try { if(!document.all) document.all=document.getElementsByTagName('*'); } catch(e) {}
        })();
    """

    private const val DESKTOP_MODE_SCRIPT = """
        (function(){
          var firstInstall=!window.__linboxDesktopMode;
          window.__linboxDesktopMode=true;
          function setProperty(target,name,value){
            try{Object.defineProperty(target,name,{get:function(){return value;},configurable:true});}catch(e){}
          }
          setProperty(navigator,'platform','Win32');
          setProperty(navigator,'maxTouchPoints',0);
          setProperty(screen,'width',1280);
          setProperty(screen,'availWidth',1280);
          function applyViewport(){
            try{
              var head=document.head||document.documentElement;
              if(!head)return;
              var meta=document.querySelector('meta[name="viewport"]');
              if(!meta){meta=document.createElement('meta');meta.name='viewport';head.appendChild(meta);}
              var parts=(meta.content||'').split(',').map(function(part){return part.trim();}).filter(function(part){return part&&!/^width\s*=/i.test(part);});
              parts.unshift('width=1280');
              var next=parts.join(', ');
              if(meta.content!==next)meta.content=next;
            }catch(e){}
          }
          applyViewport();
          if(firstInstall&&window.MutationObserver){
            var observer=new MutationObserver(function(){applyViewport();watchViewport();});
            function watchViewport(){
              observer.disconnect();
              if(document.head){
                observer.observe(document.head,{childList:true});
                var meta=document.querySelector('meta[name="viewport"]');
                if(meta)observer.observe(meta,{attributes:true,attributeFilter:['content','name']});
              }else if(document.documentElement){
                observer.observe(document.documentElement,{childList:true});
              }
            }
            watchViewport();
          }
        })();
    """

    private fun desktopUserAgent(): String {
        val chromeVersion = defaultMobileUa
            ?.let { Regex("Chrome/([0-9.]+)").find(it)?.groupValues?.getOrNull(1) }
            ?.takeIf { it.isNotBlank() }
            ?: DESKTOP_UA.substringAfter("Chrome/").substringBefore(' ')
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }

    private val flashSource: String by lazy {
        LinBoxApp.get().assets.open("flash-bootstrap.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    internal fun browserBootstrap(browserMode: String): String = composeBrowserBootstrap(browserMode, flashSource)

    internal fun composeBrowserBootstrap(browserMode: String, flashScript: String): String = when (browserMode) {
        "compatibility" -> IE_COMPAT_SCRIPT + "\n" + DESKTOP_MODE_SCRIPT + "\n" + flashScript
        "speed" -> DESKTOP_MODE_SCRIPT + "\n" + flashScript
        else -> flashScript
    }

    private fun installFlashScript(view: WebView, tab: BrowserTab, browserMode: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            tab.removeFlashScript?.invoke()
            val script = WebViewCompat.addDocumentStartJavaScript(view, browserBootstrap(browserMode), setOf("*"))
            tab.removeFlashScript = { script.remove() }
        }
    }

    fun applyBrowserMode(tab: BrowserTab, manager: TabManager) {
        val changed = tab.browserMode != manager.browserMode || tab.appliedUaMode != manager.uaMode
        if (!changed) return
        tab.webView?.let { view ->
            tab.removeFlashScript?.invoke()
            installFlashScript(view, tab, manager.browserMode)
            view.settings.userAgentString = desiredUa(manager.uaMode, manager.browserMode)
        }
        if (tab.hasLoadedOnce && !tab.url.startsWith("linbox://") && !tab.url.startsWith("about:")) {
            if (manager.activeTabId == tab.id) tab.webView?.reload()
            else tab.browserModeReloadPending = true
        }
        tab.browserMode = manager.browserMode
        tab.appliedUaMode = manager.uaMode
    }

    fun setPageActive(tab: BrowserTab, active: Boolean) {
        tab.pageActive = active
        tab.webView?.let { view ->
            if (active && tab.browserModeReloadPending) {
                tab.browserModeReloadPending = false
                view.reload()
            }
            view.evaluateJavascript(
                "window.__linboxFlashActive=$active;if(window.__linboxSetFlashActive)window.__linboxSetFlashActive($active);",
                null
            )
            if (active) view.onResume() else view.onPause()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                view.setRendererPriorityPolicy(
                    if (active) WebView.RENDERER_PRIORITY_IMPORTANT else WebView.RENDERER_PRIORITY_BOUND,
                    !active
                )
            }
        }
    }

    /**
     * 获取（必要时创建）标签专属 WebView。
     *
     * - 已有缓存：直接复用（切换标签回来时保留页面状态/滚动位置/视频进度），
     *   并静默同步 UA 设置。
     * - 没有缓存：完整配置并绑定 client。
     */
    fun ensureWebView(context: Context, tab: BrowserTab, manager: TabManager): WebView {
        val existing = tab.webView
        if (existing != null) {
            // v2.14.4：弹窗 WebView 在 onCreateWindow 时被临时挂在 opener 的
            // 视图容器里（保证 transport 交接后立即在真实 surface 上加载，
            // 不再以 0,0-0,0 脱离视图树状态加载导致首帧生产落空）。
            // AndroidView 认领前必须剥离旧父容器，否则 holder.addView 抛
            // "The specified child already has a parent"。
            (existing.parent as? ViewGroup)?.removeView(existing)
            applyUa(existing, manager.uaMode, manager.browserMode)
            return existing
        }
        val wv = ExposedWebView(context)
        if (defaultMobileUa == null) {
            defaultMobileUa = wv.settings.userAgentString
        }
        configure(wv, tab, manager)
        tab.webView = wv
        return wv
    }

    private fun applyUa(wv: WebView, uaMode: String, browserMode: String) {
        val desired = desiredUa(uaMode, browserMode)
        if (wv.settings.userAgentString != desired) {
            try {
                wv.settings.userAgentString = desired
            } catch (_: Exception) {
            }
        }
    }

    // v1.2.3：allowFileAccessFromFileURLs / allowUniversalAccessFromFileURLs
    // 已废弃但无等价替代（官方推荐 WebViewAssetLoader 迁移成本高）——
    // 本地 HTML（content:// SAF）与 Ruffle 本地资产供给依赖它们，保留并抑制告警
    @Suppress("DEPRECATION")
    private fun configure(wv: WebView, tab: BrowserTab, manager: TabManager) {
        val ctx = wv.context
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // 多窗口模式：window.open / target=_blank → onCreateWindow
            // （弹窗作为真实新标签处理，见 TabChromeClient.onCreateWindow）
            setSupportMultipleWindows(true)
            defaultTextEncodingName = "UTF-8"
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            setGeolocationEnabled(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            textZoom = 100
            userAgentString = desiredUa(manager.uaMode, manager.browserMode)
        }

        // v2.14.3：撤销 v2.14.2 照搬的 setLayerType(LAYER_TYPE_HARDWARE)。
        // Android 官方文档明确 WebView 不支持 LAYER_TYPE_HARDWARE（内部离屏
        // 缓冲由 Chromium 自管）；gamehtml 用在 Activity 根视图上能工作，但
        // LinBox 的 WebView 嵌在 Compose AndroidView 多窗口环境里，v2.8 已用
        // 录屏证实强制硬件层会导致“页面加载完成但永不绘制”。本次灰屏日志中
        // 被销毁的弹窗 WebView 连续 4s 处于 invalidated+dirty 状态——帧在产、
        // 永不上屏，正是离屏层合成失效的表征。默认 LAYER_TYPE_NONE + 窗口级
        // 硬件加速（Manifest hardwareAccelerated=true）才是 WebView 的正确配置。

        // v2.14.3（核心修复）：attach 后强制重绘梯子。
        // 弹窗 WebView 经 onCreateWindow transport 交接后，Chromium 立即在
        // 【脱离视图树】状态开始加载（日志佐证：创建时 0,0-0,0）——
        // onPageCommitVisible/onPageFinished 的强制重绘全部在 attach 前触发完，
        // 对未 attach 的视图 invalidate 无效；等下一帧 Compose 把它上屏后，
        // 再没有任何代码路径触发重绘 → 首帧永不上屏（灰屏、有声音无画面）。
        // gamehtml 是 Activity 根容器（setContentView 先 attach、后 loadUrl），
        // 页面事件都落在 attached 状态，所以它的重绘管线在那边有效；这里在
        // attach 时刻补齐同一套管线：立即 + 200ms + 600ms 三级梯子，兼容
        // 注入脚本（Ruffle 等）执行晚于首帧的情况。切换标签/窗口最小化恢复的
        // 再 attach 也一并覆盖。
        wv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post {
                    if (v.tag != TAG_DESTROYED) {
                        v.requestLayout()
                        v.invalidate()
                    }
                }
                v.postDelayed({
                    if (v.tag != TAG_DESTROYED) v.invalidate()
                }, 200)
                v.postDelayed({
                    if (v.tag != TAG_DESTROYED) v.invalidate()
                }, 600)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })

        // v2.14.2：禁用算法暗色 —— MIUI 强制深色会介入 WebView 渲染管线，
        // 配合 GPU 合成问题放大灰屏概率（灰屏日志出现 ForceDarkHelperStubImpl 初始化）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { wv.settings.isAlgorithmicDarkeningAllowed = false }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            runCatching { wv.settings.forceDark = WebSettings.FORCE_DARK_OFF }
        }

        // v2.19：渲染进程优先级拉满 —— 多窗口/多标签场景下 Chromium 可能
        // 因可见性判定降级渲染优先级导致前台滚动卡顿；deferred=false 保持
        // 常驻优先级（配合 largeHeap，多标签内存压力可控）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
        }

        // 启用 WebView 内部数据库 (WebStorage) 自动管理
        WebStorage.getInstance()
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)
        cookieManager.flush()

        // 文件下载（v6.10 重写，用户："修复浏览器无法下载文件等问题"）：
        // - 文件名解析加固：RFC 6266 filename*=UTF-8''…（中文站常用）/
        //   filename="…" / URL 末段 三级回退 + 非法字符清洗 + 缺扩展名按
        //   MIME 补全（旧版整体 URLDecoder 后 substringAfter，中文站
        //   filename* 场景一律落到无扩展名的 "linbox_download"）；
        // - Cookie/Referer 转发：DownloadManager 不共享 WebView Cookie，
        //   登录态下载 401/302 直接失败 —— 显式带上；
        // - blob:/data: URL 分流：DownloadManager 无法处理（现代站点下载
        //   按钮多为 blob:，旧版直接走 catch 报"下载失败"）—— blob 经
        //   页内 XHR fetch + __linboxDownload 分块桥落盘，data 直接解码。
        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            BrowserDownloads.handleDownload(
                wv, url, userAgent, contentDisposition, mimetype, contentLength
            )
        }

        wv.setOnLongClickListener { view ->
            val webView = view as WebView
            val hit = webView.hitTestResult
            val kind = when (hit.type) {
                HitTestResult.ANCHOR_TYPE, HitTestResult.SRC_ANCHOR_TYPE -> "link"
                HitTestResult.IMAGE_TYPE, HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> "image"
                else -> return@setOnLongClickListener false
            }
            val exposed = webView as? ExposedWebView
            val scale = webView.scale.takeIf { it > 0f } ?: 1f
            val x = ((exposed?.lastTouchX ?: 0f) / scale).toInt()
            val y = ((exposed?.lastTouchY ?: 0f) / scale).toInt()
            val fallbackUrl = JSONObject.quote(hit.extra ?: "")
            val script = """
                (function(){
                  var el=document.elementFromPoint($x,$y);
                  if(!el)return JSON.stringify({kind:'$kind',url:$fallbackUrl});
                  var link=el.closest&&el.closest('a[href]');
                  var image=el.closest&&el.closest('img');
                  var video=el.closest&&el.closest('video');
                  if(!video){
                    var videos=document.querySelectorAll('video');
                    for(var i=0;i<videos.length;i++){
                      var rect=videos[i].getBoundingClientRect();
                      if($x>=rect.left&&$x<=rect.right&&$y>=rect.top&&$y<=rect.bottom){video=videos[i];break;}
                    }
                  }
                  var object=el.closest&&el.closest('object,embed');
                  var data={kind:'$kind',url:$fallbackUrl,linkUrl:link?link.href:'',linkText:link?(link.innerText||link.textContent||'').trim():''};
                  function absolute(value){try{return value?new URL(value,document.baseURI).href:'';}catch(e){return '';}}
                  function mimeFrom(value){
                    var path=(value||'').split(/[?#]/)[0];
                    try{var u=new URL(value,document.baseURI);var name=u.searchParams.get('filename')||u.searchParams.get('file')||u.searchParams.get('name');if(name&&name.indexOf('.')>=0)path=name;}catch(e){}
                    var ext=path.split('.').pop().toLowerCase();
                    var types={jpg:'image/jpeg',jpeg:'image/jpeg',png:'image/png',gif:'image/gif',webp:'image/webp',avif:'image/avif',bmp:'image/bmp',svg:'image/svg+xml',mp4:'video/mp4',m4v:'video/mp4',webm:'video/webm',mov:'video/quicktime',m3u8:'application/vnd.apple.mpegurl'};
                    return types[ext]||'';
                  }
                  function largestSrcset(value){
                    if(!value)return '';
                    var best='',bestSize=-1;
                    value.split(',').forEach(function(entry){
                      var parts=entry.trim().split(/\s+/);if(!parts[0])return;
                      var match=(parts[1]||'1x').match(/^([0-9.]+)(w|x)$/i);
                      var size=match?parseFloat(match[1]):-1;
                      if(size>bestSize){bestSize=size;best=parts[0];}
                    });
                    return absolute(best);
                  }
                  if(image){
                    data.kind='image';
                    var original=image.getAttribute('data-original')||image.getAttribute('data-original-src')||image.getAttribute('data-src-original')||image.getAttribute('data-full')||image.getAttribute('data-zoom-image')||image.getAttribute('data-hi-res-src')||image.getAttribute('data-large')||image.getAttribute('data-big');
                    var picture=image.closest('picture');
                    var sources=picture?picture.querySelectorAll('source[srcset],source[data-srcset]'):[];
                    var sourceSet='';
                    for(var s=0;s<sources.length;s++)sourceSet=largestSrcset(sources[s].getAttribute('data-srcset')||sources[s].getAttribute('srcset'))||sourceSet;
                    var imageSet=largestSrcset(image.getAttribute('data-srcset')||image.getAttribute('srcset'));
                    var linkedImage=link&&link.href!==location.href?link.href:'';
                    var linkedPath=linkedImage.split(/[?#]/)[0].toLowerCase();
                    var linkedExt=(linkedPath.match(/\.(jpe?g|png|gif|webp|avif|bmp|svg)$/i)||[])[1];
                    var linkedName='';try{var linkedUrl=new URL(linkedImage,document.baseURI);linkedName=linkedUrl.searchParams.get('filename')||linkedUrl.searchParams.get('file')||linkedUrl.searchParams.get('name')||'';}catch(e){}
                    var linkedOriginal=!!linkedExt||/\.(jpe?g|png|gif|webp|avif|bmp|svg)$/i.test(linkedName);
                    data.linkUrl=linkedOriginal?absolute(linkedImage):'';
                    data.url=absolute(original)||(linkedOriginal?absolute(linkedImage):'')||sourceSet||imageSet||absolute(image.getAttribute('data-src'))||absolute(image.getAttribute('data-lazy-src'))||absolute(image.src)||absolute(image.currentSrc)||data.url;
                    data.mimeType=image.getAttribute('type')||image.getAttribute('data-mime-type')||mimeFrom(data.url);
                  }
                  else if(video){
                    data.kind='video';
                    var videoSources=video.querySelectorAll('source');
                    var bestVideo='',bestQuality=-1,bestMime='';
                    for(var v=0;v<videoSources.length;v++){
                      var candidate=videoSources[v];
                      var candidateUrl=candidate.getAttribute('data-original')||candidate.getAttribute('data-src')||candidate.src||'';
                      var quality=parseInt(candidate.getAttribute('data-quality')||candidate.getAttribute('data-res')||candidate.getAttribute('label')||'')||0;
                      var resolution=(candidateUrl.match(/(2160|1440|1080|720|480|360)p/i)||[])[1];
                      quality=Math.max(quality,parseInt(resolution||'0'));
                      if(candidateUrl&&quality>=bestQuality){bestQuality=quality;bestVideo=candidateUrl;bestMime=candidate.type||'';}
                    }
                    data.url=absolute(video.getAttribute('data-original')||video.getAttribute('data-src')||video.getAttribute('data-full')||video.getAttribute('src'))||absolute(bestVideo)||absolute(video.currentSrc)||data.url;
                    data.mimeType=bestMime||video.getAttribute('type')||mimeFrom(data.url);
                  }
                  else if(object){data.kind='flash';data.url=object.data||object.src||data.url;}
                  else if(link){data.kind='link';data.url=link.href;}
                  return JSON.stringify(data);
                })();
            """.trimIndent()
            webView.evaluateJavascript(script) { result ->
                runCatching {
                    val jsonText = JSONTokener(result ?: "null").nextValue() as? String ?: return@runCatching
                    val json = JSONObject(jsonText)
                    val target = BrowserLongPressTarget(
                        kind = json.optString("kind", kind),
                        url = json.optString("url", hit.extra ?: ""),
                        linkUrl = json.optString("linkUrl"),
                        linkText = json.optString("linkText"),
                        mimeType = json.optString("mimeType")
                    )
                    if (target.url.isNotBlank()) manager.ui?.onLongPress(target)
                }
            }
            true
        }

        // v6.10：blob: 下载落盘桥（页面 JS 经 XHR 读 blob 后分块回调）
        runCatching {
            wv.addJavascriptInterface(
                BrowserDownloads.bridge(wv), "__linboxDownload"
            )
            wv.addJavascriptInterface(MediaContextMenuBridge(manager), "__linboxMediaMenu")
        }

        val client = TabWebViewClient(ctx, tab, manager)
        wv.webViewClient = client
        wv.webChromeClient = TabChromeClient(ctx, tab, manager)

        // v2.16.3：鼠标视角（3D 视角旋转）JS 桥 —— 页面脚本经
        // window.__linboxLookBridge.pull() 每帧取走拖动增量并派发
        // 合成 mousemove(movementX/Y) 事件（详见 View3dController，
        // 参照 GameBox：模拟 Pointer Lock + __cameraRotate 派发）。
        // 桥只暴露只读 pull，无安全面。
        runCatching { wv.addJavascriptInterface(View3dController.bridge, "__linboxLookBridge") }
        tab.browserMode = manager.browserMode
        tab.appliedUaMode = manager.uaMode
        installFlashScript(wv, tab, manager.browserMode)
    }

    /**
     * 安全销毁 WebView（幂等）：
     * JS 暂停全部媒体 → onPause 挂起渲染 → stopLoading 中止加载 →
     * 脱离视图树 → destroy 释放原生资源。
     *
     * v2.14.10：destroyWebView 曾在部分设备上"关了还在响" —— 一方面调用方
     * 提前置空 tab.webView 导致销毁链断掉（见 TabManager 修复），另一方面
     * 部分 WebView 版本 onPause() 不会暂停 HTML5 媒体、destroy() 异步生效
     * 期间音频继续。现在销毁前先注入 JS 主动 pause 页面上所有
     * audio/video 元素并取消语音合成，保证立刻静音。
     */
    fun destroyWebView(wv: WebView) {
        if (wv.tag == TAG_DESTROYED) return
        try {
            wv.tag = TAG_DESTROYED
        } catch (_: Exception) {
        }
        // 1) 立即静音：JS 主动暂停所有媒体（部分版本 onPause 不暂停媒体）
        try {
            wv.evaluateJavascript(
                "(function(){try{var m=document.querySelectorAll('audio,video');" +
                    "for(var i=0;i<m.length;i++){try{m[i].pause();m[i].muted=true;}catch(e){}}" +
                    "if(window.speechSynthesis){try{window.speechSynthesis.cancel();}catch(e){}}" +
                    "try{if(document.body&&document.body.pause)document.body.pause();}catch(e){}}catch(e){}})()",
                null
            )
        } catch (_: Exception) {
        }
        // 2) 挂起 WebView 的额外处理（JS 定时器/布局/媒体管线）
        try {
            wv.onPause()
        } catch (_: Exception) {
        }
        try {
            wv.stopLoading()
        } catch (_: Exception) {
        }
        // 3) 脱离视图树并销毁原生资源
        (wv.parent as? ViewGroup)?.removeView(wv)
        try {
            wv.removeAllViews()
        } catch (_: Exception) {
        }
        try {
            wv.destroy()
        } catch (_: Exception) {
            // 某些设备上 destroy() 可能抛 IllegalStateException，吞掉即可
        }
    }

    /** 记录历史（Room）。所有主导航统一在 onPageStarted 记录，天然去重于 update 块。 */
    internal fun recordHistory(url: String?) {
        if (url.isNullOrEmpty() || url == "about:blank" || url.startsWith("data:")) return
        engineScope.launch {
            try {
                LinBoxApp.get().database.historyDao()
                    .insert(HistoryEntity(title = url, url = url))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * v2.14.1：是否为内网/本地地址（IP / localhost / *.local / 带端口）。
     * 这类地址（路由器、NAS、本地开发服务）大多只有 http 或自签证书，
     * 是唯一允许 https→http 自动回退的范围；公网域名绝不回退（安全底线）。
     */
    internal fun isLanishUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val bare = url.removePrefix("https://").substringBefore("/").substringBefore("?")
        val host = bare.substringBefore(":")
        val hasPort = bare.contains(":")
        return Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$").matches(host) ||
            host == "localhost" || host.endsWith(".local") || hasPort
    }

    // ====================================================================
    // WebViewClient：每个标签一次性创建，通过 manager 路由 UI 回调
    // ====================================================================
    private class TabWebViewClient(
        private val ctx: Context,
        private val tab: BrowserTab,
        private val manager: TabManager
    ) : WebViewClient() {

        private val flashResources = FlashResources(ctx, tab.flashDiagnostics)
        private val flashBootstrapScript: String get() = browserBootstrap(manager.browserMode)

        private fun isActive() = manager.activeTabId == tab.id

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            if (isActive()) manager.ui?.onUrlSync(url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val newUrl = request.url.toString()
            // 处理特殊 scheme：intent://, weixin://, alipays://, mailto: 等
            val scheme = request.url.scheme ?: "http"
            if (scheme !in setOf("http", "https", "content", "file", "about", "javascript", "data")) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, request.url)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(ctx, "未安装可打开 $scheme 应用的程序", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            // 在当前 WebView 内继续加载：登记 lastRequestedUrl 防 update 块重复加载
            tab.lastRequestedUrl = newUrl
            tab.url = newUrl
            if (isActive()) {
                manager.ui?.onUrlChanged(newUrl)
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url != null) {
                flashResources.onNavigation(url)
                tab.flashDiagnostics.clear()
                // v1.3.0：资源嗅探器跟随主导航（面板标题/目录树置顶当前站）
                ResourceSniffer.onPageStarted(url)
                // v2.14.1：同一 https 地址被重新导航（重试/地址栏回车/书签重开）时，
                // 重置自动回退标记，允许再次尝试 https 而非直接报错
                if (url == tab.httpsFallbackUrl) tab.httpsFallbackUrl = null
                // 登记 lastRequestedUrl：页面内部导航（含弹窗首跳）后
                // update 块不会重复 loadUrl 把页面拉回旧地址
                tab.lastRequestedUrl = url
                if (url != tab.url) {
                    tab.url = url
                    if (isActive()) {
                        manager.ui?.onUrlSync(url)
                    }
                }
                // 后退/前进/刷新不写入历史
                val suppress = tab.suppressNextHistory
                tab.suppressNextHistory = false
                if (!suppress) {
                    recordHistory(url)
                }
            }
            view?.let {
                tab.canGoBack = it.canGoBack()
                tab.canGoForward = it.canGoForward()
            }
            if (isActive()) {
                manager.ui?.onError(null)
            }

            view?.evaluateJavascript(
                "window.__linboxFlashActive=${tab.pageActive};" + flashBootstrapScript +
                    ";window.__linboxSetFlashActive(${tab.pageActive});", null
            )

            if (url != null && (url.startsWith("http") || url.startsWith("file:"))) {
                if (manager.browserMode == "compatibility") {
                    view?.evaluateJavascript(IE_COMPAT_SCRIPT + DESKTOP_MODE_SCRIPT, null)
                } else if (manager.browserMode == "speed" || manager.uaMode == "desktop") {
                    view?.evaluateJavascript(DESKTOP_MODE_SCRIPT, null)
                }
                // 1) View Transitions API 补丁（所有页面通用，防 SPA 跳转卡死）
                view?.evaluateJavascript(VIEW_TRANSITION_PATCH_SCRIPT, null)
                // 2) PC 页面 viewport 自适配（页面自带 viewport 则不动）。
                //    v2.14.6：恢复 v2.14.4 原版默认排版（用户要求“默认缩放
                //    还用之前的”）。缩小到 30% 的能力改由 ZoomPinchLayout
                //    在【捏合缩小期间】动态改写 viewport meta 实现
                //    （v2.14.9：width=原宽/z + 钳制 scale，页面始终精确
                //    铺满窗口宽）；默认态本脚本不改画布宽、不改默认缩放
                view?.evaluateJavascript(VIEWPORT_FIT_SCRIPT, null)
            }

            // v2.14.7：导航重置缩小域缩放（30%~100%）—— 每次新页面默认回到
            // 100% 排版（v2.14.5 教训：默认视觉必须与旧版一致）。新文档天然
            // 无注入；同文档锚点导航（onPageStarted 亦触发）由 RESET_SCRIPT
            // 按快照还原原 viewport meta（v2.14.9：缩小域改为动态改写
            // meta width=原宽/z + 钳制 scale，页面始终精确铺满窗口宽，
            // 无 letterbox）。View 变换方案（v2.14.6）已被实测
            // 否决：内容不随变换缩放、仅被缩小后的边界裁切
            if (tab.viewZoom != 1f) {
                tab.viewZoom = 1f
                view?.evaluateJavascript(ZoomPinchLayout.RESET_SCRIPT, null)
            }
        }

        /**
         * v2.14.2（gamehtml）：首次可见帧强制重绘。
         * 部分设备上首帧提交后合成器未拉起新帧，invalidate 确保当前帧上屏。
         * v2.14.5 曾在此注入 viewport 缩放脚本（宽画布方案），v2.14.6 撤销
         * —— 该方案破坏无 viewport PC 页的默认排版；v2.14.6 的 View 变换
         * 缩放亦被 v2.14.7 实测否决（内容不跟随、仅被边界裁切），缩小能力
         * 现由 ZoomPinchLayout 在捏合期间动态改写 viewport meta 实现
         * （v2.14.9），默认态与页面渲染管线无关。
         */
        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            view?.invalidate()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            runCatching { CookieManager.getInstance().flush() }
            view?.evaluateJavascript(
                "window.__linboxFlashActive=${tab.pageActive};" + flashBootstrapScript +
                    ";window.__linboxSetFlashActive(${tab.pageActive});", null
            )
            view?.let {
                tab.canGoBack = it.canGoBack()
                tab.canGoForward = it.canGoForward()
                if (isActive()) manager.ui?.onUrlSync(it.url.orEmpty())
                if (url?.startsWith("http") == true || url?.startsWith("file:") == true) {
                    if (manager.browserMode == "compatibility") {
                        it.evaluateJavascript(IE_COMPAT_SCRIPT + DESKTOP_MODE_SCRIPT, null)
                    } else if (manager.browserMode == "speed" || manager.uaMode == "desktop") {
                        it.evaluateJavascript(DESKTOP_MODE_SCRIPT, null)
                    }
                    it.evaluateJavascript(MEDIA_CONTEXT_MENU_SCRIPT, null)
                }
            }

            // ===== v2.16.3 鼠标视角（3D 视角旋转）：onPageFinished 注入 =====
            // 参照 GameBox（GAMEBOX：onPageFinished 注入 CAMERA_ROTATION_SCRIPT，
            // 注释“导航后会丢失，需重新注入”）：onPageStarted 时 DOM 尚未就绪，
            // CSS/element 查询会失败；onPageFinished 时 document.head/canvas
            // 已可用，hook requestPointerLock 也能在用户点击画布前就位。
            // 脚本幂等（window.__linboxLook 防重复），SPA 锚点导航重复触发安全。
            // v2.19：只在 3D 视角模式开启时注入 —— 脚本的 rAF pull 循环每帧
            // 跨 JNI 一次，重型门户页（qqgame 等）上徒增渲染压力；关闭时
            // 普通网页全程零注入零开销（开启时由 BrowserContent 补注入）
            if (View3dController.enabled &&
                url != null && (url.startsWith("http") || url.startsWith("file:"))
            ) {
                view?.evaluateJavascript(View3dController.LOOK_SETUP_SCRIPT, null)
            }

            // v2.14.5 曾在此注入 viewport 缩放脚本（SPA 兜底），v2.14.6 撤销，
            // v2.14.9 缩小域改为捏合期间动态改写 viewport meta（理由同
            // onPageCommitVisible 注释）

            // ===== v2.14.2（gamehtml）强制重绘管线 —— 灰屏的真正解法 =====
            // 部分网页（View Transitions SPA / 重 Canvas 页面）加载完成后渲染
            // 管线未正确触发重绘，页面“卡住”直到切后台再回来才显示。
            // invalidate + requestLayout 强制 WebView 重新绘制当前帧。
            view?.invalidate()
            view?.requestLayout()
            // 延迟二次重绘：等注入脚本执行完后再触发一次
            val weak = WeakReference(view)
            view?.postDelayed({
                val wv = weak.get() ?: return@postDelayed
                if (wv.tag == TAG_DESTROYED) return@postDelayed
                wv.invalidate()
                wv.evaluateJavascript(
                    "try{void document.body&&document.body.offsetHeight;}catch(e){}", null
                )
            }, 300)

            // ===== v1.3.1：资源嗅探 —— DOM 下载链接扫描 =====
            // ROM/游戏站的真实下载源（.swf/.zip/.nes 等）多以 <a> 链接形式
            // 挂在页面上，不点击不加载、不经 shouldInterceptRequest ——
            // 只有主动扫 DOM 才能采集。立即扫一次 + 1.5s 后补扫（懒加载
            // 列表派屏后新链接）。去重在 ResourceSniffer 内部（seen 表）。
            if (url != null && (url.startsWith("http") || url.startsWith("file:"))) {
                sniffDomLinks(view)
                val wref = WeakReference(view)
                view?.postDelayed({
                    val wv = wref.get() ?: return@postDelayed
                    if (wv.tag == TAG_DESTROYED) return@postDelayed
                    sniffDomLinks(wv)
                }, 1500)
            }
        }

        /**
         * v2.14.3：渲染进程崩溃/OOM 兜底（WebView 自身的问题）。
         * 不 override 该回调时，Chromium 会直接把【整个应用进程】杀掉。
         * 处理：丢弃死亡 WebView，标签重置为未加载态，renderEpoch+1 触发
         * key() 重组 → factory 重建新 WebView → 自动重载崩溃前页面。
         * 4399 等 Ruffle/WASM 重型页面内存压力大，该路径真实可达。
         */
        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?
        ): Boolean {
            val dead = view ?: return true
            if (dead.tag == TAG_DESTROYED) return true
            try {
                // 先更新状态再销毁：epoch 变化在下一帧驱动 factory 重建
                tab.webView = null
                tab.hasLoadedOnce = false
                tab.lastRequestedUrl = null
                // v2.14.7：新 WebView 从默认 100% 开始，不继承旧缩放态
                //（新文档天然无注入）
                tab.viewZoom = 1f
                tab.renderEpoch += 1
                // 若该标签正处视频全屏，同步收起死掉的全屏层
                if (manager.activeTabId == tab.id) {
                    manager.hideCustomView()
                }
                BrowserEngine.destroyWebView(dead)
                if (manager.activeTabId == tab.id) {
                    runCatching {
                        Toast.makeText(ctx, "页面渲染进程异常，已自动重新加载", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
            }
            // true = 应用已接管，系统不再杀进程
            return true
        }

        // ====================================================================
        // v2.14.1 https→http 自动回退（一次性）
        // ====================================================================
        // 场景：地址栏一律默认 https（保证加密浏览），但路由器/NAS 等
        // 内网服务只有 http。当内网地址 https 连接失败（ERROR_CONNECT /
        // ERROR_FAILED_SSL_HANDSHAKE）或 SSL 证书校验失败（自签/无证书）时，
        // 自动改用 http 重试一次：既保证默认 https，又兼容 http 服务。
        // 公网域名不回退 —— 无效证书必须显式拒绝（安全）。
        private fun tryHttpsFallback(view: WebView?, failingUrl: String): Boolean {
            if (!isLanishUrl(failingUrl)) return false
            if (tab.httpsFallbackUrl == failingUrl) return false
            tab.httpsFallbackUrl = failingUrl
            val httpUrl = "http://" + failingUrl.removePrefix("https://")
            tab.url = httpUrl
            tab.lastRequestedUrl = httpUrl
            if (manager.activeTabId == tab.id) {
                manager.ui?.onUrlSync(httpUrl)
            }
            runCatching {
                Toast.makeText(ctx, "该地址不支持 HTTPS，已自动改用 HTTP 加载", Toast.LENGTH_SHORT).show()
            }
            view?.let { wv ->
                if (wv.tag != TAG_DESTROYED) {
                    wv.post { if (tab.webView === wv) wv.loadUrl(httpUrl) }
                }
            }
            return true
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame != true) {
                request?.url?.let { uri -> tab.flashDiagnostics.recordResource(uri, errorType = "WebResourceError", method = request.method) }
            }
            // 仅主框架错误才处理；忽略 -1（未知/取消）与 DNS 解析失败（WebView 会自行重试）
            if (request?.isForMainFrame == true) {
                val code = error?.errorCode ?: -1
                // v2.14.1：内网地址 https 连接/SSL 握手失败 → 自动回退 http 重试一次
                if (code == WebViewClient.ERROR_CONNECT ||
                    code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
                ) {
                    if (tryHttpsFallback(view, request.url.toString())) return
                }
                if (isActive() && code != -1 && code != WebViewClient.ERROR_HOST_LOOKUP) {
                    manager.ui?.onError(error?.description?.toString() ?: "加载失败")
                }
            }
        }

        override fun onReceivedHttpError(
            view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request?.isForMainFrame != true && (errorResponse?.statusCode ?: 0) >= 400) {
                request?.url?.let { uri ->
                    tab.flashDiagnostics.recordResource(uri, status = errorResponse?.statusCode, method = request.method)
                }
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            val failingUrl = error?.url ?: ""
            // v2.14.1：内网地址（路由器/NAS 自签证书）https 握手失败 →
            // 取消本次 https 加载并改用 http 重试一次。
            // 仅当失败资源就是主框架页面时才回退，避免子资源证书问题把整页导航走。
            if (failingUrl.isNotEmpty() && failingUrl == view?.url &&
                tryHttpsFallback(view, failingUrl)
            ) {
                handler?.cancel()
                return
            }
            // 公网站点：不自动信任无效证书（安全底线），保持默认取消行为
            super.onReceivedSslError(view, handler, error)
            // 仅主框架证书失败才提示（子资源失败沿用默认取消行为，不打扰用户）
            if (isActive() && failingUrl.isNotEmpty() && failingUrl == view?.url) {
                manager.ui?.onError("SSL 证书校验失败，已阻止不安全的连接")
            }
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            // v1.3.0：资源嗅探 —— 页面发起的所有 http/https 子资源
            //（图片/脚本/样式/媒体/XHR/SWF）被动记录，悬浮球「资源嗅探」
            // 面板可勾选批量下载；零额外流量（不预取，只记 URL）。
            val snifferUrl = request.url.toString()
            if (request.url.host != RUFFLE_VIRTUAL_HOST &&
                (snifferUrl.startsWith("http://", ignoreCase = true) ||
                    snifferUrl.startsWith("https://", ignoreCase = true))
            ) {
                ResourceSniffer.record(snifferUrl)
            }
            flashResources.intercept(request)?.let { return it }
            // v2.14.4：Ruffle 引擎本地化 —— 虚拟域全部从 assets 供给，
            // 彻底摆脱 jsdelivr/unpkg 在国内网络的 TLS 掐断（日志证据：
            // ssl_client_socket_impl handshake failed net_error -100，
            // 页面 JS 在跑、音频在播但引擎子资源全挂 → 灰屏有声音）
            if (request.url.host == RUFFLE_VIRTUAL_HOST) {
                return serveRuffleAsset(request.url.path)
            }
            // 支持本地 content:// URI 的 HTML 文件读取
            val uri = request.url
            if (uri.scheme == "content") {
                return try {
                    val mime = ctx.contentResolver.getType(uri) ?: "text/html"
                    val stream = ctx.contentResolver.openInputStream(uri)
                    WebResourceResponse(mime, "UTF-8", stream)
                } catch (_: Exception) {
                    null
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        /**
         * v1.3.1：DOM 下载链接扫描 → 资源嗅探面板。
         * evaluateJavascript 回传 JSON 数组文本（"[]" = 无命中），
         * 解析后批量 [ResourceSniffer.recordLinks] 入库（主线程回调，
         * 内部持锁去重，与 IO 线程的 record 互斥安全）。
         */
        private fun sniffDomLinks(view: WebView?) {
            if (view == null || view.tag == TAG_DESTROYED) return
            try {
                view.evaluateJavascript(SNIFF_LINKS_SCRIPT) { result ->
                    if (result == null || result.length < 3) return@evaluateJavascript
                    try {
                        val arr = org.json.JSONArray(result)
                        val urls = ArrayList<String>(arr.length())
                        for (i in 0 until arr.length()) {
                            val u = arr.optString(i)
                            if (u.isNotBlank()) urls.add(u)
                        }
                        if (urls.isNotEmpty()) ResourceSniffer.recordLinks(urls)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }

        /** 本地 Ruffle 资产响应头（CORS + 不缓存，便于版本升级后立即生效） */
        private val ruffleAssetHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-cache"
        )

        /**
         * v2.14.4：从 assets/ruffle/ 供给引擎文件。
         * ruffle.js 加载器会按浏览器特性从 publicPath 拉取 core.ruffle.*.js
         * 与 *.wasm（fetch 跨域 → 必须带 CORS 头；wasm 流式编译 → MIME 必须
         * application/wasm）。文件不存在时返回 404（而非 null）。
         *
         * v2.14.5（关键修复）：请求路径形如 /ruffle/<file>，旧实现未剥离
         * 目录前缀，name 含 '/' 被穿越防护全量拒绝 → 本地引擎资产 100%
         * 返回 404。与 GameBox 的 interceptAsset 对齐：剥离虚拟
         * 目录前缀后按文件名映射 assets/ruffle/<file>，同时保留穿越防护。
         */
        private fun serveRuffleAsset(path: String?): WebResourceResponse {
            fun notFound() = WebResourceResponse(
                "text/plain", "UTF-8", 404, "Not Found",
                ruffleAssetHeaders, ByteArrayInputStream(ByteArray(0))
            )
            if (path.isNullOrEmpty()) return notFound()
            var name = path.substringBefore("?").removePrefix("/")
            // 剥离虚拟目录前缀：/ruffle/ruffle.js → ruffle.js
            if (name.startsWith("$RUFFLE_ASSET_DIR/")) {
                name = name.removePrefix("$RUFFLE_ASSET_DIR/")
            }
            // 路径穿越防护：只允许 assets/ruffle/ 下的直接文件名
            if (name.isEmpty() || name.contains("..") || name.contains('/')) return notFound()
            return try {
                val stream = ctx.assets.open("$RUFFLE_ASSET_DIR/$name")
                val mime = when {
                    name.endsWith(".wasm", true) -> "application/wasm"
                    name.endsWith(".js", true) -> "text/javascript"
                    name.endsWith(".css", true) -> "text/css"
                    name.endsWith(".ttf", true) -> "font/ttf"       // v2.14.5：SimHei 字体
                    name.endsWith(".woff", true) -> "font/woff"
                    name.endsWith(".woff2", true) -> "font/woff2"
                    name.endsWith(".otf", true) -> "font/otf"
                    else -> "application/octet-stream"
                }
                WebResourceResponse(mime, null, stream).apply {
                    responseHeaders = ruffleAssetHeaders
                }
            } catch (_: Exception) {
                notFound()
            }
        }

        companion object {
            // ================================================================
            // v2.14.4：Ruffle 引擎本地化（虚拟域 → assets 拦截，零 CDN 依赖）
            // ================================================================
            /** 虚拟域：shouldInterceptRequest 按 host 命中后从 assets 供给 */
            private const val RUFFLE_VIRTUAL_HOST = "linbox.local"

            /** assets 内引擎目录（ruffle.js + 2×core.js + 2×wasm） */
            private const val RUFFLE_ASSET_DIR = "ruffle"

            /**
             * View Transitions API 补丁（gamehtml，所有页面通用）。
             * WebView 对 startViewTransition 支持不完善：回调不执行导致 SPA 无法
             * 跳转、finished Promise 抛 AbortError。polyfill 确保回调同步执行，
             * 并强制触发一帧重绘（解决 polyfill 执行后页面"卡住"）。
             */
            private const val VIEW_TRANSITION_PATCH_SCRIPT = """
                (function(){
                  if (window.__vtPatch) return;
                  window.__vtPatch = true;
                  var vtPolyfill = function(callback) {
                    var result;
                    try { result = callback ? callback() : undefined; } catch(e) { result = Promise.reject(e); }
                    var p = (result && typeof result.then === 'function') ? result : Promise.resolve();
                    requestAnimationFrame(function() {
                      try { void document.body && document.body.offsetHeight; } catch(e) {}
                    });
                    var finished = p.then(undefined, function(err) {
                      if (err && err.name === 'AbortError') return;
                      throw err;
                    });
                    return { finished: finished, ready: Promise.resolve(),
                             updateCallbackDone: p, skipTransition: function() {}, types: [] };
                  };
                  try {
                    Object.defineProperty(Document.prototype, 'startViewTransition', {
                      value: vtPolyfill, writable: true, configurable: true });
                  } catch(e) {}
                  try { document.startViewTransition = vtPolyfill; } catch(e) {}
                  if (!window.__vtRejectionHook) {
                    window.__vtRejectionHook = true;
                    window.addEventListener('unhandledrejection', function(event) {
                      if (event.reason && event.reason.name === 'AbortError') {
                        var msg = event.reason.message || String(event.reason);
                        if (msg.indexOf('Transition') >= 0 || msg.indexOf('skipped') >= 0 || msg === 'AbortError') {
                          event.preventDefault();
                        }
                      }
                    });
                  }
                })();
            """

            private const val DESKTOP_MODE_SCRIPT = """
                (function(){
                  var firstInstall=!window.__linboxDesktopMode;
                  window.__linboxDesktopMode=true;
                  function setProperty(target,name,value){
                    try{Object.defineProperty(target,name,{get:function(){return value;},configurable:true});}catch(e){}
                  }
                  setProperty(navigator,'platform','Win32');
                  setProperty(navigator,'maxTouchPoints',0);
                  setProperty(screen,'width',1280);
                  setProperty(screen,'availWidth',1280);
                  function applyViewport(){
                    try{
                      var head=document.head||document.documentElement;
                      if(!head)return;
                      var meta=document.querySelector('meta[name="viewport"]');
                      if(!meta){meta=document.createElement('meta');meta.name='viewport';head.appendChild(meta);}
                      var parts=(meta.content||'').split(',').map(function(part){return part.trim();}).filter(function(part){return part&&!/^width\s*=/i.test(part);});
                      parts.unshift('width=1280');
                      var next=parts.join(', ');
                      if(meta.content!==next)meta.content=next;
                    }catch(e){}
                  }
                  applyViewport();
                  if(firstInstall&&window.MutationObserver){
                    var observer=new MutationObserver(function(){applyViewport();watchViewport();});
                    function watchViewport(){
                      observer.disconnect();
                      if(document.head){
                        observer.observe(document.head,{childList:true});
                        var meta=document.querySelector('meta[name="viewport"]');
                        if(meta)observer.observe(meta,{attributes:true,attributeFilter:['content','name']});
                      }else if(document.documentElement){
                        observer.observe(document.documentElement,{childList:true});
                      }
                    }
                    watchViewport();
                  }
                })();
            """

            /**
             * PC 页面 viewport 自适配（gamehtml VIEWPORT_FIT_SCRIPT，v2.14.6 恢复）。
             *
             * v2.14.5 曾用 ZOOM_VIEWPORT_SCRIPT（宽画布 + minimum-scale=0.3）
             * 尝试打开 30% 缩小域，实测失败并撤销，根因记录如下：
             * 1) Chromium 页面最小缩放 = max(meta minimum-scale, 窗口宽/画布宽)，
             *    而画布宽就是排版宽度 —— viewport 层面"缩到 100% 以下"与
             *    "排版不变"物理上不可能兼得：画布一放宽，媒体查询/流式布局/
             *    initial 全览全部跟着变；
             * 2) 实测症状与用户反馈逐条对应：4399 PC 首页按超宽画布排版整页
             *    压扁（排版有问题）、初始即 30% 全览（默认缩放被改）、初始
             *    已是最小值（不能继续缩小）、全览态无滚动余量（往左拉不过去）；
             * 3) 结论：30%~100% 缩小域改由 ZoomPinchLayout 在捏合期间动态
             *    改写 viewport meta 实现（v2.14.9：width=原宽/z + 钳制
             *    scale = 窗口宽/画布宽，整页等比变小且任何比例下精确铺满
             *    窗口宽 —— 桌面 Chrome Ctrl+减号 同源语义；v2.14.7/8 的
             *    body.zoom 方案因 letterbox 灰边被用户否决），本脚本只负责
             *    默认排版 —— 与 v2.14.4 逐字一致；
             *    ≥100% 放大继续由原生捏合处理（排版重排/文字回流）。
             *
             * 本脚本语义（v2.14.2 起不变）：仅对【无 viewport meta】的 PC 页
             * 注入 width=device-width + sw/1200 自适配 initial-scale（device-width
             * 画布下 initial 被 Chromium 钳到地板 100%，窄屏 sw/1200 不生效但
             * 无害），页面自带 viewport 则完全不动。必须在页面自身 JS 之前
             * 执行（onPageStarted），零网络开销。
             */
            private const val VIEWPORT_FIT_SCRIPT = """
                (function(){
                  try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (meta) return;
                    var head = document.head || document.documentElement;
                    if (!head) return;
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    var sw = window.screen.width || 360;
                    var scale = Math.max(0.25, Math.min(1, sw / 1200));
                    meta.content = 'width=device-width, initial-scale=' + scale +
                        ', minimum-scale=0.01, maximum-scale=10.0, user-scalable=yes';
                    head.appendChild(meta);
                  } catch(e) {}
                })();
            """

            private const val MEDIA_CONTEXT_MENU_SCRIPT = """
                (function(){
                  if(window.__linboxMediaContextInstalled)return;
                  window.__linboxMediaContextInstalled=true;
                  function absolute(value){try{return value?new URL(value,document.baseURI).href:'';}catch(e){return '';}}
                  function mimeFrom(value){
                    var dataMime=(value||'').match(/^data:([^;,]+)/i);if(dataMime)return dataMime[1].toLowerCase();
                    var path=(value||'').split(/[?#]/)[0];
                    try{var u=new URL(value,document.baseURI);var name=u.searchParams.get('filename')||u.searchParams.get('file')||u.searchParams.get('name');if(name&&name.indexOf('.')>=0)path=name;}catch(e){}
                    var ext=path.split('.').pop().toLowerCase();
                    var types={jpg:'image/jpeg',jpeg:'image/jpeg',png:'image/png',gif:'image/gif',webp:'image/webp',avif:'image/avif',bmp:'image/bmp',svg:'image/svg+xml',mp4:'video/mp4',m4v:'video/mp4',webm:'video/webm',mov:'video/quicktime',m3u8:'application/vnd.apple.mpegurl'};
                    return types[ext]||'';
                  }
                  function largestSrcset(value){
                    if(!value)return '';
                    var best='',bestSize=-1;
                    value.split(',').forEach(function(entry){
                      var parts=entry.trim().split(/\s+/);
                      var match=(parts[1]||'1x').match(/^([0-9.]+)(w|x)$/i);
                      var size=match?parseFloat(match[1]):-1;
                      if(parts[0]&&size>bestSize){bestSize=size;best=parts[0];}
                    });
                    return absolute(best);
                  }
                  document.addEventListener('contextmenu',function(event){
                    var el=event.target;
                    var image=el.closest&&el.closest('img');
                    var video=el.closest&&el.closest('video');
                    if(!video){
                      var videos=document.querySelectorAll('video');
                      for(var i=0;i<videos.length;i++){
                        var rect=videos[i].getBoundingClientRect();
                        if(event.clientX>=rect.left&&event.clientX<=rect.right&&event.clientY>=rect.top&&event.clientY<=rect.bottom){video=videos[i];break;}
                      }
                    }
                    if(!image&&!video)return;
                    event.preventDefault();event.stopImmediatePropagation();
                    var target={kind:'image',url:'',linkUrl:'',mimeType:''};
                    if(image){
                      var link=image.closest('a[href]');
                      var original=image.getAttribute('data-original')||image.getAttribute('data-original-src')||image.getAttribute('data-src-original')||image.getAttribute('data-full')||image.getAttribute('data-zoom-image')||image.getAttribute('data-hi-res-src')||image.getAttribute('data-large')||image.getAttribute('data-big');
                      var picture=image.closest('picture');
                      var sources=picture?picture.querySelectorAll('source[srcset],source[data-srcset]'):[];
                      var source='';for(var s=0;s<sources.length;s++)source=largestSrcset(sources[s].getAttribute('data-srcset')||sources[s].getAttribute('srcset'))||source;
                      var linkUrl=link&&link.href!==location.href?link.href:'';
                      var linkPath=linkUrl.split(/[?#]/)[0];
                      var linkName='';try{var linkParsed=new URL(linkUrl,document.baseURI);linkName=linkParsed.searchParams.get('filename')||linkParsed.searchParams.get('file')||linkParsed.searchParams.get('name')||'';}catch(e){}
                      var linkedOriginal=/\.(jpe?g|png|gif|webp|avif|bmp|svg)$/i.test(linkPath)||/\.(jpe?g|png|gif|webp|avif|bmp|svg)$/i.test(linkName);
                      target.url=absolute(original)||(linkedOriginal?absolute(linkUrl):'')||source||largestSrcset(image.getAttribute('data-srcset')||image.getAttribute('srcset'))||absolute(image.getAttribute('data-src'))||absolute(image.getAttribute('data-lazy-src'))||absolute(image.src)||absolute(image.currentSrc);
                      target.linkUrl=linkedOriginal?absolute(linkUrl):'';
                      target.mimeType=image.getAttribute('type')||image.getAttribute('data-mime-type')||mimeFrom(target.url);
                    }else{
                      target.kind='video';
                      var sources=video.querySelectorAll('source');
                      var best='',quality=-1;
                      for(var v=0;v<sources.length;v++){
                        var candidate=sources[v];
                        var candidateUrl=candidate.getAttribute('data-original')||candidate.getAttribute('data-src')||candidate.src||'';
                        var q=parseInt(candidate.getAttribute('data-quality')||candidate.getAttribute('data-res')||candidate.getAttribute('label')||'')||0;
                        var res=(candidateUrl.match(/(2160|1440|1080|720|480|360)p/i)||[])[1];
                        q=Math.max(q,parseInt(res||'0'));
                        if(candidateUrl&&q>=quality){quality=q;best=candidateUrl;target.mimeType=candidate.type||'';}
                      }
                      target.url=absolute(video.getAttribute('data-original')||video.getAttribute('data-full'))||absolute(best)||absolute(video.getAttribute('data-src')||video.getAttribute('src'))||absolute(video.currentSrc);
                      target.mimeType=target.mimeType||video.getAttribute('type')||mimeFrom(target.url);
                    }
                    if(target.url&&window.__linboxMediaMenu)window.__linboxMediaMenu.show(JSON.stringify(target));
                  },true);
                })();
            """

            /**
             * v1.3.1：DOM 下载链接扫描脚本（onPageFinished 注入，返回
             * 绝对 URL 数组，由 [TabWebViewClient.sniffDomLinks] 解析入库）。
             * 采集 a[href] / area[href] / embed[src] / object[data] /
             * source[src] 中扩展名命中下载类型的链接（swf / 主机 ROM /
             * 压缩包 / 媒体 / exe / pdf），每次上限 400 条；路径含 .swf
             *（Flash 站资源目录）与 4399 无扩展名模式（/dw-、flash_tm3）
             * 同源识别。纯 JS 无 Kotlin 插值。
             */
            private const val SNIFF_LINKS_SCRIPT = """
                (function(){
                  try {
                    var EXT = /\.(swf|nes|fds|sfc|smc|gba|gbc|gb|nds|3ds|n64|z64|v64|md|smd|gen|sms|gg|ws|wsc|ngp|npc|pce|vb|a26|a78|col|int|vec|dsk|cue|iso|img|bin|rom|chd|zip|7z|rar|tar|gz|bz2|xz|apk|xapk|jar|obb|exe|pdf|mp3|mp4|avi|mkv|flv|wav|flac|ogg|m4a)$/i;
                    var STATIC = /\.(js|css|png|jpe?g|gif|webp|svg|ico|html?)$/i;
                    var out = [], seen = {};
                    var nodes = document.querySelectorAll('a[href],area[href],embed[src],object[data],source[src]');
                    var base = document.baseURI || location.href;
                    for (var i = 0; i < nodes.length && out.length < 400; i++) {
                      var el = nodes[i];
                      var u = el.getAttribute('href') || el.getAttribute('src') || el.getAttribute('data') || '';
                      if (!u || u.length < 5 || u.charAt(0) === '#') continue;
                      if (/^(javascript|mailto|tel|about|blob|data):/i.test(u)) continue;
                      try { u = new URL(u, base).href; } catch (e) { continue; }
                      if (!/^https?:/i.test(u)) continue;
                      var end = u.length, q = u.indexOf('?');
                      if (q >= 0) end = q; else { var h = u.indexOf('#'); if (h >= 0) end = h; }
                      var path = u.substring(0, end).toLowerCase();
                      var isDl = EXT.test(path) || path.indexOf('.swf') >= 0;
                      // 4399 无扩展名 swf 模式（与 Kotlin 端 classify 同源：
                      // 按链接 URL 的 host 判定，静态资源防误伤）
                      if (!isDl && !STATIC.test(path) && /4399\.com/i.test(u) &&
                          (path.indexOf('/dw-') >= 0 || path.indexOf('flash_tm3') >= 0 ||
                           path.indexOf('flash20') >= 0)) isDl = true;
                      if (isDl && !seen[u]) { seen[u] = 1; out.push(u); }
                    }
                    return out;
                  } catch (e) { return []; }
                })()
            """
        }
    }

    // ====================================================================
    // WebChromeClient：多标签弹窗 + JS 弹窗 + 视频全屏 + 文件选择
    // ====================================================================
    private class TabChromeClient(
        private val ctx: Context,
        private val tab: BrowserTab,
        private val manager: TabManager
    ) : WebChromeClient() {

        private fun isActive() = manager.activeTabId == tab.id

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            consoleMessage?.let { tab.flashDiagnostics.recordConsole(it.message(), it.sourceId()) }
            return super.onConsoleMessage(consoleMessage)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (isActive()) {
                manager.ui?.onProgressChanged(newProgress)
            }
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (!title.isNullOrEmpty()) {
                tab.title = title
                if (isActive()) {
                    manager.ui?.onTitleChanged(title)
                }
            }
        }

        /**
         * v3.0（LinBox 移植补强）：favicon 捕获 —— 浏览器图标识别。
         * 页面图标（apple-touch-icon / link rel=icon / 默认地球）到达即
         * 压缩为 ≤64×64 PNG 存入标签；加入书签时持久化，书签列表展示。
         */
        override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) {
            if (icon == null) return
            runCatching {
                val side = maxOf(icon.width, icon.height).coerceAtLeast(1)
                val scaled = if (side > 64) {
                    val f = 64f / side
                    android.graphics.Bitmap.createScaledBitmap(
                        icon,
                        (icon.width * f).toInt().coerceAtLeast(1),
                        (icon.height * f).toInt().coerceAtLeast(1),
                        true
                    )
                } else icon
                val bos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, bos)
                tab.faviconPng = bos.toByteArray()
            }
        }

        /**
         * window.open / target=_blank 链接 / 新窗口表单提交（百度"百度一下"）统一入口。
         *
         * v2.8：弹出的 WebView 直接作为【新标签的真实 WebView】：
         * 1) 创建 WebView 并完整配置（client 绑定到新 tab）
         * 2) 登记为新标签并激活 —— Compose 随即将其上屏
         * 3) 通过 transport 交给 Chromium —— 表单 POST / JS 重定向等
         *    一切导航在真实标签中自然完成（旧版临时 WebView 拦截不到 POST，
         *    导致"点击百度一下没反应"）
         * 4) 页面加载后由其自身 client 同步 tab.url / 标题 / 地址栏
         */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            if (resultMsg == null) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popupWebView = WebView(ctx)
            val popupTab = manager.openTab("about:blank", popupWebView)
            configure(popupWebView, popupTab, manager)
            // v2.14.4（核心）：transport 交接【前】立即把弹窗 WebView 挂进真实
            // 视图树 —— Chromium 收到 transport 后马上开始加载；若此刻脱离视图
            // 树（ContentCatcher 日志：创建时 0,0-0,0），所有首帧生产落空，
            // attach 梯子（v2.14.3）只是事后补救。挂到 opener 的容器（真实
            // attached 视图树），首帧在真实 surface 上产出；openTab 已激活新
            // 标签，Compose 下一帧重组时 factory 会剥离临时父容器认领。
            // v2.14.6：opener 的父容器现在是 ZoomPinchLayout（普通 FrameLayout，
            // 会自动布局子 View），手动 measure/layout 仍保留 —— transport
            // 交接后 Chromium 立即加载，下一帧布局 pass 之前就要有正确尺寸。
            try {
                val holder = view?.parent as? ViewGroup
                if (holder != null && view.width > 0 && view.height > 0) {
                    holder.addView(
                        popupWebView,
                        ViewGroup.LayoutParams(view.width, view.height)
                    )
                    popupWebView.measure(
                        View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY)
                    )
                    popupWebView.layout(0, 0, view.width, view.height)
                }
            } catch (_: Exception) {
                // opener 脱离视图树/尺寸为 0 的边缘场景：跳过临时挂载，
                // v2.14.3 的 attach 重绘梯子仍然兜底
            }
            transport.webView = popupWebView
            resultMsg.sendToTarget()
            return true
        }

        /** window.close()：JS 自动关闭的弹窗标签 → 关闭对应标签 */
        override fun onCloseWindow(window: WebView?) {
            if (tab.isPopup) {
                manager.closeTab(tab.id)
            }
        }

        // 自动确认 JS 弹窗，避免网页交互卡住
        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            result?.confirm()
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            result?.confirm()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?
        ): Boolean {
            result?.confirm(defaultValue)
            return true
        }

        /**
         * HTML5 全屏（视频站点播放器的全屏按钮）：
         * 把页面提交的自定义视图挂到 DecorView 最顶层 → 盖过任务栏/状态栏，
         * 真正满屏播放（v2.8 修复，旧版仅调 super() 等于什么都不做）。
         */
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null) {
                callback?.onCustomViewHidden()
                return
            }
            manager.showCustomView(view, callback)
        }

        override fun onShowCustomView(
            view: View?,
            requestedOrientation: Int,
            callback: CustomViewCallback?
        ) {
            // 带方向的变体同样处理（忽略页面请求的方向，遵循用户显示设置）
            onShowCustomView(view, callback)
        }

        override fun onHideCustomView() {
            manager.hideCustomView()
        }

        // 文件上传回调（input[type=file]）：经 FileChooserBus 集中登记，
        // MainActivity.onActivityResult 回传（修复原版选完文件页面无响应的断链）

        // v2.14.2（gamehtml GameWebChromeClient）：游戏/网页所需的运行时权限直接授予
        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
            try {
                request?.grant(request.resources)
            } catch (_: Exception) {
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: android.webkit.GeolocationPermissions.Callback?
        ) {
            callback?.invoke(origin, true, false)
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            // LinBrowser 独立版：FileChooserBus 登记 + Activity Result launcher，
            // 结果由 launcher 回调 → FileChooserBus.onResult 回传页面（v1.2.3）
            return FileChooserBus.launch(filePathCallback, fileChooserParams)
        }
    }
}

// ===========================================================================
// v6.10：浏览器下载器（用户："修复浏览器无法下载文件等问题"）
// ===========================================================================

/**
 * 下载处理中枢：
 * - http/https → 系统 DownloadManager（带 Cookie/Referer/UA，登录态可用）
 * - blob: → 页内 XHR 读 blob → FileReader 转 base64 → __linboxDownload
 *   分块桥 → 临时文件 → 移入公共 Downloads/
 * - data: → 直接解码落盘
 *
 * 文件名三级回退：RFC 6266 filename*（RFC 5987 百分号编码，中文站常用）→
 * filename="…" → URL 末段 → 时间戳；统一清洗非法字符 + 按 MIME 补扩展名。
 */
internal class MediaContextMenuBridge(private val manager: TabManager) {
    @JavascriptInterface
    fun show(value: String?) {
        runCatching {
            val json = JSONObject(value ?: return)
            val target = BrowserLongPressTarget(
                kind = json.optString("kind"),
                url = json.optString("url"),
                linkUrl = json.optString("linkUrl"),
                linkText = json.optString("linkText"),
                mimeType = json.optString("mimeType")
            )
            val scheme = Uri.parse(target.url).scheme?.lowercase()
            if (target.kind !in setOf("image", "video") || scheme !in setOf("http", "https", "blob", "data")) return
            Handler(Looper.getMainLooper()).post {
                if (target.url.isNotBlank()) manager.ui?.onLongPress(target)
            }
        }
    }
}

internal object BrowserDownloads {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 每个 WebView 一座下载桥（分块状态隔离）。 */
    fun bridge(wv: WebView): LinboxDownloadBridge = LinboxDownloadBridge(wv)

    fun handleDownload(
        wv: WebView,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long = -1L,
        fallbackUrl: String? = null
    ) {
        val ctx = wv.context.applicationContext
        val downloadUrl = fallbackUrl?.takeIf { it.isNotBlank() } ?: url
        val filename = parseFilename(contentDisposition, downloadUrl, mimetype)
        when {
            url.startsWith("blob:", ignoreCase = true) -> {
                // blob: URL 属页面渲染进程私有，DownloadManager 取不到 ——
                // 注入 JS 用 XHR 读回，经 __linboxDownload 分块写临时文件；
                // 数据就绪后（bridge end）弹重命名确认再落盘
                toast(ctx, "正在读取页面数据…")
                val js = buildBlobFetchScript(url, filename, mimetype ?: "")
                runCatching { wv.evaluateJavascript(js, null) }
                    .onFailure { toast(ctx, "下载失败：无法读取页面数据") }
            }
            url.startsWith("data:", ignoreCase = true) -> {
                runCatching {
                    val bytes = decodeDataUri(url)
                    if (bytes == null) {
                        toast(ctx, "下载失败：无法解析 data 链接")
                    } else {
                        // 字节就绪 → 弹重命名确认（取消即丢弃，零落盘）
                        DownloadPromptBus.prompt(
                            DownloadRequest.Data(
                                suggestedName = filename,
                                mime = mimetype,
                                contentLength = bytes.size.toLong(),
                                bytes = bytes
                            )
                        )
                    }
                }.onFailure { toast(ctx, "下载失败: ${it.message}") }
            }
            else -> {
                // http/https：先弹重命名确认，确认后才入队 DownloadManager；
                // Cookie/Referer/UA 在此快照（确认时页面可能已导航）
                runCatching {
                    val cookie = CookieManager.getInstance().getCookie(downloadUrl)
                    val referer = wv.url?.takeIf { it.startsWith("http") }
                    DownloadPromptBus.prompt(
                        DownloadRequest.Http(
                            suggestedName = filename,
                            mime = mimetype,
                            contentLength = contentLength,
                            url = downloadUrl,
                            userAgent = userAgent,
                            cookie = cookie,
                            referer = referer
                        )
                    )
                }.onFailure { toast(ctx, "下载失败: ${it.message}") }
            }
        }
    }

    /**
     * 用户在重命名确认对话框点「下载」后真正开始下载/落盘
     * （DownloadPromptBus.confirm 调用）。
     *
     * v1.3.0：Http 分支改走 [AppDownloader] 应用内下载引擎（状态栏进度
     * 通知 + 完成点击直接打开文件），不再使用系统 DownloadManager ——
     * 用户反馈「下载为何调用的是自带浏览器，用我的项目浏览器直接下载」。
     */
    fun startConfirmed(request: DownloadRequest, filename: String) {
        when (request) {
            is DownloadRequest.Http -> {
                val ctx = LinBoxApp.get()
                AppDownloader.enqueue(
                    AppDownloader.Job(
                        url = request.url,
                        filename = filename,
                        mime = request.mime,
                        userAgent = request.userAgent,
                        cookie = request.cookie,
                        referer = request.referer
                    )
                )
                toast(ctx, "开始下载：$filename")
            }

            is DownloadRequest.Data -> {
                val ctx = LinBoxApp.get()
                val where = saveBytes(ctx, filename, request.mime, request.bytes)
                if (where == null) {
                    toast(ctx, "保存失败：存储不可用")
                }
            }

            is DownloadRequest.Blob -> {
                val ctx = LinBoxApp.get()
                val where = saveTempFile(ctx, request.tmpFile, filename, request.mime)
                if (where == null) {
                    toast(ctx, "保存失败：存储不可用")
                }
            }
        }
    }

    // ---- 文件名解析 ----

    private fun maybeDecode(s: String): String =
        if (s.contains("%")) {
            try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
        } else s

    /** 清洗非法字符 / 路径分隔符，限长 120。 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim().trim('.').take(120)

    fun mimeForUrl(url: String): String? {
        val extension = runCatching { Uri.parse(url).lastPathSegment?.substringAfterLast('.', "") }
            .getOrNull()?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "m3u8" -> "application/vnd.apple.mpegurl"
                "ts" -> "video/mp2t"
                else -> null
            }
    }

    fun ensureExtension(filename: String, mime: String?, contentDisposition: String?, url: String): String {
        val serverFilename = contentDisposition?.let { parseFilename(it, url, mime) }
        val serverExtension = serverFilename?.let { name ->
            name.substringAfterLast('.', "").takeIf { it.isNotBlank() && name.endsWith(".$it") }
        }
        val expected = extFor(mime ?: mimeForUrl(url))
            .ifBlank { serverExtension?.let { ".$it" }.orEmpty() }
        if (expected.isBlank()) return filename
        val current = filename.substringAfterLast('.', "").lowercase()
        if (current.isBlank()) return filename + expected
        if (current != expected.removePrefix(".")) return filename.substringBeforeLast('.') + expected
        return filename
    }

    /** MIME → 扩展名（MimeTypeMap 优先，常见类型兜底）。 */
    private fun extFor(mimetype: String?): String {
        mimetype ?: return ""
        val normalizedMime = mimetype.substringBefore(';').trim().lowercase()
        val mapped = runCatching {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(normalizedMime)
        }.getOrNull()
        if (!mapped.isNullOrBlank()) return ".$mapped"
        return when (normalizedMime) {
            "application/vnd.android.package-archive" -> ".apk"
            "application/zip", "application/x-zip-compressed" -> ".zip"
            "application/x-rar-compressed", "application/vnd.rar" -> ".rar"
            "application/x-7z-compressed" -> ".7z"
            "application/pdf" -> ".pdf"
            "application/octet-stream" -> ".bin"
            "text/html" -> ".html"
            "text/plain" -> ".txt"
            "image/jpeg" -> ".jpg"
            "image/avif" -> ".avif"
            "image/bmp" -> ".bmp"
            "image/svg+xml" -> ".svg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "audio/mpeg" -> ".mp3"
            "video/mp4" -> ".mp4"
            "video/webm" -> ".webm"
            "video/quicktime" -> ".mov"
            "application/vnd.apple.mpegurl", "application/x-mpegurl" -> ".m3u8"
            "application/x-msdownload", "application/x-msdos-program" -> ".exe"
            else -> ""
        }
    }

    /** 文件名三级回退 + 扩展名补全。 */
    fun parseFilename(contentDisposition: String?, url: String, mimetype: String?): String {
        val cd = contentDisposition ?: ""
        // 1) RFC 6266/5987：filename*=UTF-8''%E4%B8%AD%E6%96%87.zip（中文站常用）
        val star = Regex("""filename\*\s*=\s*(?:utf-8|UTF-8)''([^;]+)""")
            .find(cd)?.groupValues?.get(1)?.trim()
        if (!star.isNullOrBlank()) {
            val clean = sanitize(maybeDecode(star))
            if (clean.isNotEmpty()) return withExt(clean, mimetype)
        }
        // 2) filename="…" / filename=…
        val plain = Regex("""filename\s*=\s*"?([^";]+)"?""")
            .find(cd)?.groupValues?.get(1)?.trim()
        if (!plain.isNullOrBlank()) {
            val clean = sanitize(maybeDecode(plain))
            if (clean.isNotEmpty()) return withExt(clean, mimetype)
        }
        // 3) URL 中显式提供的文件名（媒体 CDN 常把扩展名放在查询参数中）
        if (url.startsWith("http", ignoreCase = true)) {
            runCatching {
                val uri = Uri.parse(url)
                val queryName = sequenceOf("filename", "file", "name", "download")
                    .mapNotNull { uri.getQueryParameter(it) }
                    .firstOrNull { it.isNotBlank() }
                if (queryName != null) {
                    val clean = sanitize(maybeDecode(queryName.substringAfterLast('/')))
                    if (clean.isNotEmpty()) return withExt(clean, mimetype)
                }
            }
        }
        // 4) URL 末段（去掉查询串）
        if (url.startsWith("http", ignoreCase = true)) {
            runCatching {
                val seg = Uri.parse(url).lastPathSegment
                if (!seg.isNullOrBlank()) {
                    val clean = sanitize(maybeDecode(seg))
                    if (clean.length > 1) return withExt(clean, mimetype)
                }
            }
        }
        // 5) 时间戳回退
        return "linbox_${System.currentTimeMillis()}${extFor(mimetype)}"
    }

    /** 缺扩展名时按 MIME 补全。 */
    private fun withExt(name: String, mimetype: String?): String {
        if (!mimetype.isNullOrBlank() && !name.substringAfterLast('/', name).contains('.')) {
            return name + extFor(mimetype)
        }
        return name
    }

    // ---- data: URI ----

    private fun decodeDataUri(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma).split(';')  // "data:" 固定 5 字符前缀
        val payload = url.substring(comma + 1)
        return if (meta.any { it.equals("base64", ignoreCase = true) }) {
            runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
        } else {
            maybeDecode(payload).toByteArray(Charsets.UTF_8)
        }
    }

    // ---- 公共落盘 ----

    /**
     * 字节落盘（LinBrowser 三级回退）：
     * 1) 公共 Downloads 直写（targetSdk=28 legacy 路径 + 存储权限）；
     * 2) MediaStore.Downloads（Android 10+，免权限兜底）；
     * 3) 应用专属外部目录（免权限，最后兜底）。
     * 返回最终位置描述（Toast 用）；null = 全部失败。
     */
    fun saveBytes(ctx: Context, filename: String, mimetype: String?, bytes: ByteArray): String? {
        val name = sanitize(filename)
        // 1) 公共 Downloads 直写
        runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            if (dir.canWrite()) {
                val file = uniqueFile(dir, name)
                FileOutputStream(file).use { it.write(bytes) }
                scan(ctx, file.absolutePath, mimetype)
                toast(ctx, "已保存到 Downloads/${file.name}")
                return "Downloads/${file.name}"
            }
        }
        // 2) MediaStore.Downloads（API 29+）
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val unique = uniqueMediaStoreName(ctx, name)
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, unique)
                    put(
                        android.provider.MediaStore.Downloads.MIME_TYPE,
                        mimetype ?: "application/octet-stream"
                    )
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS
                    )
                }
                val uri = ctx.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: error("MediaStore insert null")
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("openOutputStream null")
                toast(ctx, "已保存到 Downloads/$unique")
                return "Downloads/$unique"
            }
        }
        // 3) 应用专属外部目录
        return runCatching {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(ctx.filesDir, "Download").also { it.mkdirs() }
            val file = uniqueFile(dir, name)
            FileOutputStream(file).use { it.write(bytes) }
            toast(ctx, "已保存到应用目录 ${file.name}")
            "app/${file.name}"
        }.getOrNull()
    }

    /**
     * blob 临时文件落盘（同 [saveBytes] 三级回退；直写成功用 rename 零拷贝）。
     * 失败/取消由调用方删除临时文件。
     */
    fun saveTempFile(ctx: Context, tmp: File, filename: String, mimetype: String?): String? {
        val name = sanitize(filename)
        // 1) 公共 Downloads 直写（优先 rename，跨分区退 copy）
        runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            if (dir.canWrite()) {
                val dst = uniqueFile(dir, name)
                if (!tmp.renameTo(dst)) {
                    tmp.copyTo(dst, overwrite = true)
                    tmp.delete()
                }
                scan(ctx, dst.absolutePath, mimetype)
                toast(ctx, "已保存到 Downloads/${dst.name}")
                return "Downloads/${dst.name}"
            }
        }
        // 2) MediaStore.Downloads（API 29+）
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val unique = uniqueMediaStoreName(ctx, name)
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, unique)
                    put(
                        android.provider.MediaStore.Downloads.MIME_TYPE,
                        mimetype ?: "application/octet-stream"
                    )
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS
                    )
                }
                val uri = ctx.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: error("MediaStore insert null")
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                } ?: error("openOutputStream null")
                tmp.delete()
                toast(ctx, "已保存到 Downloads/$unique")
                return "Downloads/$unique"
            }
        }
        // 3) 应用专属外部目录
        return runCatching {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(ctx.filesDir, "Download").also { it.mkdirs() }
            val dst = uniqueFile(dir, name)
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            toast(ctx, "已保存到应用目录 ${dst.name}")
            "app/${dst.name}"
        }.onFailure { tmp.delete() }.getOrNull()
    }

    /**
     * v1.3.0：带子目录的临时文件落盘（[AppDownloader] 应用内下载引擎 +
     * 资源嗅探批量下载用）。子目录逐段清洗（防 ../ 逃逸），三级回退同
     * [saveTempFile]；返回落盘结果（File 直写/应用目录，Uri = MediaStore）。
     */
    fun saveTempFileTo(
        ctx: Context,
        tmp: File,
        filename: String,
        mimetype: String?,
        subdir: String
    ): AppDownloader.Saved? {
        val name = sanitize(filename)
        val sub = sanitizeSubdir(subdir)

        // 1) 公共 Downloads/<sub>/ 直写（优先 rename，跨分区退 copy）
        runCatching {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                sub
            )
            if (!dir.exists()) dir.mkdirs()
            if (dir.canWrite()) {
                val dst = uniqueFile(dir, name)
                if (!tmp.renameTo(dst)) {
                    tmp.copyTo(dst, overwrite = true)
                    tmp.delete()
                }
                scan(ctx, dst.absolutePath, mimetype)
                return AppDownloader.Saved(
                    file = dst, uri = null,
                    display = "Download/${if (sub.isBlank()) "" else "$sub/"}${dst.name}"
                )
            }
        }
        // 2) MediaStore.Downloads（API 29+；RELATIVE_PATH 支持子目录）
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val unique = uniqueMediaStoreName(ctx, if (sub.isBlank()) name else "${sub.replace('/', '_')}_$name")
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, unique)
                    put(
                        android.provider.MediaStore.Downloads.MIME_TYPE,
                        mimetype ?: "application/octet-stream"
                    )
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS +
                            (if (sub.isBlank()) "" else "/$sub")
                    )
                }
                val uri = ctx.contentResolver.insert(collection, values)
                    ?: error("MediaStore insert null")
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                } ?: error("openOutputStream null")
                tmp.delete()
                return AppDownloader.Saved(
                    file = null, uri = uri,
                    display = "Download/${if (sub.isBlank()) "" else "$sub/"}$unique"
                )
            }
        }
        // 3) 应用专属外部目录
        return runCatching {
            val dir = File(
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(ctx.filesDir, "Download").also { it.mkdirs() },
                sub
            )
            if (!dir.exists()) dir.mkdirs()
            val dst = uniqueFile(dir, name)
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            AppDownloader.Saved(
                file = dst, uri = null,
                display = "应用目录/${if (sub.isBlank()) "" else "$sub/"}${dst.name}"
            )
        }.onFailure { tmp.delete() }.getOrNull()
    }

    /** 子目录路径段逐段清洗（防空段 / 点段 / `..` 逃逸 / 非法字符；最深 8 层）。 */
    private fun sanitizeSubdir(subdir: String): String =
        subdir.split('/', '\\')
            .map { sanitize(it) }
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .take(8)
            .joinToString("/")

    /** 字节数人性化（v1.30 下载进度通知用）。 */
    fun fmtBytes(bytes: Long): String = when {
        bytes <= 0 -> "…"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
        else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    /** 文件名 → MIME（下载完成通知的打开 Intent 用）。 */
    fun guessMime(filename: String): String? {
        val ext = filename.substringAfterLast('.', "").lowercase()
        val mapped = runCatching {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }.getOrNull()
        if (!mapped.isNullOrBlank()) return mapped
        return when (ext) {
            "swf" -> "application/x-shockwave-flash"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "ts" -> "video/mp2t"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    /** MediaStore Downloads 集合中重名自动 (n)（对齐 uniqueFile 语义）。 */
    private fun uniqueMediaStoreName(ctx: Context, name: String): String {
        runCatching {
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(android.provider.MediaStore.Downloads.DISPLAY_NAME)
            val existing = HashSet<String>()
            ctx.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val idx = c.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DISPLAY_NAME)
                while (c.moveToNext()) existing.add(c.getString(idx))
            }
            if (name !in existing) return name
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var i = 1
            while (i < 1000) {
                val cand = "${base}(${i})$ext"
                if (cand !in existing) return cand
                i++
            }
        }
        return name
    }

    /** 重名自动加 (n) 后缀。 */
    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 1000) {
            f = File(dir, "${base}(${i})$ext")
            if (!f.exists()) return f
            i++
        }
        return File(dir, "${base}_${System.currentTimeMillis()}$ext")
    }

    fun scan(ctx: Context, path: String, mimetype: String?) {
        runCatching {
            MediaScannerConnection.scanFile(
                ctx, arrayOf(path), if (mimetype.isNullOrBlank()) null else arrayOf(mimetype), null
            )
        }
    }

    fun toast(ctx: Context, msg: String) {
        mainHandler.post {
            runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    // ---- blob: 分块下载桥 ----

    /** 页内 XHR 读 blob → base64 分块回调 __linboxDownload。 */
    private fun buildBlobFetchScript(url: String, filename: String, mime: String): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        return """
            (function(){
              try{
                var xhr=new XMLHttpRequest();
                xhr.open('GET','${esc(url)}',true);
                xhr.responseType='blob';
                xhr.onload=function(){
                  try{
                    if(xhr.status!==200&&xhr.status!==0){window.__linboxDownload.fail(xhr.status);return;}
                    var b=xhr.response;
                    if(!b){window.__linboxDownload.fail(-1);return;}
                    var fr=new FileReader();
                    fr.onload=function(){
                      try{
                        var s=String(fr.result||'');
                        var i=s.indexOf(',');
                        var b64=i>=0?s.substring(i+1):s;
                        window.__linboxDownload.begin('${esc(filename)}','${esc(mime)}'||(b.type||''));
                        var N=393216; // 384KB，4 的倍数（base64 分块可独立解码）
                        for(var o=0;o<b64.length;o+=N){
                          window.__linboxDownload.chunk(b64.substr(o,N));
                        }
                        window.__linboxDownload.end();
                      }catch(e){window.__linboxDownload.fail(-2);}
                    };
                    fr.onerror=function(){window.__linboxDownload.fail(-3);};
                    fr.readAsDataURL(b);
                  }catch(e){window.__linboxDownload.fail(-2);}
                };
                xhr.onerror=function(){window.__linboxDownload.fail(0);};
                xhr.send();
              }catch(e){window.__linboxDownload.fail(-2);}
            })();
        """.trimIndent()
    }

    /**
     * blob 下载落盘桥：begin → chunk… → end（临时文件累积，end 时移入
     * 公共 Downloads/）。分块避免 Binder/Chromium 单次字符串上限，
     * 临时文件避免大文件驻留内存。fail 任何阶段清理现场。
     */
    internal class LinboxDownloadBridge(wv: WebView) {
        private val ctx = wv.context.applicationContext
        private var tmp: File? = null
        private var out: FileOutputStream? = null
        private var filename: String = ""
        private var mime: String = ""

        @JavascriptInterface
        fun begin(name: String?, mime: String?) {
            runCatching {
                close()
                filename = name ?: ""
                this.mime = mime ?: ""
                val f = File(ctx.cacheDir, "linbox_dl_${System.currentTimeMillis()}.tmp")
                out = FileOutputStream(f)
                tmp = f
            }
        }

        @JavascriptInterface
        fun chunk(b64: String?) {
            if (b64.isNullOrBlank()) return
            runCatching {
                val o = out ?: return
                o.write(Base64.decode(b64, Base64.DEFAULT))
            }
        }

        @JavascriptInterface
        fun end() {
            runCatching {
                val o = out ?: return
                val src = tmp
                o.flush(); o.close(); out = null; tmp = null
                if (src == null || src.length() == 0L) {
                    src?.delete()
                    toast(ctx, "下载失败：文件为空")
                    return
                }
                // 数据就绪 → 弹重命名确认（取消即删临时文件，零残留）；
                // 确认后由 DownloadPromptBus.confirm → saveTempFile 落盘
                val suggested = if (filename.isBlank()) "linbox_download" else filename
                DownloadPromptBus.prompt(
                    DownloadRequest.Blob(
                        suggestedName = suggested,
                        mime = if (mime.isBlank()) null else mime,
                        contentLength = src.length(),
                        tmpFile = src
                    )
                )
            }.onFailure {
                close()
                toast(ctx, "保存失败: ${it.message}")
            }
        }

        @JavascriptInterface
        fun fail(code: Int) {
            close()
            toast(ctx, if (code == 0) "下载失败：网络错误" else "下载失败（$code）")
        }

        private fun close() {
            runCatching { out?.close() }
            out = null
            runCatching { tmp?.takeIf { it.exists() }?.delete() }
            tmp = null
        }
    }
}
