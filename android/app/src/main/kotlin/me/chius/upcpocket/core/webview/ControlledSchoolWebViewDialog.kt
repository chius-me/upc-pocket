package me.chius.upcpocket.core.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.net.URI
import java.util.Locale
import me.chius.upcpocket.core.model.SchoolAccountState

/**
 * Clears all Android WebView cookies synchronously queued onto [CookieManager].
 */
fun clearSchoolWebViewCookies() {
    runCatching {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }
}

/**
 * Single shared controlled WebView modal used when CAS requires captcha or second-factor verification.
 *
 * Security invariants:
 * - Entry URL and every subsequent navigation are checked against [ControlledWebViewContract].
 * - SSL errors unconditionally cancel the load (never bypassed).
 * - Session invalidates and closes immediately if the active account or generation changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlledSchoolWebViewDialog(
    sessionContext: ControlledWebViewContract.SessionContext,
    accountState: SchoolAccountState,
    scopeHosts: Set<String>,
    targetCookieHost: String,
    completionPathPrefixes: Set<String>,
    onCookiesCaptured: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val latestOnCookiesCaptured by rememberUpdatedState(onCookiesCaptured)
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var capturedCookiesSent by remember { mutableStateOf(false) }

    LaunchedEffect(accountState, sessionContext) {
        if (!sessionContext.isStillValidFor(accountState)) {
            clearSchoolWebViewCookies()
            latestOnDismiss()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = {
            clearSchoolWebViewCookies()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("学校安全验证", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "仅允许官方 HTTPS 域名 · 密码不在客户端重放",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                clearSchoolWebViewCookies()
                                onDismiss()
                            },
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                errorMessage = null
                                capturedCookiesSent = false
                                if (ControlledWebViewContract.inspectEntryUrl(
                                        sessionContext.entryUrl,
                                        scopeHosts = scopeHosts,
                                    ) == ControlledWebViewContract.Decision.ALLOW
                                ) {
                                    webViewRef?.loadUrl(sessionContext.entryUrl)
                                } else {
                                    errorMessage = "验证入口地址未通过安全校验"
                                }
                            },
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                errorMessage?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.removeAllCookies(null)
                        cookieManager.flush()

                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            configureHardenedSettings(settings)
                            cookieManager.setAcceptThirdPartyCookies(this, false)

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    val url = request.url?.toString().orEmpty()
                                    val method = request.method ?: "GET"
                                    val decision = ControlledWebViewContract.inspectNavigation(
                                        rawUrl = url,
                                        method = method,
                                        isMainFrame = request.isForMainFrame,
                                        scopeHosts = scopeHosts,
                                    )
                                    if (decision == ControlledWebViewContract.Decision.BLOCK) {
                                        if (request.isForMainFrame) {
                                            isLoading = false
                                            errorMessage = "已阻止打开未允许的跳转地址"
                                        }
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(
                                    view: WebView,
                                    url: String,
                                    favicon: Bitmap?,
                                ) {
                                    isLoading = true
                                    errorMessage = null
                                }

                                override fun onPageCommitVisible(view: WebView, url: String) {
                                    view.evaluateJavascript(CAS_VIEWPORT_REPAIR_JS, null)
                                }

                                override fun doUpdateVisitedHistory(
                                    view: WebView,
                                    url: String,
                                    isReload: Boolean,
                                ) {
                                    view.evaluateJavascript(CAS_VIEWPORT_REPAIR_JS, null)
                                    tryExtractCompletionCookies(url)
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    isLoading = false
                                    CookieManager.getInstance().flush()
                                    view.evaluateJavascript(CAS_VIEWPORT_REPAIR_JS, null)
                                    tryExtractCompletionCookies(url)
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (request.isForMainFrame) {
                                        isLoading = false
                                        errorMessage = "页面加载失败（错误码 ${error.errorCode}），请点击右上角重试"
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView,
                                    handler: SslErrorHandler,
                                    error: SslError,
                                ) {
                                    // Unconditionally reject untrusted or mismatched certificates.
                                    handler.cancel()
                                    isLoading = false
                                    errorMessage = "学校页面 TLS 证书校验失败，已终止加载"
                                }

                                private fun tryExtractCompletionCookies(rawUrl: String) {
                                    if (capturedCookiesSent) return
                                    val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return
                                    if (!uri.scheme.equals("https", ignoreCase = true)) return
                                    val host = uri.host?.lowercase(Locale.ROOT) ?: return
                                    if (host != targetCookieHost.lowercase(Locale.ROOT)) return
                                    val path = uri.rawPath.orEmpty()
                                    if (completionPathPrefixes.none { path.startsWith(it) }) return

                                    val rawCookie = CookieManager.getInstance()
                                        .getCookie("https://$host/")
                                    val parsed = ControlledWebViewContract.parseCookieHeader(rawCookie)
                                    if (parsed.isNotEmpty()) {
                                        capturedCookiesSent = true
                                        latestOnCookiesCaptured(parsed)
                                    }
                                }
                            }

                            webViewRef = this
                            if (ControlledWebViewContract.inspectEntryUrl(
                                    sessionContext.entryUrl,
                                    scopeHosts = scopeHosts,
                                ) == ControlledWebViewContract.Decision.ALLOW
                            ) {
                                loadUrl(sessionContext.entryUrl)
                            } else {
                                isLoading = false
                                errorMessage = "验证入口地址未通过安全校验"
                            }
                        }
                    },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureHardenedSettings(settings: WebSettings) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setSupportMultipleWindows(false)
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
}

/**
 * Repairs known `#loginIframe` height=0 collapse on CAS mobile WebView while strictly restricting
 * execution to the exact `https://cas.upc.edu.cn/cas/login` document and never touching inputs.
 */
private const val CAS_VIEWPORT_REPAIR_JS = """
(function () {
  if (location.origin !== 'https://cas.upc.edu.cn' || location.pathname !== '/cas/login') return;
  if (window.__upcPocketCasResize) { window.__upcPocketCasResize(); return; }
  function repair() {
    var f = document.getElementById('loginIframe');
    var h = Math.round(window.innerHeight);
    if (!f || h < 100 || getComputedStyle(f).display === 'none') return;
    if (f.getBoundingClientRect().height >= 16 && f.dataset.upcPocketAutoHeight !== '1') return;
    f.dataset.upcPocketAutoHeight = '1';
    document.documentElement.style.minHeight = h + 'px';
    document.body.style.minHeight = h + 'px';
    f.style.setProperty('height', h + 'px', 'important');
    f.style.setProperty('min-height', h + 'px', 'important');
    f.style.width = '100%';
  }
  window.__upcPocketCasResize = repair;
  window.addEventListener('resize', repair);
  repair();
  setTimeout(repair, 250);
  setTimeout(repair, 1000);
})();
"""
