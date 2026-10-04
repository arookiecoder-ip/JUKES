package com.example.juke.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.juke.network.AlexaRemotePolicy

/** Vendor sign-in only. Home, music browsing and playback use native Compose screens. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AlexaAccountBrowser(server: String, url: String, cookies: List<String>, onClose: (String) -> Unit) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    val manager = CookieManager.getInstance()
    fun clearCookies() {
        listOf(server, "$server/youtube-login/").forEach { scope ->
            manager.getCookie(scope).orEmpty().split(";").forEach { part ->
                val name = part.trim().substringBefore('=')
                if(name.isNotBlank()) manager.setCookie(scope, "$name=; Max-Age=0; Path=/; Secure")
            }
        }
    }
    val close = {
        val values = manager.getCookie(server).orEmpty()
        clearCookies(); onClose(values)
    }
    DisposableEffect(Unit) { onDispose { browser?.stopLoading(); browser?.destroy() } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("Account sign in", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = close) { Text("Done") }
                }
                AndroidView(modifier = Modifier.weight(1f), factory = { context ->
                    WebView(context).apply {
                        browser = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        manager.setAcceptCookie(true)
                        clearCookies()
                        cookies.forEach { manager.setCookie(server, it) }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val target = request.url.toString()
                                if (!AlexaRemotePolicy.trusted(server, target)) return true
                                // Account proxy/noVNC paths may navigate; core music routes cannot open here.
                                val path = request.url.path.orEmpty()
                                return path == "/remote/" || path in listOf("/home", "/library", "/search", "/explore")
                            }
                        }
                        loadUrl(url)
                    }
                })
            }
        }
    }
}
