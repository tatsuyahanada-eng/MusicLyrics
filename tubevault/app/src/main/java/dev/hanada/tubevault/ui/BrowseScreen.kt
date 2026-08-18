package dev.hanada.tubevault.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hanada.tubevault.core.YouTubeUrls

/**
 * The in-app YouTube browser.
 *
 * It earns its place twice over: it is the natural way to find something to
 * download, and its cookie jar is what keeps yt-dlp past YouTube's
 * "Please sign in" checks.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentUrl by viewModel.currentUrl.collectAsStateWithLifecycle()
    val pageTitle by viewModel.pageTitle.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()

    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableStateOf(0) }
    var askingDownload by remember { mutableStateOf(false) }

    // Google's sign-in flow sometimes opens as a JS popup (target="_blank")
    // rather than a normal navigation. Without handling that, tapping
    // "ログイン" silently does nothing. The popup shares the same cookie jar
    // as the main WebView, so anything it signs in with still reaches
    // yt-dlp once captureCookies() runs.
    var popupWebView by remember { mutableStateOf<WebView?>(null) }
    var popupTitle by remember { mutableStateOf("") }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            // The stock WebView UA contains "wv", which Google's sign-in flow
            // rejects outright; a plain Chrome UA at least gets a chance.
            settings.userAgentString = MOBILE_USER_AGENT

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val target = request.url.toString()
                    // Swallow youtube:// and intent:// links so "open in app"
                    // buttons do not bounce the user out of here.
                    return !(target.startsWith("http://") || target.startsWith("https://"))
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    viewModel.onNavigated(url, view?.title)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    viewModel.onNavigated(url, view?.title)
                    viewModel.captureCookies()
                    canGoBack = view?.canGoBack() == true
                    canGoForward = view?.canGoForward() == true
                }

                /** YouTube is a single-page app, so this is the reliable hook. */
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    viewModel.onNavigated(url, view?.title)
                    canGoBack = view?.canGoBack() == true
                    canGoForward = view?.canGoForward() == true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loadProgress = newProgress
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    viewModel.onTitle(title)
                }

                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message,
                ): Boolean {
                    val popup = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = MOBILE_USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(popupView: WebView?, url: String?) {
                                viewModel.captureCookies()
                                popupTitle = popupView?.title.orEmpty()
                            }
                        }
                    }
                    popupWebView = popup
                    (resultMsg.obj as WebView.WebViewTransport).webView = popup
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView) {
                    popupWebView = null
                }
            }

            val restored = viewModel.consumeSavedState()
            if (restored == null || restoreState(restored) == null) {
                loadUrl(YouTubeUrls.MOBILE_HOME)
            }
        }
    }

    BackHandler(enabled = canGoBack) { webView.goBack() }

    DisposableEffect(Unit) {
        onDispose { viewModel.captureCookies() }
    }

    val videoId = YouTubeUrls.videoId(currentUrl)

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { webView.loadUrl(YouTubeUrls.MOBILE_HOME) }) {
                Icon(Icons.Default.Home, contentDescription = "YouTube のホーム")
            }
            IconButton(onClick = { webView.goBack() }, enabled = canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
            }
            IconButton(onClick = { webView.goForward() }, enabled = canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "進む")
            }
            Text(
                text = pageTitle.ifBlank { "YouTube" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconButton(onClick = { webView.loadUrl(GOOGLE_SIGN_IN_URL) }) {
                Icon(Icons.Default.Login, contentDescription = "Google にログイン")
            }
            IconButton(onClick = { webView.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "再読み込み")
            }
        }

        if (loadProgress in 1..99) {
            LinearProgressIndicator(
                progress = { loadProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view ->
                    val bundle = Bundle()
                    view.saveState(bundle)
                    viewModel.saveState(bundle)
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                },
            )

            if (videoId != null) {
                ExtendedFloatingActionButton(
                    onClick = { askingDownload = true },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    text = { Text("この動画を保存") },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
        }
    }

    if (askingDownload && videoId != null) {
        val title = YouTubeUrls.cleanPageTitle(pageTitle)
        DownloadOptionsDialog(
            title = title,
            categories = categories,
            initialKind = appSettings.defaultKind,
            initialQuality = appSettings.defaultQuality,
            initialCategoryId = appSettings.defaultCategoryId,
            onDismiss = { askingDownload = false },
            onConfirm = { kind, quality, categoryId ->
                viewModel.download(videoId, title, kind, quality, categoryId)
                askingDownload = false
            },
        )
    }

    val popup = popupWebView
    if (popup != null) {
        Dialog(
            onDismissRequest = {
                viewModel.captureCookies()
                popupWebView = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = {
                            Text(
                                popupTitle.ifBlank { "ログイン" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                viewModel.captureCookies()
                                popupWebView = null
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "閉じる")
                            }
                        },
                    )
                    AndroidView(
                        factory = { popup },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Mobile Safari/537.36"

private const val GOOGLE_SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https://m.youtube.com/"
