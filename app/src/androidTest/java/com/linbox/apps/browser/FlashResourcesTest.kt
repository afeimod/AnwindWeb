package com.linbox.apps.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlashResourcesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun request(url: String, mainFrame: Boolean = true) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test
    fun directSwfPreservesDirectoryAndQuery() {
        val response = FlashResources(context).intercept(request("https://example.com/games/wing/main.swf?token=abc"))!!
        assertEquals("text/html", response.mimeType)
        val html = response.data.bufferedReader().use { it.readText() }
        assertTrue(html.contains("main.swf?token=abc"))
        assertTrue(html.replace("\\/", "/").contains("https://example.com/games/wing/"))
        assertFalse(html.contains("\"base\":\"https://example.com/\""))
    }

    @Test
    fun swfUrlCannotInjectPlayerScript() {
        val response = FlashResources(context).intercept(request("https://example.com/a.swf?x=</script><script>evil()"))!!
        val html = response.data.bufferedReader().use { it.readText() }
        assertFalse(html.contains("</script><script>evil()"))
        assertTrue(html.contains("\\u003c"))
    }

    @Test
    fun localMagicDetectedMovieCanOpenWithoutSwfExtension() {
        val uri = Uri.parse("https://linbox.local/local-flash/token/movie?linboxSwf=1")
        assertTrue(FlashResources.isSwf(uri))
        assertFalse(FlashResources.isSwf(Uri.parse("https://example.com/movie?linboxSwf=1")))
    }

    @Test
    fun localEngineBootstrapIsServedFromAssets() {
        val response = FlashResources(context).intercept(request("https://linbox.local/flash-bootstrap.js", false))!!
        assertEquals("text/javascript", response.mimeType)
        val script = response.data.bufferedReader().use { it.readText() }
        assertTrue(script.contains("favorFlash: false"))
    }

    @Test
    fun localMappingRejectsTraversalAndUnknownSessions() {
        val unknown = LocalFlashStore.serve(context, Uri.parse("https://linbox.local/local-flash/missing/game.swf"))
        assertNotNull(unknown)
        assertEquals(404, unknown!!.statusCode)
        for (path in listOf("%2e%2e/secret", "token/a%2fb", "token/a%5cb")) {
            val rejected = LocalFlashStore.serve(context, Uri.parse("https://linbox.local/local-flash/$path"))!!
            assertEquals(403, rejected.statusCode)
        }
    }
}
