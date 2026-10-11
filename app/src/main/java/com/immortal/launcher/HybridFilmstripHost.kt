/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.animation.PathInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

/** Main-owned native beats inside the existing JS scheduler. JS retains selection and clocks;
 * this host owns at most two stages, shared prepared pixels and one outer fade. */
internal class HybridFilmstripHost(
    private val root: FrameLayout,
    private val webView: WebView,
    private val sources: List<String>,
    private val intervalMillis: Long,
    private val decode: (String, Int) -> Bitmap?,
    private val isCurrent: () -> Boolean,
    private val liftOverlays: () -> Unit,
) : AutoCloseable {
  private class Stage(val view: FilmstripEngine, val urls: List<String>) {
    var ready = false
    var paused = true
  }
  // An independent Handler admits bridge acknowledgements even while the scheduler is paused.
  private val main = Handler(Looper.getMainLooper())
  private val stages = LinkedHashMap<String, Stage>()
  private val budget = SlideshowPixelBudget(256L * 1024 * 1024)
  private val curve = PathInterpolator(.4f,0f,.2f,1f)
  @Volatile private var closed = false
  private var activeToken = ""
  private var fade: AnimatorSet? = null
  private var hostActive = true
  private var userPaused = false
  val hasActiveNative: Boolean get() = activeToken.isNotEmpty() && stages[activeToken]?.ready == true

  private fun dispatch(action: () -> Unit): Boolean = !closed && main.post {
    if (!closed && isCurrent()) action()
  }
  private fun emit(name: String, vararg args: Any) {
    if (closed || !isCurrent()) return
    val json = args.joinToString(",") { when(it) {
      is String -> JSONObject.quote(it)
      is Boolean, is Int -> it.toString()
      else -> error("Unsupported Filmstrip callback")
    } }
    webView.evaluateJavascript("window.SS&&SS.$name&&SS.$name($json)",null)
  }

  /** Called by the JS bridge thread. Parsing and index validation are bounded before dispatch. */
  fun prepare(token: String, idsJson: String): Boolean {
    if (!NativeFilmstripSelection.validToken(token) || idsJson.length > 1024) return false
    val ids = runCatching {
      val values = JSONArray(idsJson)
      if (values.length() !in 1..12) return false
      (0 until values.length()).map { index ->
        val value = values.opt(index)
        if (value !is Int) return false
        value
      }
    }.getOrNull() ?: return false
    val selected = NativeFilmstripSelection.resolve(ids,sources) ?: return false
    return dispatch {
      if (stages.containsKey(token) || stages.size >= 2) {
        emit("nativeFilmstripPrepared",token,false)
        return@dispatch
      }
      lateinit var stage: Stage
      val view = FilmstripEngine(root.context,selected,intervalMillis,
          decode=decode, weight={ 0f }, watching=false,
          onAdmitted={
            if (!closed && stages[token] === stage && isCurrent()) {
              stage.ready = true
              stage.view.setPaused(stage.paused || userPaused)
              emit("nativeFilmstripPrepared",token,true)
            }
          },
          onFailure={
            if (!closed && stages[token] === stage && isCurrent()) {
              // A failed resized backdrop keeps the last admitted deck visible until its
              // normal scheduler replacement; initial failure falls back through the adapter.
              if (!stage.ready) release(token)
              emit("nativeFilmstripPrepared",token,false)
            }
          },
          orderedSelection=true, externallyClocked=true, sharedBudget=budget,
          publishOwnProbe=false,
          onStateChanged={ state ->
            if (!closed && activeToken == token) FilmstripRuntimeProbe.record(this,state)
          })
      stage = Stage(view,selected)
      stages[token] = stage // Ownership precedes attachment/start.
      view.alpha = 0f
      try {
        root.addView(view,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
        liftOverlays()
        view.setHostActive(hostActive)
        // Preparation remains deliverable; only admitted motion is paused until promotion.
        view.start()
      } catch (_: RuntimeException) {
        release(token)
        emit("nativeFilmstripPrepared",token,false)
      }
    }
  }

  fun command(token: String, action: String, requestId: Int): Boolean {
    if (!NativeFilmstripSelection.validToken(token) || action !in setOf("next","previous","pause","resume","release","restore") || requestId < 0) return false
    return dispatch {
      val stage = stages[token]
      val ok = when {
        stage == null -> action == "release"
        action == "release" -> { release(token); true }
        action == "pause" -> { stage.paused = true; sync(stage); true }
        action == "resume" -> { stage.paused = false; sync(stage); true }
        !stage.ready -> false
        action == "restore" -> { restore(token,stage); true }
        action == "next" -> stage.view.next()
        else -> stage.view.previous()
      }
      emit("nativeFilmstripCommandDone",token,requestId,ok)
    }
  }

  /** The successor WebView layer is made opaque by JS before native→WebView fade starts.
   * Acknowledgement describes accepted ownership, not photo presentation. */
  fun promote(incoming: String, outgoing: String, handoffId: Int): Boolean {
    if ((incoming.isNotEmpty() && !NativeFilmstripSelection.validToken(incoming)) ||
        (outgoing.isNotEmpty() && !NativeFilmstripSelection.validToken(outgoing)) || handoffId < 0) return false
    return dispatch {
      val next = stages[incoming]
      val old = stages[outgoing]
      if ((incoming.isNotEmpty() && next?.ready != true) ||
          (outgoing.isNotEmpty() && (old?.ready != true || activeToken != outgoing)) ||
          (outgoing.isEmpty() && activeToken.isNotEmpty()) || incoming == outgoing && incoming.isNotEmpty()) {
        emit("nativeFilmstripHandoffDone",handoffId,false)
        return@dispatch
      }
      fade?.removeAllListeners(); fade?.cancel(); fade = null
      old?.let { it.paused = true; sync(it); it.view.alpha = 1f }
      activeToken = incoming
      if (next != null) {
        next.paused = false
        next.view.alpha = 0f
        next.view.bringToFront(); liftOverlays(); sync(next)
        FilmstripRuntimeProbe.record(this,next.view.runtimeState())
        FilmstripScreenshot.bind(next.view,next.urls)
      } else {
        FilmstripRuntimeProbe.clear(this)
        old?.let { FilmstripScreenshot.clear(it.view) }
      }
      val target = next?.view ?: old?.view
      if (target != null) {
        val animation = AnimatorSet().apply {
          playTogether(ObjectAnimator.ofFloat(target,"alpha",if(next != null) 1f else 0f))
          duration = 1600L; interpolator = curve
          addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(ended: Animator) { if (fade === ended) fade = null }
          })
        }
        fade = animation
        animation.start()
        if (!hostActive || userPaused) animation.pause()
      }
      emit("nativeFilmstripHandoffDone",handoffId,true)
    }
  }

  /** Recovery is opaque before its command acknowledgement. JS retains the successor
   * until that acknowledgement, then resumes this retained stage without decoding again. */
  private fun restore(token: String, stage: Stage) {
    fade?.removeAllListeners(); fade?.cancel(); fade = null
    stages.values.forEach { it.paused = true; sync(it) }
    activeToken = token
    stage.view.alpha = 1f
    stage.view.bringToFront(); liftOverlays()
    FilmstripRuntimeProbe.record(this,stage.view.runtimeState())
    FilmstripScreenshot.bind(stage.view,stage.urls)
  }

  private fun sync(stage: Stage) {
    stage.view.setHostActive(hostActive)
    if (stage.ready) stage.view.setPaused(stage.paused || userPaused)
  }
  fun setHostActive(value: Boolean) {
    hostActive = value
    stages.values.forEach(::sync)
    if (!hostActive || userPaused) fade?.pause() else fade?.resume()
  }
  fun setPaused(value: Boolean) {
    userPaused = value
    stages.values.forEach(::sync)
    if (!hostActive || userPaused) fade?.pause() else fade?.resume()
  }
  private fun release(token: String) {
    val stage = stages.remove(token) ?: return
    if (activeToken == token) {
      activeToken = ""
      FilmstripRuntimeProbe.clear(this)
    }
    stage.view.close()
    (stage.view.parent as? android.view.ViewGroup)?.removeView(stage.view)
  }
  override fun close() {
    if (closed) return
    closed = true
    main.removeCallbacksAndMessages(null)
    fade?.removeAllListeners(); fade?.cancel(); fade = null
    FilmstripRuntimeProbe.clear(this)
    stages.keys.toList().forEach(::release)
    activeToken = ""
  }
}
