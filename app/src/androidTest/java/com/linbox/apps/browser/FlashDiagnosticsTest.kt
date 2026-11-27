package com.linbox.apps.browser

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FlashDiagnosticsTest {
    private val source = "https://linbox.local/ruffle/ruffle.js"

    @Test
    fun only4399FlashSessionsProxyTheirAssociatedResourceSubdomains() {
        assertFalse(FlashResources.activates4399FlashNavigation("https://web.4399.com/stat/togame.php?target=mhfx"))
        assertTrue(FlashResources.activates4399FlashNavigation("https://mhfx2670.me4399.com/game/main.swf"))
        assertFalse(FlashResources.activates4399FlashNavigation("https://mhfx2670.me4399.com/game/index.html"))
        assertTrue(FlashResources.activates4399Proxy("mhfx2670.me4399.com", null))
        assertTrue(FlashResources.activates4399Proxy("cdn.example.net", "www.4399.com"))
        assertFalse(FlashResources.activates4399Proxy("assets.example.com", "site.example.com"))
        assertTrue(FlashResources.shouldProxy4399Subresource("mhxx-res.my4399.com", true))
        assertTrue(FlashResources.shouldProxy4399Subresource("s15.4399.com", true))
        assertTrue(FlashResources.shouldProxy4399Subresource("cdn.4399pk.com", true))
        assertFalse(FlashResources.shouldProxy4399Subresource("assets.example.com", true))
        assertFalse(FlashResources.shouldProxy4399Subresource("mhxx-res.my4399.com", false))
        assertFalse(FlashResources.is4399Host("not4399.com"))
    }

    @Test
    fun everyBrowserModeBootstrapKeepsFlashLoaderAndUsesRealLineBreaks() {
        val loader = "window.__linboxLoadRuffle=function(){};"
        val default = BrowserEngine.composeBrowserBootstrap("default", loader)
        val speed = BrowserEngine.composeBrowserBootstrap("speed", loader)
        val compatibility = BrowserEngine.composeBrowserBootstrap("compatibility", loader)
        assertEquals(loader, default)
        assertTrue(speed.contains(loader))
        assertTrue(compatibility.contains(loader))
        assertTrue(speed.contains('\n'))
        assertTrue(compatibility.contains('\n'))
        assertFalse(speed.contains("\\nwindow.__linboxLoadRuffle"))
        assertFalse(compatibility.contains("\\nwindow.__linboxLoadRuffle"))
        assertTrue(compatibility.contains("documentMode"))
    }

    @Test
    fun browserModesSelectDocumentedUserAgents() {
        assertTrue(BrowserEngine.desiredUa("mobile", "compatibility").contains("Trident/7.0; rv:11.0"))
        assertTrue(BrowserEngine.desiredUa("mobile", "speed").contains("Windows NT 10.0"))
        assertTrue(BrowserEngine.desiredUa("mobile", "default").contains("Windows NT 10.0"))
    }

    @Test
    fun actualRuffleInstanceLogExtractsRendererAndSimd() {
        val diagnostics = FlashDiagnostics()
        diagnostics.recordConsole(
            "New Ruffle instance created (Version: 0.4.1 | WebAssembly extensions: ON | Used renderer: WebGL)",
            source
        )
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("renderer=WebGL"))
        assertTrue(snapshot.contains("SIMD=ON"))
        assertFalse(snapshot.contains("Version:"))
        assertFalse(snapshot.contains("GPU未开启"))
        diagnostics.clear()
        diagnostics.recordConsole(
            "New Ruffle instance created (Version: 0.4.1 | WebAssembly extensions: OFF | Used renderer: Canvas)",
            source
        )
        assertTrue(diagnostics.snapshot().contains("renderer=Canvas；SIMD=OFF"))
        diagnostics.clear()
        diagnostics.recordConsole(
            "New Ruffle instance created (Version: 0.4.1 | WebAssembly extensions: ON | Used renderer: wgpu-webgl)", source
        )
        assertTrue(diagnostics.snapshot().contains("renderer=WGPU WebGL；SIMD=ON"))
    }

    @Test
    fun unknownRendererCannotLeakArbitraryText() {
        val diagnostics = FlashDiagnostics()
        diagnostics.recordConsole(
            "New Ruffle instance created (Version: secretVersion | WebAssembly extensions: ON | Used renderer: secretToken)",
            source
        )
        assertTrue(diagnostics.snapshot().contains("renderer=未识别"))
        assertFalse(diagnostics.snapshot().contains("secret"))
    }

    @Test
    fun bootstrapNetworkFailureLogKeepsOnlyWhitelistedRequestFields() {
        val diagnostics = FlashDiagnostics()
        diagnostics.recordConsole(
            "[LinBoxResource] host=assets.example ext=pack method=GET status=0 cors=possible",
            "https://linbox.local/flash-bootstrap.js"
        )
        val result = diagnostics.snapshot()
        assertTrue(result.contains("host=assets.example"))
        assertTrue(result.contains("文件类型=.pack"))
        assertTrue(result.contains("方法=GET"))
        assertTrue(result.contains("HTTP=0"))
        assertTrue(result.contains("CORS可能"))
        assertFalse(result.contains("query"))
    }

    @Test
    fun corsConsoleLineShowsRequestHostWithoutQueryData() {
        val diagnostics = FlashDiagnostics()
        diagnostics.recordConsole(
            "Access to fetch at 'https://assets.example/private.pack?token=secretKey' from origin 'https://game.example' has been blocked by CORS policy: Response to preflight request doesn't pass access control check: No 'Access-Control-Allow-Origin' header is present.",
            "https://linbox.local/flash-bootstrap.js"
        )
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("CORS失败资源：host=assets.example；文件类型=.pack"))
        assertTrue(snapshot.contains("CORS响应缺少：Access-Control-Allow-Origin"))
        assertFalse(snapshot.contains("secretKey"))
        assertFalse(snapshot.contains("token="))
        assertFalse(snapshot.contains("https://assets.example"))
    }

    @Test
    fun corsDiagnosticsPreservePreflightAndResourceFailureCategories() {
        val diagnostics = FlashDiagnostics()
        diagnostics.recordResource(Uri.parse("https://assets.example/game.pack?token=secret"), 403, "预检")
        diagnostics.recordResource(Uri.parse("https://assets.example/game.pack?secret=1"), 206, "Range")
        val result = diagnostics.snapshot()
        assertTrue(result.contains("host=assets.example"))
        assertTrue(result.contains("HTTP=403"))
        assertTrue(result.contains("预检"))
        assertTrue(result.contains("Range"))
        assertTrue(result.contains("HTTP=206"))
        assertTrue(result.contains("HTTP=403"))
        assertFalse(result.contains("方法=OPTIONS"))
        diagnostics.recordResource(Uri.parse("https://assets.example/play.swf#frag"), 403, "预检失败", method = "OPTIONS")
        assertTrue(diagnostics.snapshot().contains("方法=OPTIONS"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("token"))
    }

    @Test
    fun logsOnlyKeepCategoriesWithoutTokensOrTrace() {
        val diagnostics = FlashDiagnostics()
        diagnostics.recordConsole("SWF trace: player secretTrace token=secretToken", source)
        assertTrue(diagnostics.snapshot().contains("请等待 SWF 加载"))
        diagnostics.recordConsole(
            "ERROR Socket Stage3D Context3D AVM2 unimplemented API fetch CORS WASM panic " +
                "https://assets.example/private.pack?token=secretToken cookie=secretCookie auth=secretAuth key=secretKey",
            "https://game.example/frame.html?token=secretSource"
        )
        val snapshot = diagnostics.snapshot()
        for (category in listOf("Socket", "Stage3D", "Context3D", "AVM2", "未实现API", "fetch", "CORS", "WASM", "panic")) {
            assertTrue(snapshot.contains("日志分类：$category"))
        }
        assertTrue(snapshot.contains("CORS失败资源：host=assets.example；文件类型=.pack"))
        assertTrue(snapshot.contains("CORS日志来源：host=game.example"))
        for (raw in listOf("secret", "token=", "cookie=", "auth=", "key=", "https://", "SWF trace", "ERROR")) {
            assertFalse(snapshot.contains(raw))
        }
    }

    @Test
    fun rejectsConsoleSourcesOutsideLocalRuffleDirectory() {
        val diagnostics = FlashDiagnostics()
        for (untrusted in listOf(
            "https://example.com/ruffle/ruffle.js",
            "http://linbox.local/ruffle/ruffle.js",
            "https://linbox.local/ruffle-other/ruffle.js",
            "https://linbox.local/flash-bootstrap.js",
            "https://linbox.local.evil/ruffle/ruffle.js",
            "https://user:password@linbox.local/ruffle/ruffle.js",
            "https://linbox.local:8443/ruffle/ruffle.js",
            ""
        )) {
            diagnostics.recordConsole("ERROR panic secretToken", untrusted)
        }
        assertTrue(diagnostics.snapshot().contains("请等待 SWF 加载"))
    }

    @Test
    fun routineConsoleMessagesAreIgnored() {
        val diagnostics = FlashDiagnostics()
        diagnostics.recordConsole("Loading WASM and AVM2 with Stage3D Context3D Socket fetch CORS", source)
        assertTrue(diagnostics.snapshot().contains("请等待 SWF 加载"))
    }

    @Test
    fun duplicateCategoriesAndResourcesAreStoredOnce() {
        val diagnostics = FlashDiagnostics()
        repeat(100) {
            diagnostics.recordConsole("WARN unsupported Socket API token=$it", source)
            diagnostics.recordResource(Uri.parse("https://example.com/$it.swf?token=$it"), 404)
        }
        val lines = diagnosticLines(diagnostics)
        assertEquals(3, lines.size)
        assertEquals(1, lines.count { it == "日志分类：Socket" })
        assertEquals(1, lines.count { it == "日志分类：未实现API" })
        assertEquals(1, lines.count { it == "资源：host=example.com；HTTP=404" })
    }

    @Test
    fun ringRetainsOnlyNewestFortyUniqueEntries() {
        val diagnostics = FlashDiagnostics()
        repeat(45) { index ->
            diagnostics.recordResource(Uri.parse("https://host$index.example/game.swf"), 500)
        }
        val lines = diagnosticLines(diagnostics)
        assertEquals(40, lines.size)
        assertEquals("资源：host=host5.example；HTTP=500", lines.first())
        assertEquals("资源：host=host44.example；HTTP=500", lines.last())
        diagnostics.recordResource(Uri.parse("https://host0.example/game.swf"), 500)
        val afterEviction = diagnosticLines(diagnostics)
        assertEquals(40, afterEviction.size)
        assertEquals("资源：host=host6.example；HTTP=500", afterEviction.first())
        assertEquals("资源：host=host0.example；HTTP=500", afterEviction.last())
    }

    @Test
    fun resourcesKeepOnlyHostStatusAndSafeExceptionClass() {
        val diagnostics = FlashDiagnostics()
        val uri = Uri.parse("https://user:secretPassword@example.com/private/secretPath.swf?token=secretToken&auth=secretAuth&key=secretKey#secretFragment")
        diagnostics.recordResource(uri, 200)
        diagnostics.recordResource(uri)
        assertTrue(diagnostics.snapshot().contains("请等待 SWF 加载"))
        diagnostics.recordResource(uri, 403)
        diagnostics.recordResource(uri, errorType = "java.net.SocketTimeoutException")
        diagnostics.recordResource(uri, errorType = "IOException: cookie=secretCookie")
        diagnostics.recordResource(uri, errorType = "secretTokenException")
        val snapshot = diagnostics.snapshot()
        assertTrue(snapshot.contains("资源：host=example.com；HTTP=403"))
        assertTrue(snapshot.contains("资源：host=example.com；异常=SocketTimeoutException"))
        assertTrue(snapshot.contains("资源：host=example.com；异常=其他异常"))
        for (raw in listOf("secret", "user", "private", "?", "#", "cookie=", "auth=", "key=")) {
            assertFalse(snapshot.contains(raw))
        }
    }

    @Test
    fun clearResetsEntriesAndDeduplication() {
        val diagnostics = FlashDiagnostics()
        diagnostics.recordConsole("ERROR panic", source)
        diagnostics.clear()
        assertTrue(diagnostics.snapshot().contains("请等待 SWF 加载"))
        diagnostics.recordConsole("ERROR panic", source)
        assertEquals(listOf("日志分类：panic"), diagnosticLines(diagnostics))
    }

    @Test
    fun concurrentWritesSnapshotsAndClearsPreserveBounds() {
        val diagnostics = FlashDiagnostics()
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 4).map { worker ->
                executor.submit {
                    start.await()
                    repeat(100) { index ->
                        if (worker == 0 && index % 10 == 0) diagnostics.clear()
                        diagnostics.recordConsole("ERROR panic", source)
                        diagnostics.recordResource(Uri.parse("https://host$worker-$index.example/movie"), 500)
                        val lines = diagnosticLines(diagnostics)
                        assertTrue(lines.size <= 40)
                        assertEquals(lines.size, lines.toSet().size)
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            val lines = diagnosticLines(diagnostics)
            assertTrue(lines.size <= 40)
            assertEquals(lines.size, lines.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun diagnosticLines(diagnostics: FlashDiagnostics): List<String> =
        diagnostics.snapshot().lineSequence().filter {
            it.startsWith("引擎：") || it.startsWith("日志分类：") || it.startsWith("资源：")
        }.toList()
}
