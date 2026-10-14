/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.annotation.SuppressLint
import android.content.Intent
import org.json.JSONObject
import android.os.Bundle
import android.os.Build
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.doOnAttach
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * A fullscreen kiosk WebView for web apps that have no installable client on the Portal
 * (no GMS — so e.g. YouTube Music runs as its PWA instead). Generic on purpose: the URL and
 * label come from the launch Intent, so any future web-app tile reuses this Activity.
 *
 * - Media playback without a user gesture (it's a music player on a wall display).
 * - Desktop-ish viewport (wide viewport + overview) so responsive PWAs pick their tablet layout.
 * - DOM storage on → the site keeps its login/session across launches (default WebView cookie
 *   persistence applies).
 * - Back navigates the page history first; only exits when the page can't go back.
 * - The WebView instance is retained across pause/resume; audio keeps playing until destroy.
 */
class WebAppActivity : ComponentActivity() {

  companion object {
    const val EXTRA_URL = "url"
    /** YouTube Music's PWA — the Music tile's target. */
    const val URL_YT_MUSIC = "https://music.youtube.com"
  }

  private var web: WebView? = null
  private lateinit var entry: WebAppEntryState
  private var entryUrl: String = URL_YT_MUSIC
  private var loads = 0
  private var pageRevision = 0L
  private lateinit var root: FrameLayout
  private lateinit var opening: LinearLayout
  private val openingGate = WebAppOpeningGate()
  private var frameCommit: Runnable? = null
  private var drawListener: ViewTreeObserver.OnDrawListener? = null
  private val createWeb = Runnable { createWebAfterFirstFrame() }

  internal fun isGeneratedProbe(): Boolean = WebAppProbe.isProbeUrl(entryUrl) &&
      WebAppProbe.isProbeUrl(web?.url?.substringBefore('#'))

  internal fun generatedProbeSnapshot(callback: (JSONObject?) -> Unit) {
    val view = web
    if (view == null || !isGeneratedProbe() || !hasWindowFocus()) {
      callback(null)
      return
    }
    view.evaluateJavascript("""(() => ({document: window.portalProbeDocument,
        seeded: document.getElementById('probe').value === 'generated marker',
        scrolled: window.scrollY > 100, history: location.hash === '#chapter'}))()""") { raw ->
      if (!isGeneratedProbe() || !hasWindowFocus()) callback(null)
      else {
        val value = runCatching { JSONObject(raw) }.getOrNull()
        val document = value?.opt("document") as? String
        if (document == null || !document.matches(Regex("[a-z0-9-]{1,64}")) ||
            listOf("seeded", "scrolled", "history").any { value.opt(it) !is Boolean }) callback(null)
        else callback(value.put("ok", true).put("active", true).put("probe", true)
            .put("focused", true).put("activityIdentity", System.identityHashCode(this))
            .put("viewIdentity", System.identityHashCode(view)).put("loads", loads))
      }
    }
  }

  internal fun seedGeneratedProbe(): Boolean {
    if (!isGeneratedProbe() || !hasWindowFocus()) return false
    web?.evaluateJavascript("""(() => {document.getElementById('probe').value = 'generated marker';
        window.scrollTo(0, 600); history.pushState({}, '', '#chapter'); return true;})()""", null)
    return true
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Immersive: this is a kiosk surface — no status/nav chrome (swipe reveals transiently).
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    val url = intent?.getStringExtra(EXTRA_URL) ?: URL_YT_MUSIC
    entryUrl = url
    entry = WebAppEntryState(url)
    root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(19, 20, 22)) }
    opening = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(Color.rgb(19, 20, 22))
      isClickable = true
      val size = (44 * resources.displayMetrics.density).toInt()
      addView(ProgressBar(context).apply {
        indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
      }, LinearLayout.LayoutParams(size, size))
      addView(TextView(context).apply {
        text = if (entryUrl == URL_YT_MUSIC) "Opening Music…" else "Opening page…"
        textSize = 28f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
          ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (24 * resources.displayMetrics.density).toInt()
      })
    }
    root.addView(opening, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    setContentView(root)
    WebAppProbe.created(this)
    root.doOnAttach { view ->
      if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
        val callback = Runnable { root.post { firstFrameReady(true) } }
        frameCommit = callback
        view.viewTreeObserver.registerFrameCommitCallback(callback)
      } else {
        var drawn = false
        val listener = ViewTreeObserver.OnDrawListener {
          if (!drawn) {
            drawn = true
            root.post {
              drawListener?.let { if (root.viewTreeObserver.isAlive)
                root.viewTreeObserver.removeOnDrawListener(it) }
              drawListener = null
              firstFrameReady(false)
            }
          }
        }
        drawListener = listener
        view.viewTreeObserver.addOnDrawListener(listener)
      }
    }

    onBackPressedDispatcher.addCallback(
        this,
        object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() {
            val w = web
            if (w != null && w.canGoBack()) w.goBack()
            else {
              isEnabled = false
              onBackPressedDispatcher.onBackPressed()
            }
          }
        })
  }

  private fun firstFrameReady(hardware: Boolean) {
    frameCommit = null
    if (isFinishing || isDestroyed) return
    WebAppOpeningDiagnostics.frameReady(hardware)
    openingGate.frameCommitted()
    root.post(createWeb)
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createWebAfterFirstFrame() {
    val foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    if (isFinishing || isDestroyed || !openingGate.tryStart(foreground)) return
    WebAppOpeningDiagnostics.creationStarting()
    val view = WebView(this).apply {
      setBackgroundColor(Color.BLACK)
      isVerticalScrollBarEnabled = false
      isHorizontalScrollBarEnabled = false
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.mediaPlaybackRequiresUserGesture = false
      settings.loadWithOverviewMode = true
      settings.useWideViewPort = true
      webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
          super.onPageStarted(view, url, favicon)
          pageRevision++
          opening.visibility = View.VISIBLE
        }
        override fun onPageFinished(view: WebView, url: String?) {
          super.onPageFinished(view, url)
          if (url != view.url) return
          val readyRevision = pageRevision
          view.postVisualStateCallback(readyRevision, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
              if (requestId != pageRevision || view !== web || isFinishing || isDestroyed) return
              opening.visibility = View.GONE
              WebAppOpeningDiagnostics.pageFinished()
            }
          })
        }
      }
      webChromeClient = WebChromeClient()
    }
    web = view
    root.addView(view, 0, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    // An entry change arriving during first-frame admission uses the latest request.
    loads++
    WebAppOpeningDiagnostics.loadRequested()
    view.loadUrl(entryUrl)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val url = intent.getStringExtra(EXTRA_URL) ?: URL_YT_MUSIC
    // Compare the requested entry, not the current page after redirects/navigation.
    if (entry.request(url)) {
      entryUrl = url
      web?.let {
        loads++
        pageRevision++
        WebAppOpeningDiagnostics.loadRequested()
        it.loadUrl(url)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    web?.onResume()
    if (::root.isInitialized) root.post(createWeb)
  }

  override fun onPause() {
    // Do not pauseTimers(): it affects every WebView in this process, including clocks.
    web?.onPause()
    super.onPause()
  }

  override fun onDestroy() {
    openingGate.destroy()
    pageRevision++
    if (::root.isInitialized) {
      root.removeCallbacks(createWeb)
      if (Build.VERSION.SDK_INT >= 29) frameCommit?.let {
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.unregisterFrameCommitCallback(it)
      }
      drawListener?.let { if (root.viewTreeObserver.isAlive)
        root.viewTreeObserver.removeOnDrawListener(it) }
    }
    frameCommit = null
    drawListener = null
    WebAppProbe.destroyed(this)
    // Stop audio and free the renderer when the kiosk actually closes.
    web?.apply {
      stopLoading()
      (parent as? ViewGroup)?.removeView(this)
      destroy()
    }
    web = null
    super.onDestroy()
  }
}
