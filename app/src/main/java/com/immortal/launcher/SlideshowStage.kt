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
import android.animation.ValueAnimator
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
      val occupied: List<Rect>, val galleryPrint: Boolean, val gallerySpread: Boolean,
      val editing: Boolean = false, val automatic: Boolean = true,
  ) {
    val galleryStyle: Boolean get() = galleryPrint || gallerySpread
  }
  private val layers = arrayOf(Composition(context, budget, "stage0"), Composition(context, budget, "stage1"))
  private var current = 0
  private var fade: ObjectAnimator? = null
  private val dissolvePause = SlideshowDissolvePause { running ->
    if (running) fade?.resume() else fade?.pause()
  }
  private var revision = 0L
  private var pending: Presentation? = null
  private var shown: Presentation? = null
  private var editOriginal: Presentation? = null
  private var editSnapshot: Composition.Snapshot? = null
  private val paused get() = dissolvePause.paused
  private val hostActive get() = dissolvePause.hostActive
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

  /** Spread keeps up to two whole, still photos; Gallery print wins if both flags are supplied. */
  fun show(
      photos: List<SlideshowPhoto>, fill: Boolean, motion: Boolean, dwell: Long,
      occupied: List<Rect> = emptyList(), galleryPrint: Boolean = false,
      gallerySpread: Boolean = false, automatic: Boolean = true,
  ) {
    if (photos.isEmpty() || photos.any { it.bitmap.isRecycled }) return
    // Retain one extra obstacle as an overflow sentinel; gallery styles then take their
    // conservative fallback without copying or searching an arbitrary metadata list.
    val gallery = galleryPrint || gallerySpread
    val measured = if (gallery) galleryOcclusions(occupied) else occupied.map(::Rect)
    val presentation = Presentation(photos.take(if (galleryPrint) 1 else 2),
        fill && !gallery, motion && !gallery, dwell, measured, galleryPrint,
        gallerySpread && !galleryPrint, automatic = automatic)
    if (width <= 0 || height <= 0) {
      pending = presentation
      own("pending", presentation)
      return
    }
    present(presentation)
  }

  /** Reposition retained views only; keep bitmap ownership, alpha and any live dissolve intact. */
  fun refreshGalleryOverlays(occupied: List<Rect>) {
    val old = shown ?: return
    if (!hostActive || !old.galleryStyle || editOriginal != null || width <= 0 || height <= 0) return
    val measured = galleryOcclusions(occupied)
    if (old.occupied == measured) return
    shown = old.copy(occupied = measured)
    // During an existing dissolve both retained compositions need the new reservation.
    layers.forEach { it.refreshGalleryOverlays(measured, width, height) }
  }

  private fun galleryOcclusions(occupied: List<Rect>): List<Rect> =
      occupied.take(SlideshowPresentationPolicy.MAX_OCCLUSIONS + 1).map(::Rect)
          .sortedWith(compareBy<Rect> { it.left }.thenBy { it.top }
              .thenBy { it.right }.thenBy { it.bottom })

  private fun present(presentation: Presentation) {
    // Complete the previous replacement before reusing either slot. A canceled animator's
    // old end callback must never clear a bitmap belonging to a more recent replacement.
    revision++
    editOriginal = null
    editSnapshot = null
    own("edit", null)
    dissolvePause.end()
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
    contentDescription = when {
      presentation.galleryPrint -> "Gallery print"
      presentation.gallerySpread -> "Gallery spread"
      presentation.photos.size == 2 -> "Portrait pair"
      else -> "Photo slideshow"
    }
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
          dissolvePause.end()
        }
      })
      start()
    }
    dissolvePause.begin(presentation.automatic)
    onPresented?.invoke()
  }

  // Explicit navigation started while paused still frees the outgoing slot.
  fun pause() { dissolvePause.setPaused(true); layers.forEach { it.pause() } }
  fun resume() { dissolvePause.setPaused(false); if (hostActive) layers.forEach { it.resume() } }

  /** A covered host freezes the dissolve too; explicit user pause still permits navigation. */
  fun setHostActive(value: Boolean) {
    if (hostActive == value) return
    dissolvePause.setHostActive(value)
    if (!value) layers.forEach { it.pause() }
    else if (!paused) layers.forEach { it.resume() }
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
    val edited = old.copy(photos = listOf(first.copy(adjustment = adjustment)),
        motion = false, editing = true)
    shown = edited
    if (!entering && layers[current].updateAdjustment(first.bitmap, adjustment)) return
    // Editing is deliberate; it never triggers another scene dissolve or acquires an image.
    revision++
    dissolvePause.end()
    fade?.removeAllListeners()
    fade?.cancel()
    fade = null
    layers[1 - current].clear()
    layers[current].display(edited, width, height)
    layers[current].alpha = 1f
  }

  /** Resolve each gesture against the live edited view; a resize may replace that view. */
  fun draggedAdjustment(dx: Float, dy: Float): SlideshowAdjustment? {
    val edited = shown?.takeIf { it.editing } ?: return null
    val photo = edited.photos.singleOrNull() ?: return null
    val adjustment = photo.adjustment ?: return null
    return layers[current].draggedAdjustment(photo.bitmap, adjustment, dx, dy)
  }

  fun finishAdjustment(save: Boolean) {
    val original = editOriginal
    val snapshot = editSnapshot
    val adjustment = shown?.photos?.firstOrNull()?.adjustment
    editOriginal = null
    editSnapshot = null
    if (original != null) {
      // Finish the temporary single-photo editor in the original normal composition.
      val restored = if (save) original.copy(photos = original.photos.mapIndexed { index, photo ->
        if (index == 0) photo.copy(adjustment = adjustment) else photo
      }) else original
      shown = restored
      layers[current].display(restored, width, height)
      if (paused || !hostActive) layers[current].pause()
      // The saved photo uses its new crop; companions retain their prior pose and motion.
      // Normal spread ignores crops, so every photo can keep its original presentation.
      if (snapshot != null) layers[current].restore(snapshot, width, height,
          fromPhoto = if (save && !original.gallerySpread) 1 else 0)
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
    dissolvePause.end()
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
    if (next != null) {
      present(next); own("pending", null)
      layers.forEach { it.measureAfterRebind(w, h) }
    }
    else layers.forEach { it.resize(w, h, paused || !hostActive) }
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
        val transforms: List<SlideshowGeometry.Transform>, val times: Map<Int, Long>,
    )
    private val moves = ArrayList<AnimatorSet>()
    private val movePhotos = ArrayList<Int>()
    private val rememberedTimes = HashMap<Int, Long>()
    private val paths = HashMap<Int, SlideshowGeometry.Motion>()
    private var displayed: Presentation? = null
    private val matte = NativePhotoMatte(MATTE, resources.displayMetrics.density)
    private val printMatte = NativePhotoMatte(PRINT_MATTE, resources.displayMetrics.density)
    init { background = matte; clipChildren = true }

    fun display(p: Presentation, width: Int, height: Int) {
      // Keep admission held across rebinding; a worker cannot reserve scratch
      // in the brief gap between removing and rebuilding this layer's views.
      clear(releasePixels = false)
      displayed = p
      budget.setOwner(owner, p.photos.map {
        SlideshowPixelBudget.Entry(it.bitmap, it.bitmap.allocationByteCount.toLong())
      })
      background = if (p.galleryStyle) printMatte else matte
      visibility = View.VISIBLE
      val gallery = galleryPlans(p, width, height)
      val margin = if (p.photos.size == 2) (20 * resources.displayMetrics.density).toInt() else 0
      val tileWidth = (width - margin) / p.photos.size
      p.photos.forEachIndexed { index, photo ->
        val left = index * (tileWidth + margin)
        val tile = Rect(left, 0, if (index == p.photos.lastIndex) width else left + tileWidth, height)
        val print = gallery?.getOrNull(index)
        val initial = print?.path ?: SlideshowGeometry.plan(
            photo.bitmap.width, photo.bitmap.height, tile.width(), tile.height(), photo.regions,
            p.fill, p.motion && photo.adjustment == null, index % 2 == 1)
        // Reserve a measured free area only if a protected face would intersect an overlay.
        val overlaps = print == null && photo.regions.any { region ->
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
        val area = print?.area?.let { Rect(it.left, it.top, it.right, it.bottom) }
            ?: if (overlaps && photo.adjustment == null) freeArea(tile, p.occupied) else tile
        val holder = FrameLayout(context).apply { clipChildren = true }
        addView(holder, LayoutParams(area.width(), area.height()).apply {
          leftMargin = area.left; topMargin = area.top
        })
        val image = NativePhotoView(context).apply {
          scaleType = ImageView.ScaleType.FIT_XY
          setImageBitmap(photo.bitmap)
          pivotX = 0f; pivotY = 0f
        }
        holder.addView(image, LayoutParams(photo.bitmap.width, photo.bitmap.height))
        val path = if (print != null || area == tile) initial else SlideshowGeometry.plan(
            photo.bitmap.width, photo.bitmap.height, area.width(), area.height(), photo.regions,
            p.fill, p.motion, index % 2 == 1)
        val manual = if (print != null) null else photo.adjustment?.transform(
            photo.bitmap.width, photo.bitmap.height, area.width(), area.height())
        val start = manual ?: path.start
        if (manual == null) paths[index] = path
        image.scaleX = start.scale; image.scaleY = start.scale
        image.translationX = start.x; image.translationY = start.y
        image.showBorder = path.whole && !p.editing &&
            (photo.adjustment == null || p.gallerySpread)
        if (manual == null && path.start != path.end &&
            animationsAllowed()) {
          val end = path.end
          movePhotos.add(index)
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

    /** Reuses only bitmap dimensions and views; no composite images or extra pixel owners. */
    private fun galleryPlans(
        p: Presentation, width: Int, height: Int,
    ): List<SlideshowPresentationPolicy.Plan>? {
      if (!p.galleryStyle) return null
      val occupied = p.occupied.map {
        SlideshowPresentationPolicy.Bounds(it.left, it.top, it.right, it.bottom)
      }
      if (p.galleryPrint) {
        val photo = p.photos.first()
        return listOf(SlideshowPresentationPolicy.galleryPrint(
            photo.bitmap.width, photo.bitmap.height, width, height, resources.displayMetrics.density,
            occupied, photo.adjustment))
      }
      val plans = SlideshowPresentationPolicy.gallerySpread(
          p.photos.map { it.bitmap.width to it.bitmap.height }, width, height, occupied)
      // Normal spread ignores saved crops. Explicit editing keeps the existing manual preview;
      // cancellation restores the complete original pair and its exact retained transforms.
      return if (!p.editing) plans else plans.mapIndexed { index, plan ->
        val photo = p.photos[index]
        val manual = photo.adjustment?.transform(photo.bitmap.width, photo.bitmap.height,
            plan.area.width, plan.area.height) ?: return@mapIndexed plan
        plan.copy(path = SlideshowGeometry.Motion(manual, manual, photo.adjustment.zoom == 1f))
      }
    }

    fun refreshGalleryOverlays(occupied: List<Rect>, width: Int, height: Int) {
      val old = displayed ?: return
      if (!old.galleryStyle || old.occupied == occupied || old.editing) return
      if (old.photos.any { it.bitmap.isRecycled }) return
      displayed = old.copy(occupied = occupied)
      // Saved manual positions already bypass reservations; leave their pose alone.
      if (old.galleryPrint && old.photos.first().adjustment != null) return
      val plans = galleryPlans(displayed ?: return, width, height) ?: return
      plans.forEachIndexed { index, plan ->
        val holder = getChildAt(index) as? FrameLayout ?: return@forEachIndexed
        val image = holder.getChildAt(0) as? ImageView ?: return@forEachIndexed
        val area = plan.area
        val params = holder.layoutParams as LayoutParams
        if (params.width != area.width || params.height != area.height ||
            params.leftMargin != area.left || params.topMargin != area.top) {
          params.width = area.width; params.height = area.height
          params.leftMargin = area.left; params.topMargin = area.top
          holder.layoutParams = params
        }
        val transform = plan.path.start
        image.scaleX = transform.scale; image.scaleY = transform.scale
        image.translationX = transform.x; image.translationY = transform.y
      }
    }

    fun pause() { moves.forEach { it.pause() } }
    fun resume() { moves.forEach { it.resume() } }
    fun resize(width: Int, height: Int, suspended: Boolean) {
      val presentation = displayed ?: return
      val times = motionTimes()
      display(presentation, width, height)
      if (suspended) pause()
      restoreMotionTimes(times)
      measureAfterRebind(width, height)
    }

    fun measureAfterRebind(width: Int, height: Int) {
      if (displayed == null) return
      // onSizeChanged runs after the parent's measure pass. These replacement children
      // need their own measurement before the imminent layout, including under a cover.
      forceLayout()
      measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
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
    fun draggedAdjustment(bitmap: Bitmap, adjustment: SlideshowAdjustment,
        dx: Float, dy: Float): SlideshowAdjustment? {
      if (childCount != 1 || !dx.isFinite() || !dy.isFinite()) return null
      val holder = getChildAt(0) as? FrameLayout ?: return null
      val image = holder.getChildAt(0) as? ImageView ?: return null
      if ((image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== bitmap) return null
      val imageWidth = bitmap.width * image.scaleX
      val imageHeight = bitmap.height * image.scaleY
      val width = holder.layoutParams.width
      val height = holder.layoutParams.height
      if (!imageWidth.isFinite() || !imageHeight.isFinite() || imageWidth <= 0f ||
          imageHeight <= 0f || width <= 0 || height <= 0 ||
          !image.translationX.isFinite() || !image.translationY.isFinite()) return null
      fun focus(position: Float, delta: Float, length: Float, viewport: Int, prior: Float): Float {
        if (length <= viewport) return prior
        val translated = (position + delta).coerceIn(viewport - length, 0f)
        // Persist the visible edge, not hidden overshoot, so reversing responds immediately.
        return ((viewport / 2f - translated) / length).coerceIn(0f, 1f)
      }
      return adjustment.copy(
          focusX = focus(image.translationX, dx, imageWidth, width, adjustment.focusX),
          focusY = focus(image.translationY, dy, imageHeight, height, adjustment.focusY))
    }
    private fun images(): List<ImageView> = (0 until childCount).mapNotNull {
      (getChildAt(it) as? FrameLayout)?.getChildAt(0) as? ImageView
    }
    private fun motionTimes(): Map<Int, Long> {
      if (android.os.Build.VERSION.SDK_INT < 26) return emptyMap()
      return rememberedTimes + movePhotos.zip(moves).associate { (index, move) ->
        val children = move.childAnimations
        // A completed AnimatorSet resets its frame clock; its children retain the end pose.
        val completed = !move.isStarted && children.isNotEmpty() &&
            children.all { it is ValueAnimator && it.animatedFraction == 1f }
        index to if (completed) move.duration else move.currentPlayTime
      }
    }

    private fun restoreMotionTimes(times: Map<Int, Long>, fromPhoto: Int = 0) {
      if (android.os.Build.VERSION.SDK_INT < 26) return
      val duration = (displayed?.dwell ?: return) + DISSOLVE_MS
      rememberedTimes.clear()
      times.filterKeys { it in fromPhoto until childCount }.forEach { (index, time) ->
        rememberedTimes[index] = time.coerceIn(0L, duration)
      }
      movePhotos.zip(moves).forEach { (index, move) ->
        rememberedTimes[index]?.let { move.currentPlayTime = it }
      }
      val pictures = images()
      rememberedTimes.forEach { (index, time) ->
        val path = paths[index] ?: return@forEach
        val image = pictures.getOrNull(index) ?: return@forEach
        // API 26-28 queue a started set's seek while paused. Apply the new geometry now,
        // and leave its queued seek to continue from the same phase when resumed.
        val transform = path.at(time.toFloat() / duration)
        image.scaleX = transform.scale; image.scaleY = transform.scale
        image.translationX = transform.x; image.translationY = transform.y
      }
    }

    fun snapshot(width: Int, height: Int) = Snapshot(width, height, images().map {
      SlideshowGeometry.Transform(it.scaleX, it.translationX, it.translationY)
    }, motionTimes())

    fun restore(snapshot: Snapshot, width: Int, height: Int, fromPhoto: Int = 0) {
      val sameViewport = snapshot.width == width && snapshot.height == height
      if (android.os.Build.VERSION.SDK_INT >= 26) {
        restoreMotionTimes(snapshot.times, fromPhoto)
      } else if (sameViewport) {
        // API-24/25 cannot seek an AnimatorSet. Hold the exact previous pose until the
        // next photo rather than restarting motion from its first endpoint.
        moves.forEach { it.cancel() }
        moves.clear()
        movePhotos.clear()
      }
      if (!sameViewport) return
      images().zip(snapshot.transforms).forEachIndexed { index, (image, transform) ->
        if (index < fromPhoto) return@forEachIndexed
        image.scaleX = transform.scale; image.scaleY = transform.scale
        image.translationX = transform.x; image.translationY = transform.y
      }
    }
    fun clear(releasePixels: Boolean = true) {
      displayed = null
      moves.forEach { it.cancel() }
      moves.clear()
      movePhotos.clear()
      rememberedTimes.clear()
      paths.clear()
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
    private val PRINT_MATTE: Int = Color.rgb(28, 27, 25)
    const val DISSOLVE_MS = 900L
  }
}
