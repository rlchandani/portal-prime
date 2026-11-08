/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Resolves a public playlist's video ids through the official IFrame API ([YtPlaylistPage]) in a
 * short-lived, off-screen WebView that never plays and is destroyed as soon as it answers.
 * Used only when no YouTube account is signed in on this Portal.
 */
internal object YtPlaylistResolver {
  private const val TAG = "CalderMusicShare"
  private const val TIMEOUT_MS = 15_000L
  private const val SIZE_PX = 320

  /** The ids (≤ [MusicShareQueue.MAX_TRACKS]), or null when the list can't be opened in time. */
  suspend fun resolve(context: Context, listId: String): List<String>? = withContext(Dispatchers.Main) {
    withTimeoutOrNull(TIMEOUT_MS) { cueAndRead(context.applicationContext, listId) }
  }

  @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
  private suspend fun cueAndRead(app: Context, listId: String): List<String>? = suspendCancellableCoroutine { cont ->
    val origin = YtPlayerPage.baseUrl(app.packageName)
    val html = YtPlaylistPage.html(origin, listId)
    if (html == null) { cont.resume(null); return@suspendCancellableCoroutine }
    val main = Handler(Looper.getMainLooper())
    val web = WebView(app)
    var finished = false
    fun finish(ids: List<String>?) {
      if (finished) return
      finished = true
      destroy(web)
      if (cont.isActive) cont.resume(ids)
    }
    web.settings.javaScriptEnabled = true
    web.settings.allowFileAccess = false
    web.settings.allowContentAccess = false
    web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    web.settings.mediaPlaybackRequiresUserGesture = true
    web.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame
    }
    web.addJavascriptInterface(object {
      @JavascriptInterface fun postMessage(raw: String?) {
        main.post {
          when (val m = YtPlaylistPage.parse(raw)) {
            is YtPlaylistPage.Message.Ids -> finish(m.videoIds)
            is YtPlaylistPage.Message.Failed -> { Log.i(TAG, "playlist cue failed (${m.code})"); finish(null) }
            null -> Unit
          }
        }
      }
    }, YtPlaylistPage.BRIDGE_NAME)
    // Not attached to a window: give it a real layout size so the player initializes.
    web.measure(View.MeasureSpec.makeMeasureSpec(SIZE_PX, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(SIZE_PX, View.MeasureSpec.EXACTLY))
    web.layout(0, 0, SIZE_PX, SIZE_PX)
    web.loadDataWithBaseURL(origin, html, "text/html", "utf-8", null)
    cont.invokeOnCancellation { main.post { finish(null) } }
  }

  private fun destroy(web: WebView) {
    runCatching {
      web.removeJavascriptInterface(YtPlaylistPage.BRIDGE_NAME)
      web.stopLoading()
      web.destroy()
    }.onFailure { Log.w(TAG, "resolver teardown failed", it) }
  }
}
