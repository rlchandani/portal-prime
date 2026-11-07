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
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Observes the embedded YouTube Music page and drives it. A WebView publishes no Android media
 * session, so `assets/ytmusic/bridge.js` reports the page's media state here; we validate it with
 * [YtMusicBridgeParser] and publish into [NowPlayingHub] as [SOURCE_EMBEDDED_YT_MUSIC]. Transport
 * commands are fixed scripts evaluated in the page. Main thread only, except [Sink.postMessage].
 */
internal class YtMusicBridge(private val webView: WebView) : MusicTransport {
  private companion object {
    const val TAG = "ImmortalYtMusicBridge"
    const val SINK_NAME = "portalYtMusic"
    const val DIAGNOSTIC_INTERVAL_MS = 15_000L
    const val ASSET = "ytmusic/bridge.js"
    /** Kotlin-side floor between handled messages; the script already coalesces. */
    const val MIN_MESSAGE_INTERVAL_MS = 150L
    val ALLOWED_ORIGINS = setOf(YtMusicUrls.ORIGIN)
  }

  private val main = Handler(Looper.getMainLooper())
  private var script: String? = null
  private var documentStart: ScriptHandler? = null
  private var last: NowPlayingState? = null
  private var lastHandledAt = 0L
  private var pendingRaw: String? = null
  private var released = false
  /** Top-level URL, tracked for the fallback interface (which cannot see the caller's origin). */
  @Volatile private var mainFrameUrl: String? = null
  private val drainPending = Runnable { pendingRaw?.let { pendingRaw = null; handle(it) } }

  /** Latest validated page state (null when nothing is loaded or playing). */
  val state: NowPlayingState? get() = last

  @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
  @MainThread
  fun install(context: Context) {
    val source = runCatching {
      context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }.onFailure { Log.w(TAG, "bridge script unavailable", it) }.getOrNull() ?: return
    script = source
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
      // Origin-scoped channel: only the music.youtube.com main frame can reach us.
      WebViewCompat.addWebMessageListener(webView, SINK_NAME, ALLOWED_ORIGINS) { _, message, origin, isMainFrame, _ ->
        if (isMainFrame && origin.toString().trimEnd('/') == YtMusicUrls.ORIGIN) onMessage(message.data)
      }
    } else {
      webView.addJavascriptInterface(Sink(), SINK_NAME)
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      // Early injection lets the script record the page's own media-session handlers.
      documentStart = runCatching {
        WebViewCompat.addDocumentStartJavaScript(webView, source, ALLOWED_ORIGINS)
      }.onFailure { Log.w(TAG, "document-start injection unavailable", it) }.getOrNull()
    }
    Log.i(TAG, "installed listener=${WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)} " +
        "documentStart=${documentStart != null}")
  }

  /** Called on every top-level navigation (including in-app history changes). */
  @MainThread
  fun onNavigated(url: String?) {
    mainFrameUrl = url
  }

  /** Late injection for page loads; the script is idempotent and self-checks its origin. */
  @MainThread
  fun onPageFinished(url: String?) {
    mainFrameUrl = url
    val source = script ?: return
    if (!released && YtMusicUrls.isMusicPage(url)) webView.evaluateJavascript(source, null)
  }

  /** In-page navigation for a tab; [result] is true only when the page handled it itself. */
  @MainThread
  fun go(tab: YtMusicTab, result: (Boolean) -> Unit) {
    if (released || !YtMusicUrls.isMusicPage(webView.url)) return result(false)
    webView.evaluateJavascript(
        "window.__portalYtm?window.__portalYtm.go('${tab.pageKey}'):false;") { raw ->
      result(!released && raw == "true")
    }
  }

  override fun play() = send(YtMusicCommand.PLAY)
  override fun pause() = send(YtMusicCommand.PAUSE)
  override fun next() = send(YtMusicCommand.NEXT)
  override fun previous() = send(YtMusicCommand.PREVIOUS)
  override fun seekTo(positionMs: Long) = send(YtMusicCommand.SEEK, positionMs)

  private fun send(command: YtMusicCommand, positionMs: Long = 0L) {
    val js = YtMusicBridgeParser.commandScript(command, positionMs)
    runOnMain {
      if (!released && YtMusicUrls.isMusicPage(webView.url)) webView.evaluateJavascript(js, null)
    }
  }

  @MainThread
  fun release() {
    released = true
    main.removeCallbacks(drainPending)
    pendingRaw = null
    documentStart?.let { runCatching { it.remove() } }
    documentStart = null
    if (MusicTransports.embedded === this) MusicTransports.embedded = null
    clearPublished()
    last = null
  }

  private fun onMessage(raw: String?) {
    if (raw == null || raw.length > YtMusicBridgeParser.MAX_MESSAGE_CHARS) return
    runOnMain {
      if (released) return@runOnMain
      val wait = lastHandledAt + MIN_MESSAGE_INTERVAL_MS - SystemClock.elapsedRealtime()
      if (wait > 0) {
        // Keep only the newest message; the drain runs once the floor has elapsed.
        if (pendingRaw == null) main.postDelayed(drainPending, wait)
        pendingRaw = raw
      } else handle(raw)
    }
  }

  private var lastDiagnosticAt = -DIAGNOSTIC_INTERVAL_MS

  private fun handle(raw: String) {
    if (released) return
    val now = SystemClock.elapsedRealtime()
    lastHandledAt = now
    val next = YtMusicBridgeParser.parse(raw, now)
    // Rate-limited field diagnostics: shows whether page messages arrive and how they parse,
    // without logging track text (only lengths/flags).
    if (now - lastDiagnosticAt > DIAGNOSTIC_INTERVAL_MS) {
      lastDiagnosticAt = now
      Log.i(TAG, "message chars=${raw.length} parsed=${next != null} active=${next?.active} " +
          "state=${next?.state} titleChars=${next?.title?.length ?: 0} sample=${raw.take(160).replace(Regex("\"(t|title|a|artist|al|album)\":\"[^\"]*\""), "\"$1\":\"…\"")}")
    }
    if (next == null) {
      if (last != null) { last = null; clearPublished() }
      return
    }
    // Re-assert when another source cleared the hub while the page was still playing.
    val hubLost = NowPlayingHub.current == null && next.active
    if (YtMusicBridgeParser.shouldPublish(last, next, now) || hubLost) {
      last = next
      NowPlayingHub.publish(next)
    }
  }

  /** Only clear the hub when it still shows this page; never wipe another app's session. */
  private fun clearPublished() {
    if (NowPlayingHub.current?.source == SOURCE_EMBEDDED_YT_MUSIC) NowPlayingHub.publish(null)
  }

  private inline fun runOnMain(crossinline block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
  }

  /** Fallback channel for WebViews without WebMessageListener; gated on the top-level origin. */
  private inner class Sink {
    @JavascriptInterface
    fun postMessage(message: String?) {
      if (YtMusicUrls.isMusicPage(mainFrameUrl)) onMessage(message)
    }
  }
}
