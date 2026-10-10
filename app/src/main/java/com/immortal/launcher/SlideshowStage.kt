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
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

internal data class SlideshowPhoto(
    val key: String,
    val bitmap: Bitmap,
    val regions: List<SlideshowGeometry.Region> = emptyList(),
    val adjustment: SlideshowAdjustment? = null,
    val sourceKey: String = key,
)

/** Fixed bitmap views animated with RenderNode properties, not per-frame decoding or layout. */
internal class SlideshowStage(
    context: Context, private val budget: SlideshowPixelBudget = SlideshowPixelBudget(),
) : FrameLayout(context) {
  private data class Presentation(
      val photos: List<SlideshowPhoto>, val fill: Boolean, val motion: Boolean, val dwell: Long,
      val occupied: List<Rect>,
  )
  private val layers = arrayOf(Composition(context, budget, "stage0"), Composition(context, budget, "stage1"))
  private var current = 0
  private var fade: ObjectAnimator? = null
  private var revision = 0L
  private var pending: Presentation? = null
  private var shown: Presentation? = null
  private var editOriginal: Presentation? = null
  private var editSnapshot: Composition.Snapshot? = null
  private var paused = false
  private var hostActive = true
  var onPresented: (() -> Unit)? = null
  val currentPhoto: SlideshowPhoto? get() = shown?.photos?.firstOrNull()
  val photoCount: Int get() = shown?.photos?.size ?: 0

  init {
    setBackgroundColor(MATTE)
    clipChildren = true
    layers.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    layers[1].visibility = View.INVISIBLE
    contentDescription = "Photo slideshow"
  }

  fun show(
      photos: List<SlideshowPhoto>, fill: Boolean, motion: Boolean, dwell: Long,
      occupied: List<Rect> = emptyList(),
  ) {
    if (photos.isEmpty() || photos.any { it.bitmap.isRecycled }) return
    val presentation = Presentation(photos.take(2), fill, motion, dwell, occupied.map(::Rect))
    if (width <= 0 || height <= 0) {
      pending = presentation
      own("pending", presentation)
      return
    }
    present(presentation)
  }

  private fun present(presentation: Presentation) {
    // Complete the previous replacement before reusing either slot. A canceled animator's
    // old end callback must never clear a bitmap belonging to a more recent replacement.
    revision++
    editOriginal = null
    editSnapshot = null
    own("edit", null)
    fade?.removeAllListeners()
    fade?.cancel()
    fade = null
    layers[current].alpha = 1f
    layers[1 - current].clear()
    val first = shown == null
    val incoming = if (first) current else 1 - current
    val outgoing = current
    val layer = layers[incoming]
    layer.display(presentation, width, height)
    if (paused || !hostActive) layer.pause()
    layer.visibility = View.VISIBLE
    layer.bringToFront()
    shown = presentation
    current = incoming
    contentDescription = if (presentation.photos.size == 2) "Portrait pair" else "Photo slideshow"
    if (first || !animationsAllowed()) {
      layer.alpha = 1f
      if (!first) layers[outgoing].clear()
      onPresented?.invoke()
      return
    }
    layer.alpha = 0f
    val ticket = revision
    // Keep the outgoing scene opaque. Fading both layers simultaneously introduces a
    // dark dip because the incoming alpha also reveals the matte beneath the outgoing.
    fade = ObjectAnimator.ofFloat(layer, View.ALPHA, 0f, 1f).apply {
      duration = DISSOLVE_MS
      interpolator = LinearInterpolator()
      addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
          if (ticket != revision) return
          layers[outgoing].clear()
          fade = null
        }
      })
      start()
      if (!hostActive) pause()
    }
    onPresented?.invoke()
  }

  // Explicit navigation still completes its dissolve while paused, freeing the old slot.
  fun pause() { paused = true; layers.forEach { it.pause() } }
  fun resume() { paused = false; if (hostActive) layers.forEach { it.resume() } }

  /** A covered host freezes the dissolve too; explicit user pause still permits navigation. */
  fun setHostActive(value: Boolean) {
    if (hostActive == value) return
    hostActive = value
    if (!value) { fade?.pause(); layers.forEach { it.pause() } }
    else {
      fade?.resume()
      if (!paused) layers.forEach { it.resume() }
    }
  }

  fun adjust(adjustment: SlideshowAdjustment?) {
    val old = shown ?: return
    val entering = editOriginal == null
    if (entering) {
      editOriginal = old
      editSnapshot = layers[current].snapshot(width, height)
      own("edit", old)
    }
    val first = old.photos.firstOrNull() ?: return
    val edited = old.copy(photos = listOf(first.copy(adjustment = adjustment)), motion = false)
    shown = edited
    if (!entering && layers[current].updateAdjustment(first.bitmap, adjustment)) return
    // Editing is deliberate; it never triggers another scene dissolve or acquires an image.
    revision++
    fade?.removeAllListeners()
    fade?.cancel()
    fade = null
    layers[1 - current].clear()
    layers[current].display(edited, width, height)
    layers[current].alpha = 1f
  }

  fun finishAdjustment(save: Boolean) {
    val original = editOriginal
    val snapshot = editSnapshot
    editOriginal = null
    editSnapshot = null
    if (!save && original != null) {
      shown = original
      layers[current].display(original, width, height)
      if (snapshot != null) layers[current].restore(snapshot, width, height)
      if (paused || !hostActive) layers[current].pause()
      layers[current].alpha = 1f
    }
    own("edit", null)
  }

  fun stop() {
    revision++
    pending = null
    shown = null
    editOriginal = null
    editSnapshot = null
    own("pending", null)
    own("edit", null)
    fade?.removeAllListeners()
    fade?.cancel()
    fade = null
    layers.forEach { it.clear() }
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w <= 0 || h <= 0) return
    val next = pending
    pending = null
    if (next != null) { present(next); own("pending", null) }
    else shown?.let {
      layers[current].display(it, w, h)
      if (paused || !hostActive) layers[current].pause()
    }
  }

  private fun own(name: String, presentation: Presentation?) {
    budget.setOwner(name, presentation?.photos.orEmpty().map {
      SlideshowPixelBudget.Entry(it.bitmap, it.bitmap.allocationByteCount.toLong())
    })
  }

  private class Composition(
      context: Context, private val budget: SlideshowPixelBudget, private val owner: String,
  ) : FrameLayout(context) {
    data class Snapshot(
        val width: Int, val height: Int,
        val transforms: List<SlideshowGeometry.Transform>, val times: List<Long>,
    )
    private val moves = ArrayList<AnimatorSet>()
    init { setBackgroundColor(MATTE); clipChildren = true }

    fun display(p: Presentation, width: Int, height: Int) {
      // Keep admission held across rebinding; a worker cannot reserve scratch
      // in the brief gap between removing and rebuilding this layer's views.
      clear(releasePixels = false)
      budget.setOwner(owner, p.photos.map {
        SlideshowPixelBudget.Entry(it.bitmap, it.bitmap.allocationByteCount.toLong())
      })
      visibility = View.VISIBLE
      val margin = if (p.photos.size == 2) (20 * resources.displayMetrics.density).toInt() else 0
      val tileWidth = (width - margin) / p.photos.size
      p.photos.forEachIndexed { index, photo ->
        val left = index * (tileWidth + margin)
        val tile = Rect(left, 0, if (index == p.photos.lastIndex) width else left + tileWidth, height)
        val initial = SlideshowGeometry.plan(
            photo.bitmap.width, photo.bitmap.height, tile.width(), tile.height(), photo.regions,
            p.fill, p.motion && photo.adjustment == null, index % 2 == 1)
        // Reserve a measured free area only if a protected face would intersect an overlay.
        val overlaps = photo.regions.any { region ->
          val rw = region.right - region.left
          val rh = region.bottom - region.top
          fun protected(transform: SlideshowGeometry.Transform): Rect = Rect(
              kotlin.math.floor(left + transform.x +
                  (region.left - rw * .2f).coerceAtLeast(0f) * photo.bitmap.width * transform.scale).toInt(),
              kotlin.math.floor(transform.y +
                  (region.top - rh * .3f).coerceAtLeast(0f) * photo.bitmap.height * transform.scale).toInt(),
              kotlin.math.ceil(left + transform.x +
                  (region.right + rw * .2f).coerceAtMost(1f) * photo.bitmap.width * transform.scale).toInt(),
              kotlin.math.ceil(transform.y +
                  (region.bottom + rh * .2f).coerceAtMost(1f) * photo.bitmap.height * transform.scale).toInt())
          // Shared interpolation bounds every edge by its endpoints. The swept union is
          // conservative even when an obstacle lies between two otherwise clear endpoints.
          val sweep = protected(initial.start).apply { union(protected(initial.end)) }
          p.occupied.any { Rect.intersects(sweep, it) }
        }
        val area = if (overlaps && photo.adjustment == null) freeArea(tile, p.occupied) else tile
        val holder = FrameLayout(context).apply { clipChildren = true }
        addView(holder, LayoutParams(area.width(), area.height()).apply {
          leftMargin = area.left; topMargin = area.top
        })
        val image = ImageView(context).apply {
          scaleType = ImageView.ScaleType.FIT_XY
          setImageBitmap(photo.bitmap)
          pivotX = 0f; pivotY = 0f
        }
        holder.addView(image, LayoutParams(photo.bitmap.width, photo.bitmap.height))
        val path = if (area == tile) initial else SlideshowGeometry.plan(
            photo.bitmap.width, photo.bitmap.height, area.width(), area.height(), photo.regions,
            p.fill, p.motion, index % 2 == 1)
        val manual = photo.adjustment?.transform(
            photo.bitmap.width, photo.bitmap.height, area.width(), area.height())
        val start = manual ?: path.start
        image.scaleX = start.scale; image.scaleY = start.scale
        image.translationX = start.x; image.translationY = start.y
        if (manual == null && path.start != path.end &&
            animationsAllowed()) {
          val end = path.end
          moves.add(AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(image, View.SCALE_X, start.scale, end.scale),
                ObjectAnimator.ofFloat(image, View.SCALE_Y, start.scale, end.scale),
                ObjectAnimator.ofFloat(image, View.TRANSLATION_X, start.x, end.x),
                ObjectAnimator.ofFloat(image, View.TRANSLATION_Y, start.y, end.y))
            duration = p.dwell + DISSOLVE_MS
            interpolator = LinearInterpolator()
            start()
          })
        }
      }
    }

    fun pause() { moves.forEach { it.pause() } }
    fun resume() { moves.forEach { it.resume() } }
    fun updateAdjustment(bitmap: Bitmap, adjustment: SlideshowAdjustment?): Boolean {
      if (adjustment == null || childCount != 1) return false
      val holder = getChildAt(0) as? FrameLayout ?: return false
      val image = holder.getChildAt(0) as? ImageView ?: return false
      if ((image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== bitmap) return false
      val transform = adjustment.transform(bitmap.width, bitmap.height,
          holder.layoutParams.width, holder.layoutParams.height)
      image.scaleX = transform.scale; image.scaleY = transform.scale
      image.translationX = transform.x; image.translationY = transform.y
      return true
    }
    private fun images(): List<ImageView> = (0 until childCount).mapNotNull {
      (getChildAt(it) as? FrameLayout)?.getChildAt(0) as? ImageView
    }
    fun snapshot(width: Int, height: Int) = Snapshot(width, height, images().map {
      SlideshowGeometry.Transform(it.scaleX, it.translationX, it.translationY)
    }, if (android.os.Build.VERSION.SDK_INT >= 26) moves.map { it.currentPlayTime } else emptyList())

    fun restore(snapshot: Snapshot, width: Int, height: Int) {
      if (snapshot.width != width || snapshot.height != height) return
      if (android.os.Build.VERSION.SDK_INT >= 26) {
        moves.zip(snapshot.times).forEach { (move, time) -> move.currentPlayTime = time }
      } else {
        // API-24/25 cannot seek an AnimatorSet. Hold the exact previous pose until the
        // next photo rather than restarting motion from its first endpoint.
        moves.forEach { it.cancel() }
        moves.clear()
      }
      images().zip(snapshot.transforms).forEach { (image, transform) ->
        image.scaleX = transform.scale; image.scaleY = transform.scale
        image.translationX = transform.x; image.translationY = transform.y
      }
    }
    fun clear(releasePixels: Boolean = true) {
      moves.forEach { it.cancel() }
      moves.clear()
      // Drawable references are released only after their visible layer is detached/cleared.
      // Do not recycle rendered bitmaps while Android may retain a display-list reference.
      removeAllViews()
      if (releasePixels) budget.setOwner(owner, emptyList())
      visibility = View.INVISIBLE
    }

    private fun freeArea(tile: Rect, occupied: List<Rect>): Rect {
      var candidates = listOf(Rect(tile))
      occupied.forEach { obstacle ->
        candidates = candidates.flatMap { area ->
          if (!Rect.intersects(area, obstacle)) listOf(area) else listOf(
              Rect(area.left, area.top, area.right, minOf(area.bottom, obstacle.top)),
              Rect(area.left, maxOf(area.top, obstacle.bottom), area.right, area.bottom),
              Rect(area.left, area.top, minOf(area.right, obstacle.left), area.bottom),
              Rect(maxOf(area.left, obstacle.right), area.top, area.right, area.bottom))
              .filter { it.width() > 0 && it.height() > 0 }
        }.sortedByDescending { it.width().toLong() * it.height() }.take(16)
      }
      return candidates.maxByOrNull { it.width().toLong() * it.height() } ?: tile
    }
  }

  companion object {
    private fun animationsAllowed(): Boolean =
        android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()
    val MATTE: Int = Color.rgb(20, 22, 27)
    const val DISSOLVE_MS = 900L
  }
}
