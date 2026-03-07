package com.phoncoin.wallet.feature.explorer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.theme.PhonColors

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ExplorerScreen(
    url: String,
    onBack: () -> Unit
) {
    val tBack = stringResource(R.string.back)
    val tRefresh = stringResource(R.string.refresh)

    var progress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    // Keep a reference + force reload when the URL changes
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    var lastLoadedUrl by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = true) {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Surface(color = PhonColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    border = BorderStroke(1.dp, PhonColors.Border),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(tBack) }

                OutlinedButton(
                    onClick = { webViewRef.value?.reload() },
                    border = BorderStroke(1.dp, PhonColors.Border),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(tRefresh) }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(0, 100) / 100f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    color = PhonColors.Accent,
                    trackColor = PhonColors.Border
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                border = BorderStroke(1.dp, PhonColors.Border)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef.value = this

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                    isLoading = newProgress < 100
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    progress = 0
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                }
                            }

                            // premier load
                            lastLoadedUrl = url.trim()
                            loadUrl(lastLoadedUrl!!)
                        }
                    },
                    update = { wv ->
                        // Fix: when the URL changes (for example after clicking a TX), force the load
                        val target = url.trim()
                        val current = (lastLoadedUrl ?: wv.url ?: "").trim()

                        if (target.isNotEmpty() && target != current) {
                            lastLoadedUrl = target
                            try { wv.stopLoading() } catch (_: Throwable) {}
                            wv.loadUrl(target)
                        }
                    }
                )
            }
        }
    }
}
