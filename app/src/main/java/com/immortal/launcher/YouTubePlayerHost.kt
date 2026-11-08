/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.annotation.SuppressLint
import android.app.Application
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread

/**
 * Owns the ONE WebView that hosts the official YouTube IFrame player ([YtPlayerPage]), bridges
 * its events to Kotlin and executes commands. Created and used on the main thread only; the
 * `@JavascriptInterface` callback hops to main before touching anything.
 *
 * The WebView is built on a [MutableContextWrapper] whose base is the hosting Activity while
 * attached and the Application otherwise, so a detached player never leaks an Activity.
 * When the renderer dies the WebView is rebuilt in place (up to [MAX_RECREATES] times before the
 * player reports itself unavailable); the engine then re-cues the current item paused.
 */
internal class YouTubePlayerHost(
    private val app: Application,
    private val listener: Listener,
) {
  /** Host callbacks, always on the main thread. */
  interface Listener {
    fun onPlayerEvent(event: YtPlayerEvent)
    /** The renderer died; a fresh page is loading (or [onPlayerEvent] gets ApiUnavailable). */
    fun onPlayerLost()
    fun onSizeVerdict(verdict: YtSizeVerdict)
    /** The page tried to navigate away (e.g. the YouTube logo); never loaded in place. */
    fun onExternalLink(uri: Uri)
  }

  private companion object {
    const val TAG = "ImmortalYtPlayer"
    const val MAX_RECREATES = 2
    const val RATE_WINDOW_MS = 1_000L
    const val MAX_MESSAGES_PER_WINDOW = 30
  }

  private val main = Handler(Looper.getMainLooper())
  private val wrapper = MutableContextWrapper(app)
  private val baseUrl = YtPlayerPage.baseUrl(app.packageName)
  private var web: WebView? = null
  private var container: ViewGroup? = null
  private var recreates = 0
  private var released = false
  private var lastVerdict: YtSizeVerdict? = null
  private var windowStart = 0L
  private var windowCount = 0
  private val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> checkSize(v) }

  init {
    web = createWebView()
  }

  /** Hosts the player in [target] (match-parent), moving it out of any previous parent. */
  @MainThread
  fun attach(target: ViewGroup) {
    if (released) return
    val view = web ?: return
    if (container !== target) {
      container?.removeOnLayoutChangeListener(layoutListener)
      container = target
      target.addOnLayoutChangeListener(layoutListener)
    }
    wrapper.baseContext = target.context
    val parent = view.parent as? ViewGroup
    if (parent !== target) {
      parent?.removeView(view)
      target.addView(view, ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    view.onResume()
    checkSize(target)
  }

  /** Un-hosts the player from [target] if it is still there. Playback state is the engine's. */
  @MainThread
  fun detach(target: ViewGroup) {
    if (container === target) {
      target.removeOnLayoutChangeListener(layoutListener)
      container = null
      lastVerdict = null
    }
    val view = web ?: return
    if (view.parent === target) target.removeView(view)
    if (view.parent == null) wrapper.baseContext = app
  }

  @MainThread fun load(videoId: String, startMs: Long) = YtPlayerPage.load(videoId, startMs)?.let(::run)
  @MainThread fun cue(videoId: String, startMs: Long) = YtPlayerPage.cue(videoId, startMs)?.let(::run)
  @MainThread fun play() = run(YtPlayerPage.PLAY)
  @MainThread fun pause() = run(YtPlayerPage.PAUSE)
  @MainThread fun stop() = run(YtPlayerPage.STOP)
  @MainThread fun seek(positionMs: Long) = run(YtPlayerPage.seek(positionMs))
  /** Player volume 0–100 (the IFrame API's own volume, not the device stream). */
  @MainThread fun setVolume(level: Int) = run(YtPlayerPage.volume(level))

  /** Stops the player and destroys the WebView. The host is unusable afterwards. */
  @MainThread
  fun release() {
    if (released) return
    released = true
    container?.removeOnLayoutChangeListener(layoutListener)
    container = null
    destroyWebView()
  }

  private fun run(script: String) {
    if (!released) web?.evaluateJavascript(script, null)
  }

  private fun checkSize(v: View) {
    val verdict = YtPlayerSize.verdict(v.width, v.height, v.resources.displayMetrics.density)
    if (verdict == lastVerdict) return
    lastVerdict = verdict
    listener.onSizeVerdict(verdict)
  }

  @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
  private fun createWebView(): WebView = WebView(wrapper).apply {
    setBackgroundColor(Color.BLACK)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // Proven on the Portal: the official player reaches "playing" only with this off. The user
    // opened music and pressed play; the player itself stays visible with its own controls.
    settings.mediaPlaybackRequiresUserGesture = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setSupportMultipleWindows(false)
    webViewClient = Client()
    webChromeClient = WebChromeClient()
    addJavascriptInterface(Bridge(this), YtPlayerPage.BRIDGE_NAME)
    // Base URL https://<package>: the Referer identifies the app (YouTube RMF); same as origin.
    loadDataWithBaseURL(baseUrl, YtPlayerPage.html(baseUrl), "text/html", "utf-8", null)
  }

  private fun destroyWebView() {
    val view = web ?: return
    web = null
    (view.parent as? ViewGroup)?.removeView(view)
    runCatching {
      view.removeJavascriptInterface(YtPlayerPage.BRIDGE_NAME)
      view.stopLoading()
      view.destroy()
    }.onFailure { Log.w(TAG, "WebView teardown failed", it) }
  }

  private fun onRendererGone(view: WebView) {
    if (view !== web || released) return
    destroyWebView()
    listener.onPlayerLost()
    if (recreates >= MAX_RECREATES) {
      Log.w(TAG, "renderer keeps dying; giving up after $recreates rebuilds")
      listener.onPlayerEvent(YtPlayerEvent.ApiUnavailable)
      return
    }
    recreates++
    web = createWebView()
    container?.let(::attach)
  }

  private fun onMessage(view: WebView, raw: String?) {
    if (released || view !== web || !withinRate()) return
    val event = YtPlayerEventParser.parse(raw, SystemClock.elapsedRealtime()) ?: return
    if (event == YtPlayerEvent.Ready) recreates = 0
    if (event is YtPlayerEvent.Error) Log.i(TAG, "player error ${event.code}")
    listener.onPlayerEvent(event)
  }

  /** Drops floods (any frame can call a JavaScript interface); the page posts ~1 msg/s. */
  private fun withinRate(): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (now - windowStart > RATE_WINDOW_MS) {
      windowStart = now
      windowCount = 0
    }
    windowCount++
    return windowCount <= MAX_MESSAGES_PER_WINDOW
  }

  /** Receives page events on a binder thread; everything else happens on main. */
  private inner class Bridge(private val view: WebView) {
    @JavascriptInterface
    fun postMessage(raw: String?) {
      if (raw == null || raw.length > YtPlayerEventParser.MAX_MESSAGE_CHARS) return
      main.post { onMessage(view, raw) }
    }
  }

  private inner class Client : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
      if (!request.isForMainFrame) return false
      // The host page is inline; any top-level navigation is a link out of the player.
      val uri = request.url
      if (uri.scheme == "https") listener.onExternalLink(uri)
      return true
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
      val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail?.didCrash() else null
      Log.w(TAG, "renderer gone (crash=$crashed)")
      onRendererGone(view)
      return true
    }
  }
}
