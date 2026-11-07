/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONTokener

/** What the embedded YouTube Music pane should show over (or instead of) the page. */
enum class YtMusicPageStatus { IDLE, LOADING, READY, BLOCKED, ERROR }

/**
 * Process-scoped owner of the ONE retained WebView for music.youtube.com. It outlives the pane
 * so playback continues while the pane (or the whole player) is closed; [release] is the only
 * teardown — call it when the user stops/closes music. It also releases itself on
 * TRIM_MEMORY_COMPLETE and when the renderer dies.
 *
 * Built on a [MutableContextWrapper] over the application context: while hosted, its base is the
 * hosting Activity (so page popups such as `<select>` work); when detached it falls back to the
 * application so no Activity leaks. The stock user agent is kept: if Google refuses sign-in here,
 * the pane shows its fallback; nothing tries to avoid that check.
 */
object YtMusicWebHost {
  private const val TAG = "ImmortalYtMusicHost"
  private const val PAGE_TEXT_SCRIPT = "(function(){try{return ((document.title||'')+'\\n'+" +
      "(document.body?document.body.innerText:'')).slice(0,4000);}catch(e){return '';}})()"

  /** Snapshot state for Compose; written on the main thread only. */
  var status by mutableStateOf(YtMusicPageStatus.IDLE)
    private set
  var currentUrl by mutableStateOf<String?>(null)
    private set

  private val main = Handler(Looper.getMainLooper())
  private var web: WebView? = null
  private var bridge: YtMusicBridge? = null
  private var wrapper: MutableContextWrapper? = null
  private var app: Application? = null

  private val memory = object : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
      @Suppress("DEPRECATION")
      if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) main.post { release() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = Unit
  }

  val isAlive: Boolean get() = web != null

  /** The music transport while the page lives (also registered in [MusicTransports.embedded]). */
  val transport: MusicTransport? get() = bridge

  /** Returns the retained WebView, creating and loading it on first use. */
  @MainThread
  fun obtain(context: Context): WebView {
    web?.let { return it }
    val application = context.applicationContext as Application
    val ctx = MutableContextWrapper(application)
    val view = createWebView(ctx)
    val b = YtMusicBridge(view).also { it.install(application) }
    wrapper = ctx
    web = view
    bridge = b
    app = application.also { it.registerComponentCallbacks(memory) }
    MusicTransports.embedded = b
    status = YtMusicPageStatus.LOADING
    view.loadUrl(YtMusicUrls.HOME)
    return view
  }

  /** Hosts the retained WebView in [container], detaching it from any previous parent first. */
  @MainThread
  fun attach(container: ViewGroup) {
    val view = obtain(container.context)
    wrapper?.baseContext = container.context
    val parent = view.parent as? ViewGroup
    if (parent !== container) {
      parent?.removeView(view)
      container.addView(view, ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    view.onResume()
  }

  /** Un-hosts the WebView from [container] (if it is still there); playback keeps going. */
  @MainThread
  fun detach(container: ViewGroup) {
    val view = web ?: return
    if (view.parent === container) container.removeView(view)
    if (view.parent == null) app?.let { wrapper?.baseContext = it }
  }

  /** Shows [tab], preferring the page's own in-app navigation so playback is not interrupted. */
  @MainThread
  fun showTab(tab: YtMusicTab) {
    val view = web ?: return
    val b = bridge ?: return
    if (status == YtMusicPageStatus.BLOCKED) return
    b.go(tab) { handled ->
      if (handled || web !== view) return@go
      // Reloading would stop the current song; only fall back to a URL load when not playing.
      if (b.state?.state == PlaybackState.PLAYING) return@go
      val target = tab.targetUrl(view.url)
      if (target != view.url) view.loadUrl(target)
    }
  }

  /** Retry after a connection error or a renderer loss, re-hosting in [container] if needed. */
  @MainThread
  fun retry(container: ViewGroup) {
    val view = web ?: return attach(container)
    status = YtMusicPageStatus.LOADING
    view.loadUrl(view.url?.takeIf { YtMusicUrls.isMusicPage(it) } ?: YtMusicUrls.HOME)
  }

  /** Stops playback and frees the renderer. Call when the user stops or closes music. */
  @MainThread
  fun release() {
    val view = web ?: return
    web = null
    bridge?.release()
    bridge = null
    app?.unregisterComponentCallbacks(memory)
    app?.let { wrapper?.baseContext = it }
    app = null
    wrapper = null
    runCatching { CookieManager.getInstance().flush() }
    (view.parent as? ViewGroup)?.removeView(view)
    view.stopLoading()
    view.destroy()
    status = YtMusicPageStatus.IDLE
    currentUrl = null
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createWebView(ctx: Context): WebView = WebView(ctx).apply {
    setBackgroundColor(Color.BLACK)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // Native transport (play from the Now Playing column) is evaluated script, not an in-page
    // gesture; the user chose to open music, as in WebAppActivity.
    settings.mediaPlaybackRequiresUserGesture = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setSupportMultipleWindows(false)
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    // YouTube's sign-in sets its session across accounts.google.com / youtube.com.
    cookies.setAcceptThirdPartyCookies(this, true)
    webViewClient = Client()
    webChromeClient = WebChromeClient()
  }

  private fun navigated(view: WebView, url: String?) {
    if (view !== web) return
    currentUrl = url
    bridge?.onNavigated(url)
    if (YtMusicSignInDetector.isBlockedUrl(url)) status = YtMusicPageStatus.BLOCKED
  }

  private fun checkSignInPage(view: WebView, url: String?) {
    view.evaluateJavascript(PAGE_TEXT_SCRIPT) { raw ->
      if (view !== web || status == YtMusicPageStatus.BLOCKED) return@evaluateJavascript
      val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
      if (YtMusicSignInDetector.isBlockedPage(url, text)) status = YtMusicPageStatus.BLOCKED
    }
  }

  private class Client : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
      if (!request.isForMainFrame) return false
      val url = request.url.toString()
      if (YtMusicSignInDetector.isBlockedUrl(url)) status = YtMusicPageStatus.BLOCKED
      // Keep the pane on YouTube Music and Google sign-in; refuse everything else in place.
      return !YtMusicUrls.isAllowedInPane(url)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
      super.onPageStarted(view, url, favicon)
      navigated(view, url)
      if (view === web && status != YtMusicPageStatus.BLOCKED) status = YtMusicPageStatus.LOADING
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
      super.doUpdateVisitedHistory(view, url, isReload)
      navigated(view, url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
      super.onPageFinished(view, url)
      if (view !== web) return
      navigated(view, url)
      bridge?.onPageFinished(url)
      runCatching { CookieManager.getInstance().flush() }
      if (status == YtMusicPageStatus.BLOCKED || status == YtMusicPageStatus.ERROR) return
      status = YtMusicPageStatus.READY
      if (!YtMusicUrls.isMusicPage(url)) checkSignInPage(view, url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
      super.onReceivedError(view, request, error)
      if (view !== web || status == YtMusicPageStatus.BLOCKED) return
      if (WebAppLoadErrorPolicy.shouldRecover(request.isForMainFrame, error.errorCode)) {
        status = YtMusicPageStatus.ERROR
      }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
      // The renderer died (often OOM on a 2.7 GB Portal): drop this WebView instead of crashing
      // the launcher. The pane recreates it on its next show.
      Log.w(TAG, "renderer gone (crash=${if (Build.VERSION.SDK_INT >= 26) detail?.didCrash() else null})")
      if (view === web) {
        release()
        status = YtMusicPageStatus.ERROR
      }
      return true
    }
  }
}
