package com.moontvplus.phone.feature.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.moontvplus.ui.theme.MoonTvTheme

/**
 * 站内网页版：直播、观影室等尚未原生的页面统一走这里。
 *
 * 登录态由 AndroidSiteCookies 写进 WebView 的 CookieManager，
 * 打开前宿主会补写当前 token，避免原生刷新过 token 后网页仍拿旧值。
 */
class WebViewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (!isWebUrl(url)) {
            finish()
            return
        }

        setContent {
            MoonTvTheme(darkTheme = isSystemInDarkTheme()) {
                WebPageScreen(url = url, fallbackTitle = title, onClose = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, url: String, title: String): Intent =
            Intent(context, WebViewActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)

        internal fun isWebUrl(url: String): Boolean {
            val parsed = Uri.parse(url)
            return parsed.scheme == "http" || parsed.scheme == "https"
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPageScreen(
    url: String,
    fallbackTitle: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var pageTitle by remember { mutableStateOf(fallbackTitle) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            val web = this
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(web, true)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") return false
                    openExternal(context, request.url)
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    loading = true
                    canGoBack = view.canGoBack()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    canGoBack = view.canGoBack()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (!title.isNullOrBlank()) pageTitle = title
                }
            }
        }
    }

    DisposableEffect(webView) {
        webView.loadUrl(url)
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onClose()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (webView.canGoBack()) webView.goBack() else onClose() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = pageTitle.ifBlank { fallbackTitle },
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { webView.reload() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
                IconButton(onClick = { openExternal(context, Uri.parse(webView.url ?: url)) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "在浏览器打开")
                }
            }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun openExternal(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
