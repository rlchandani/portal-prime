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
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/**
 * Native candidate for the existing 12-card coverflow. The deck, EXIF decode and small blurred
 * textures are prepared off Main. Steady-state motion changes RenderNode properties only.
 * Pixel ownership is bounded; closed hosts reject even a non-interruptible decoder's late result.
 */
internal class FilmstripEngine(
    context: Context,
    urls: List<String>,
    private val intervalMillis: Long,
    decode: (String, Int) -> Bitmap?,
    private val weight: (String) -> Float,
    watching: Boolean,
    private val onAdmitted: () -> Unit,
    private val onFailure: () -> Unit,
) : FrameLayout(context), AutoCloseable {
  private data class Pixels(val photo: Bitmap, val backdrop: Bitmap, val reusedPhoto: Boolean = false) {
    val bytes: Long get() = photo.allocationByteCount.toLong() + backdrop.allocationByteCount
    fun discard() { if (!reusedPhoto) photo.recycle(); backdrop.recycle() }
  }
  private class Scene(val images: List<Pixels>, val budgetKey: String, val revision: Long, val sourceUrls: List<String>, val backdropOnly: Boolean) {
    fun discard() { images.forEach { it.discard() } }
  }
  private var urls = urls.toList()
  private var decode = decode
  private var watching = watching
  private var preparationRevision = 0L
  private var pendingBackdrop: Int? = null
  private data class Card(val container: FrameLayout, val image: ImageView, val dimmer: View)
  private val density = resources.displayMetrics.density
  private val work = SlideshowWork()
  private val ui = PausableFrameCallbacks()
  private val budget = SlideshowPixelBudget(256L * 1024 * 1024)
  private val backgrounds = Array(2) { ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }
  private val backgroundLayer = FrameLayout(context).apply { clipChildren = false }
  private val track = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
  private val vignette = FilmstripVignette(context)
  private var cards = emptyList<Card>()
  private var scene: Scene? = null
  private var layout: FilmstripGeometry.Layout? = null
  private var motion: AnimatorSet? = null
  private var dissolve: AnimatorSet? = null
  private var topBackground = 0
  private var center = 0
  private var advances = 0L
  private var started = false
  private var loading = false
  private var loadingSource = false
  private val backdropIndices = intArrayOf(0,0)
  @Volatile private var closed = false
  private var hostActive = true
  private var userPaused = false
  private val delivery = SlideshowPreparedDelivery<Scene>(
      postMain = { task -> ui.post { task() } },
      discard = ::discardScene,
      admit = { prepared ->
        if (closed || prepared.revision != preparationRevision) discardScene(prepared)
        else try { admit(prepared) } catch (_: RuntimeException) { onFailure() }
      })
  private val tick = Runnable { if (!closed && !userPaused) move(+1) }
  private val transformCurve = PathInterpolator(.22f, 1f, .36f, 1f)
  private val fadeCurve = PathInterpolator(.25f, .1f, .25f, 1f)

  init {
    setBackgroundColor(Color.BLACK)
    clipChildren = true
    backgrounds.forEach { backgroundLayer.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    addView(backgroundLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    backgrounds[1].alpha = 0f
    addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    addView(vignette, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    // Parent controller owns tap/gesture dispatch, just like its legacy WebView.
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  }

  fun start() {
    if (closed || started) return
    started = true
    reportState()
    prepareIfSized()
  }

  fun reload(urls: List<String>, decode: (String, Int) -> Bitmap?, watching: Boolean) {
    if (closed) return
    this.urls = urls.toList()
    this.decode = decode
    this.watching = watching
    prepareIfSized(replace = true)
  }

  private fun prepareIfSized(replace: Boolean = false, reusePhotos: List<Bitmap>? = null) {
    if (!started || (loading && !replace) || closed || width <= 0 || height <= 0) return
    loading = true
    loadingSource = reusePhotos == null
    val viewportWidth = width
    val viewportHeight = height
    val frozenUrls = urls
    val frozenDecode = decode
    val frozenWatching = watching
    val revision = ++preparationRevision
    val budgetKey = "deck-$revision"
    work.submit { current ->
      val span = FramePerformanceCapture.beginSlideshowWork(SlideshowWorkStage.NATIVE_FILMSTRIP_PREPARATION)
      val images = ArrayList<Pixels>(12)
      var handedOff = false
      try {
        // Same legacy selection: shuffled unique deck, stable people bias while watched, at most 12.
        val shuffled = frozenUrls.distinct().shuffled()
        val deck = (if (frozenWatching) shuffled.sortedByDescending { weight(it) } else shuffled).take(12)
        val count = reusePhotos?.size ?: deck.size
        for (index in 0 until count) {
          if (!current()) throw CancellationException()
          // 1400px is the legacy card target; reserve EXIF's possible second bitmap plus blur scratch.
          val reservation = budget.reserve((if (reusePhotos == null) 24L else 3L) * 1024 * 1024, current) ?: throw CancellationException()
          var photo: Bitmap? = null
          var backdrop: Bitmap? = null
          try {
            photo = reusePhotos?.get(index) ?: runCatching { frozenDecode(deck[index], 1400) }.getOrNull() ?: continue
            if (!current()) throw CancellationException()
            backdrop = FilmstripBackdrop.prepare(context, photo, viewportWidth, viewportHeight, density)
            if (!current()) throw CancellationException()
            val pixels = Pixels(photo, backdrop, reusedPhoto = reusePhotos != null)
            images.add(pixels)
            photo = null; backdrop = null
            budget.commit(reservation, budgetKey, images.flatMap {
              listOf(SlideshowPixelBudget.Entry(it.photo, it.photo.allocationByteCount.toLong()),
                  SlideshowPixelBudget.Entry(it.backdrop, it.backdrop.allocationByteCount.toLong())) })
          } finally {
            if (reusePhotos == null) photo?.recycle(); backdrop?.recycle()
            budget.release(reservation)
          }
        }
        if (images.isEmpty()) throw IllegalStateException("No decoded Filmstrip card")
        if (!current()) throw CancellationException()
        val prepared = Scene(images.toList(), budgetKey, revision, frozenUrls, backdropOnly = reusePhotos != null)
        handedOff = true
        delivery.publish(prepared)
      } catch (_: CancellationException) {
        // Superseded/closed work has no UI side effect.
      } catch (_: Exception) {
        ui.post { if (!closed && revision == preparationRevision) onFailure() }
      } catch (_: OutOfMemoryError) {
        ui.post { if (!closed && revision == preparationRevision) onFailure() }
      } finally {
        if (!handedOff) {
          images.forEach { it.discard() }
          budget.setOwner(budgetKey,emptyList())
        }
        span?.finish()
      }
    }
  }

  private fun admit(prepared: Scene) {
    val span = FramePerformanceCapture.beginSlideshowWork(SlideshowWorkStage.NATIVE_FILMSTRIP_ADMISSION)
    try {
      loading = false
      val previous = scene
      if (prepared.backdropOnly && previous != null) {
        check(prepared.images.size == cards.size && prepared.images.indices.all {
          prepared.images[it].photo === previous.images[it].photo
        })
        scene = prepared
        backgrounds.forEachIndexed { index, view ->
          view.setImageBitmap(prepared.images[backdropIndices[index]].backdrop)
        }
        budget.setOwner(previous.budgetKey,emptyList())
        reportState()
        FilmstripScreenshot.bind(this,prepared.sourceUrls)
        return // Keep current card, live motion, dissolve alpha, timer and style admission.
      }
      motion?.removeAllListeners(); motion?.cancel(); motion = null
      dissolve?.removeAllListeners(); dissolve?.cancel(); dissolve = null
      pendingBackdrop = null
      cards.forEach { it.image.setImageDrawable(null) }
      backgrounds.forEach { it.setImageDrawable(null) }
      track.removeAllViews()
      previous?.let { budget.setOwner(it.budgetKey,emptyList()) }
      scene = prepared // Main cleanup owns all pixels before any View construction can fail.
      cards = prepared.images.map { pixels ->
        val image = ImageView(context).apply {
          scaleType = ImageView.ScaleType.FIT_XY
          setImageBitmap(pixels.photo)
        }
        val dimmer = View(context).apply { setBackgroundColor(Color.BLACK); alpha = .45f }
        val container = FrameLayout(context).apply {
          outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
              outline.setRoundRect(0,0,view.width,view.height,10f*density)
            }
          }
          clipToOutline = true
          elevation = 30f * density
          if (android.os.Build.VERSION.SDK_INT >= 28) {
            outlineAmbientShadowColor = Color.BLACK; outlineSpotShadowColor = Color.BLACK
          }
          scaleX = .7f; scaleY = .7f; alpha = .4f
          addView(image, LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
          addView(dimmer, LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        }
        track.addView(container)
        Card(container, image, dimmer)
      }
      relayout(initial = true)
      backgrounds[0].setImageBitmap(prepared.images[0].backdrop)
      backgrounds[0].alpha = 1f; backgrounds[1].alpha = 0f
      backgrounds[0].bringToFront()
      topBackground = 0
      backdropIndices.fill(0)
      show(0, initial = true)
      FilmstripScreenshot.bind(this, prepared.sourceUrls)
      onAdmitted()
    } finally { span?.finish() }
  }

  private fun relayout(initial: Boolean = false) {
    val images = scene?.images ?: return
    val geometry = FilmstripGeometry.layout(width/density,height/density,
        images.map { it.photo.width.toFloat()/it.photo.height }) ?: return
    layout = geometry
    // Physical-pixel bounds are assigned only on admission/resize, never inside an animation frame.
    geometry.cards.forEachIndexed { index, card ->
      cards[index].container.layoutParams = LayoutParams(
          (card.width*density).roundToInt().coerceAtLeast(1),
          (card.height*density).roundToInt().coerceAtLeast(1)).apply {
        leftMargin = (width/2f+(card.offset-card.width/2f)*density).roundToInt()
        topMargin = ((height-card.height*density)/2f).roundToInt()
      }
    }
    track.translationX = if (initial) 0f else -geometry.cards[center].offset*density
    backgrounds.forEach {
      it.layoutParams = LayoutParams((width*1.38f).roundToInt(),(height*1.38f).roundToInt()).apply {
        leftMargin = -(width*.19f).roundToInt(); topMargin = -(height*.19f).roundToInt()
      }
    }
  }

  private fun show(index: Int, initial: Boolean = false) {
    val images = scene?.images ?: return
    val geometry = layout ?: return
    center = index
    motion?.removeAllListeners(); motion?.cancel()
    val transforms = ArrayList<Animator>()
    fun property(view: View, name: String, value: Float, transform: Boolean = true) {
      transforms.add(ObjectAnimator.ofFloat(view,name,value).apply {
        duration = 1400L
        interpolator = if (transform) transformCurve else fadeCurve
      })
    }
    property(track,"translationX",-geometry.cards[index].offset*density)
    cards.forEachIndexed { ordinal, card ->
      val selected = ordinal == index
      val scale = if (selected) 1.12f else .7f
      property(card.container,"scaleX",scale); property(card.container,"scaleY",scale)
      property(card.container,"alpha",if (selected) 1f else .4f,false)
      property(card.dimmer,"alpha",if (selected) 0f else .45f,false)
    }
    motion = AnimatorSet().apply { playTogether(transforms); start() }
    if (!initial) requestBackdrop(index)
    if (!hostActive || userPaused) { motion?.pause(); dissolve?.pause() }
    reportState()
    armTick()
  }

  private fun requestBackdrop(index: Int) {
    if (dissolve != null) { pendingBackdrop = index; return }
    val images = scene?.images ?: return
    val incoming = 1-topBackground
    backgrounds[topBackground].alpha = 1f
    backgrounds[incoming].setImageBitmap(images[index].backdrop)
    backdropIndices[incoming] = index
    backgrounds[incoming].alpha = 0f
    backgrounds[incoming].bringToFront()
    val animation = AnimatorSet().apply {
      playTogether(ObjectAnimator.ofFloat(backgrounds[incoming],"alpha",1f))
      duration = 1200L; interpolator = fadeCurve
      addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(ended: Animator) {
          if (closed || dissolve !== ended) return
          topBackground = incoming
          dissolve = null
          val next = pendingBackdrop
          pendingBackdrop = null
          if (next != null && next != index) requestBackdrop(next)
        }
      })
    }
    dissolve = animation
    animation.start()
    if (!hostActive || userPaused) animation.pause()
  }

  private fun discardScene(value: Scene) {
    value.discard()
    budget.setOwner(value.budgetKey,emptyList())
  }

  private fun move(direction: Int) {
    val count = scene?.images?.size ?: return
    advances++
    show((center+direction+count)%count)
  }
  fun next() { if (!closed) move(+1) }
  fun previous() { if (!closed) move(-1) }

  private fun armTick() {
    ui.removeCallbacks(tick)
    if (!closed && !userPaused) ui.postDelayed(tick,intervalMillis.coerceAtLeast(5000L))
  }

  fun setPaused(value: Boolean) {
    if (closed || userPaused == value) return
    userPaused = value
    reportState()
    ui.setActive(hostActive && !userPaused)
    if (value) { motion?.pause(); dissolve?.pause() }
    else if (hostActive) { motion?.resume(); dissolve?.resume() }
  }

  fun setHostActive(value: Boolean) {
    if (closed || hostActive == value) return
    hostActive = value
    reportState()
    ui.setActive(value && !userPaused)
    if (!value) { motion?.pause(); dissolve?.pause() }
    else if (!userPaused) { motion?.resume(); dissolve?.resume() }
  }

  override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
    super.onSizeChanged(w,h,oldw,oldh)
    if (closed) return
    val aspectChanged = oldw > 0 && oldh > 0 && h > 0 &&
        kotlin.math.abs(w.toDouble()/h-oldw.toDouble()/oldh) > .005
    if (scene == null) prepareIfSized(replace = loading && aspectChanged)
    else {
      motion?.removeAllListeners(); motion?.cancel(); motion = null
      relayout()
      cards.forEachIndexed { index, card ->
        val selected = index == center
        card.container.scaleX = if (selected) 1.12f else .7f
        card.container.scaleY = card.container.scaleX
        card.container.alpha = if (selected) 1f else .4f
        card.dimmer.alpha = if (selected) 0f else .45f
      }
      reportState()
      if (aspectChanged) {
        // A source replacement already in flight takes priority over reusing the older deck.
        prepareIfSized(replace = true, reusePhotos = if (loading && loadingSource) null else scene?.images?.map { it.photo })
      }
    }
  }

  private fun reportState() {
    FilmstripRuntimeProbe.record(this, FilmstripRuntimeProbe.State(scene != null, cards.size,
        center, advances, scene?.images?.sumOf { it.bytes } ?: 0L, width, height, userPaused, hostActive))
  }

  override fun close() {
    if (closed) return
    FilmstripScreenshot.clear(this)
    closed = true
    FilmstripRuntimeProbe.clear(this)
    delivery.close(); work.close(); ui.close()
    motion?.removeAllListeners(); motion?.cancel(); motion = null
    dissolve?.removeAllListeners(); dissolve?.cancel(); dissolve = null
    cards.forEach { it.image.setImageDrawable(null) }
    backgrounds.forEach { it.setImageDrawable(null) }
    track.removeAllViews(); cards = emptyList()
    scene?.let { budget.setOwner(it.budgetKey,emptyList()) }
    scene = null; layout = null
    // Never recycle a bitmap previously handed to a View; RenderThread may still own its display list.

  }
}

/** Legacy vignette stays above the photo stage, below the controller's native clock/overlays. */
private class FilmstripVignette(context: Context) : View(context) {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
    super.onSizeChanged(w,h,oldw,oldh)
    if (w <= 0 || h <= 0) return
    // Unit radial gradient transformed into CSS's 130% by 130% ellipse.
    val gradient = RadialGradient(0f,0f,1f,intArrayOf(Color.TRANSPARENT,Color.TRANSPARENT,0x66000000),
        floatArrayOf(0f,.58f,1f),Shader.TileMode.CLAMP)
    gradient.setLocalMatrix(android.graphics.Matrix().apply {
      setScale(w*1.3f,h*1.3f); postTranslate(w*.5f,h*.45f)
    })
    paint.shader = gradient
  }
  override fun onDraw(canvas: Canvas) { canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint) }
}
