package dev.hanada.tubevault.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class PoTokenException(message: String) : Exception(message)

/**
 * One offscreen WebView running YouTube's BotGuard challenge.
 *
 * A PO Token has to be minted by code Google controls, executing in something
 * that passes its runtime checks — which on Android means the system WebView.
 * Running the challenge is expensive, so a session is kept alive and reused:
 * [start] performs the attestation once, and [mint] then issues tokens from it
 * cheaply.
 */
class PoTokenWebView(private val appContext: Context) {

    private var webView: WebView? = null

    private val ready = CompletableDeferred<String>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val requestIds = AtomicLong()

    /** Runs the attestation. Returns the visitor id tokens will be bound to. */
    suspend fun start(): String {
        withContext(Dispatchers.Main) { ensureWebView() }
        return withTimeout(INIT_TIMEOUT_MS) { ready.await() }
    }

    /**
     * Issues a token for [binding], which must be the same visitor id that the
     * eventual request carries — a token bound to anything else is rejected.
     */
    suspend fun mint(binding: String): String {
        val id = requestIds.incrementAndGet().toString()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred

        withContext(Dispatchers.Main) {
            val script = "window.__mintPoToken(${JSONObject.quote(id)}, ${JSONObject.quote(binding)})"
            webView?.evaluateJavascript(script, null)
        }

        val token = try {
            withTimeout(MINT_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.remove(id)
        }

        if (token.isBlank()) throw PoTokenException("BotGuard returned no token")
        return token
    }

    fun destroy() {
        val view = webView ?: return
        webView = null
        view.post {
            runCatching {
                view.removeJavascriptInterface(BRIDGE_NAME)
                view.destroy()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun ensureWebView() {
        if (webView != null) return

        val page = try {
            val html = readAsset("potoken/potoken.html")
            val bundle = readAsset("potoken/bgutils.bundle.js")
            html.replace(BUNDLE_PLACEHOLDER, bundle)
        } catch (e: Exception) {
            ready.completeExceptionally(PoTokenException("could not read assets: ${e.message}"))
            return
        }

        webView = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkLoads = false
            settings.userAgentString = DESKTOP_USER_AGENT
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), BRIDGE_NAME)
            // The youtube.com base URL is what keeps the challenge same-origin.
            loadDataWithBaseURL(
                "https://www.youtube.com",
                page,
                "text/html",
                "utf-8",
                null,
            )
        }
    }

    private fun readAsset(path: String): String =
        appContext.assets.open(path).bufferedReader().use { it.readText() }

    private inner class Bridge {

        @JavascriptInterface
        fun onReady(visitorData: String) {
            if (visitorData.isBlank()) {
                onError("attestation produced no visitor data")
            } else {
                ready.complete(visitorData)
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            Log.w(TAG, "po_token: $message")
            if (!ready.isCompleted) {
                ready.completeExceptionally(PoTokenException(message))
            }
        }

        @JavascriptInterface
        fun onToken(requestId: String, token: String) {
            pending[requestId]?.complete(token)
        }
    }

    private companion object {
        const val TAG = "PoTokenWebView"
        const val BRIDGE_NAME = "AndroidPoToken"
        const val BUNDLE_PLACEHOLDER = "__BGUTILS_BUNDLE__"

        const val INIT_TIMEOUT_MS = 30_000L
        const val MINT_TIMEOUT_MS = 15_000L

        /** BotGuard is fussier about mobile UAs than desktop ones. */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
