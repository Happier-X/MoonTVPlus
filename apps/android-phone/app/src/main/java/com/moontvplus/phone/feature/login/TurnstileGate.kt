package com.moontvplus.phone.feature.login

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Cloudflare Turnstile 挂件。
 *
 * 必须用 `loadDataWithBaseURL(站点 origin, ...)` 注入：`data:` URL 的 origin 是 opaque 的，
 * Turnstile 的域名校验会直接失败。这里不做站点导航，只跑挂件脚本。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TurnstileGate(
    siteKey: String,
    origin: String,
    resetKey: Int,
    onToken: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val html = remember(siteKey) { turnstileHtml(siteKey) }

    key(resetKey) {
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .height(84.dp),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onToken(token: String?) {
                                onToken(token?.takeIf { it.isNotBlank() })
                            }
                        },
                        JS_BRIDGE,
                    )
                    loadDataWithBaseURL(origin, html, "text/html", "utf-8", null)
                }
            },
        )
    }
}

private const val JS_BRIDGE = "MoonTvTurnstile"

private fun turnstileHtml(siteKey: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<style>
  html,body { margin:0; padding:0; background:transparent; }
  #cf { display:flex; justify-content:center; padding:8px 0; }
</style>
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit" async defer></script>
</head>
<body>
<div id="cf"></div>
<script>
  function boot() {
    if (!window.turnstile) { setTimeout(boot, 120); return; }
    try {
      window.turnstile.render('#cf', {
        sitekey: '$siteKey',
        callback: function (t) { $JS_BRIDGE.onToken(t); },
        'expired-callback': function () { $JS_BRIDGE.onToken(''); },
        'error-callback': function () { $JS_BRIDGE.onToken(''); }
      });
    } catch (e) {
      $JS_BRIDGE.onToken('');
    }
  }
  boot();
</script>
</body>
</html>
""".trimIndent()
