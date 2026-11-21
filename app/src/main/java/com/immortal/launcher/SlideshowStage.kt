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
import android.graphics.ColorMatrixColorFilter
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
    /** Pre-blurred backdrop + analysis; null when the photo could not be sampled. */
    val look: PhotoLook? = null,
)

/** Fixed bitmap views animated with RenderNode properties, not per-frame decoding or layout. */
internal class SlideshowStage(
    context: Context, private val budget: SlideshowPixelBudget = SlideshowPixelBudget(),
) : FrameLayout(context) {
  private data class Presentation(
      val photos: List<SlideshowPhoto>, val fill: Boolean, val motion: Boolean, val dwell: Long,
      val occupied: List<Rect>, val galleryPrint: Boolean, val gallerySpread: Boolean,
      val editing: Boolean = false, val automatic: Boolean = true,
      val feature: Feature = Feature.NONE, val keepPeople: Boolean = false,
      /** The native style, for the compact (Portal Mini) layouts; LARGE ignores it. */
      val style: CompactStagePlan.Style? = null,
      /** Alternates compact drift/sweep directions per scene; fixed for the scene's lifetime. */
      val parity: Boolean = false,
  ) {
    val galleryStyle: Boolean get() = galleryPrint || gallerySpread
    /** A new-mode scene: one unedited photo without a saved crop. */
    val featureScene: Boolean
      get() = feature != Feature.NONE && !editing && photos.size == 1 && photos[0].adjustment == null
    val dissolveMs: Long get() = when (feature) {
      Feature.SPLIT -> SPLIT_DISSOLVE_MS
      Feature.NIGHT_PHOTO, Feature.SPLIT_NIGHT -> NIGHT_DISSOLVE_MS
      Feature.NONE -> DISSOLVE_MS
    }
  }

  /**
   * The new photo modes; [NONE] is every existing style, drawn exactly as before. [SPLIT_NIGHT] is
   * Split glance in a dark room: the same card, warm and dimmed under the mock's night veil.
   */
  enum class Feature { NONE, SPLIT, NIGHT_PHOTO, SPLIT_NIGHT;
    val split: Boolean get() = this == SPLIT || this == SPLIT_NIGHT
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
  /**
   * A rotation left a two-photo scene that no longer pairs on the new screen (a stacked pair
   * turned, a portrait pair upright): the first photo stays, and the companion (its source key)
   * is handed back so navigation shows it next instead of skipping it.
   */
  var onCompanionDropped: ((anchor: String, companion: String) -> Unit)? = null
  private var compactParity = false
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
      feature: Feature = Feature.NONE, keepPeople: Boolean = false,
      style: CompactStagePlan.Style? = null,
  ) {
    if (photos.isEmpty() || photos.any { it.bitmap.isRecycled }) return
    // Retain one extra obstacle as an overflow sentinel; gallery styles then take their
    // conservative fallback without copying or searching an arbitrary metadata list.
    val gallery = galleryPrint || gallerySpread
    val measured = if (gallery) galleryOcclusions(occupied) else occupied.map(::Rect)
    val presentation = Presentation(photos.take(if (galleryPrint) 1 else 2),
        fill && !gallery, motion && !gallery, dwell, measured, galleryPrint,
        gallerySpread && !galleryPrint, automatic = automatic,
        feature = if (gallery) Feature.NONE else feature, keepPeople = keepPeople,
        style = style, parity = style != null && compactParity.also { compactParity = !it })
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
      duration = presentation.dissolveMs
      interpolator = when (presentation.feature) {
        Feature.SPLIT -> HearthEasing.SOFT
        Feature.NIGHT_PHOTO, Feature.SPLIT_NIGHT -> SINE
        Feature.NONE -> LinearInterpolator()
      }
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
    rotationReveal?.cancel(); rotationReveal = null; alpha = 1f
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
    else {
      val rotated = ScreensaverLayout.rotated(oldw, oldh, w, h)
      if (rotated) dropUnpairedCompanion()
      layers.forEach { it.resize(w, h, paused || !hostActive) }
      if (rotated && shown != null) revealAfterRotation()
    }
  }

  private var rotationReveal: android.view.ViewPropertyAnimator? = null

  /** Re-pair on rotation: a pair that no longer fits the new class keeps only its first photo. */
  private fun dropUnpairedCompanion() {
    val old = shown ?: return
    if (old.photos.size != 2 || old.editing || editOriginal != null) return
    val (a, b) = old.photos
    val cls = ScreensaverLayout.of(context)
    if (CompactStagePlan.pairs(old.style, cls, a.bitmap.width to a.bitmap.height,
            b.bitmap.width to b.bitmap.height)) return
    val single = old.copy(photos = listOf(a))
    shown = single
    layers[current].retain(single)
    onCompanionDropped?.invoke(a.sourceKey, b.sourceKey)
  }

  /**
   * Rotation mid-show (mock): the same photo is re-laid for the new screen under a 20 % dim (set
   * at once, under the system's own rotation animation), held [ScreensaverLayout.ROTATION_DIM_MS],
   * then revealed over [ScreensaverLayout.ROTATION_REVEAL_MS]. No re-decode; the bitmaps are kept.
   */
  private fun revealAfterRotation() {
    rotationReveal?.cancel()
    if (!animationsAllowed()) { alpha = 1f; return }
    alpha = ScreensaverLayout.ROTATION_DIM
    rotationReveal = animate().alpha(1f).setStartDelay(ScreensaverLayout.ROTATION_DIM_MS)
        .setDuration(ScreensaverLayout.ROTATION_REVEAL_MS).setInterpolator(HearthEasing.SOFT)
        .withLayer().withEndAction { rotationReveal = null }
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
    /** This layer holds a compact (Portal Mini) scene: measured gallery reservations don't move it. */
    private var compactLaid = false
    /** Easing of a new-mode settle; null for classic linear motion. */
    private var featureEasing: android.animation.TimeInterpolator? = null
    /** A Night photo scene is one full-bleed cover photo and nothing else (see [displayFeature]). */
    private var singleOpaquePhoto = false
    init { background = matte; clipChildren = true }

    // The dissolve fades this whole layer. A single full-bleed photo cannot overlap itself, so
    // its alpha is applied to the photo directly instead of through a full-screen offscreen
    // layer every frame. Every other scene keeps the default (exactly as before).
    override fun hasOverlappingRendering(): Boolean = !singleOpaquePhoto

    fun display(p: Presentation, width: Int, height: Int) {
      // Keep admission held across rebinding; a worker cannot reserve scratch
      // in the brief gap between removing and rebuilding this layer's views.
      clear(releasePixels = false)
      displayed = p
      budget.setOwner(owner, p.photos.map {
        SlideshowPixelBudget.Entry(it.bitmap, it.bitmap.allocationByteCount.toLong())
      })
      if (p.featureScene && displayFeature(p, p.photos[0], width, height)) return
      if (!p.editing && p.photos.all { it.adjustment == null } && displayCompact(p, width, height)) return
      background = if (p.galleryStyle) printMatte else matte
      visibility = View.VISIBLE
      val gallery = galleryPlans(p, width, height)
      // Portrait panels only (Portal Mini): a badly mismatched single photo is shown whole over
      // its own blurred backdrop rather than hard-cropped or floated on the flat matte.
      val single = p.photos.singleOrNull()
      val portraitWhole = single != null && gallery == null && !p.editing && single.adjustment == null &&
          height > width && PremiumFraming.mismatch(single.bitmap.width, single.bitmap.height,
              width, height) > PremiumFraming.MISMATCH
      val look = single?.look
      if (portraitWhole && look != null) {
        background = null
        val fill = PhotoBackdropView(context)
        addView(fill, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val contain = minOf(width.toFloat() / single.bitmap.width, height.toFloat() / single.bitmap.height)
        val w = single.bitmap.width * contain
        val h = single.bitmap.height * contain
        fill.show(look.backdrop, floatArrayOf((width - w) / 2f, (height - h) / 2f, (width + w) / 2f,
            (height + h) / 2f), 0f, 0f)
      }
      val margin = if (p.photos.size == 2) (20 * resources.displayMetrics.density).toInt() else 0
      val tileWidth = (width - margin) / p.photos.size
      p.photos.forEachIndexed { index, photo ->
        val left = index * (tileWidth + margin)
        val tile = Rect(left, 0, if (index == p.photos.lastIndex) width else left + tileWidth, height)
        val print = gallery?.getOrNull(index)
        val initial = print?.path ?: SlideshowGeometry.plan(
            photo.bitmap.width, photo.bitmap.height, tile.width(), tile.height(), photo.regions,
            p.fill && !portraitWhole, p.motion && photo.adjustment == null && !portraitWhole, index % 2 == 1)
        // Reserve a measured free area only if a protected face would intersect an overlay.
        // A whole portrait-panel photo keeps its backdrop hole exact, so it is never shrunk.
        val overlaps = print == null && !portraitWhole && photo.regions.any { region ->
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

    /**
     * Split glance: a rounded photo card with a settle motion over the photo's dim blurred
     * backdrop. Night photo: the photo full-bleed under a warm multiply with a slow settle. False
     * without a look, so the classic presentation is used instead.
     */
    private fun displayFeature(p: Presentation, photo: SlideshowPhoto, width: Int, height: Int): Boolean {
      val look = photo.look ?: return false
      val density = resources.displayMetrics.density
      val split = p.feature.split
      // The night veil as a colour matrix on the draw (free in the shader; no full-screen layer).
      val nightLevel = if (p.feature == Feature.SPLIT_NIGHT) 1f else 0f
      val cls = ScreensaverLayout.of(context)
      // The card's 30-unit corner is a mock unit like the rest of Split glance ([HearthMockUnit]);
      // compact windows use the Mini mock's 28 dp corner.
      val corner = if (cls.compact) ScreensaverLayout.splitType(cls).cornerDp * density
          else SPLIT_CORNER_DP * density * HearthMockUnit.boost(width, height, density)
      val box = (if (split) SplitGlancePanel.layout(width, height, density, cls).first
          else SplitGlancePanel.Box(0, 0, width, height)).let { Rect(it.left, it.top, it.right, it.bottom) }
      val bw = photo.bitmap.width
      val bh = photo.bitmap.height
      // Night photo keeps the day's whole-photo rule: a badly mismatched photo (a portrait on the
      // Portal+, a landscape on the upright Mini) is shown whole over its own warm backdrop instead
      // of losing more than half of itself to the cover crop. Matching photos still cover.
      val nightWhole = !split && ScreensaverLayout.nightWhole(bw, bh, width, height)
      val parity = (featureParity++ and 1) == 1
      val plan = if (nightWhole) PremiumFraming.Frame(ScreensaverLayout.wholeMotion(bw, bh,
          ScreensaverLayout.nightZone(cls, width, height, density), p.motion), true)
      else PremiumFraming.plan(bw, bh, box.width(), box.height(), photo.regions, p.keepPeople,
          fill = true, motion = p.motion, focus = look.analysis.focus,
          target = PremiumFraming.Target(.5f, .45f), reverse = parity,
          mismatchLimit = Double.MAX_VALUE)
      visibility = View.VISIBLE
      if (nightWhole) {
        background = null
        addView(PhotoBackdropView(context).also {
          it.show(look.backdrop, ScreensaverLayout.rectOf(plan.motion.start, bw, bh), 0f, 1f, NIGHT_BACKDROP)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
      } else if (split) {
        background = null
        val radius = corner
        addView(PhotoBackdropView(context).also {
          it.show(look.backdrop, floatArrayOf(box.left + radius, box.top + radius, box.right - radius,
              box.bottom - radius), 0f, nightLevel, SPLIT_BACKDROP)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
      } else {
        // The cover photo fills the whole panel, so a black backing would be pure overdraw.
        background = null
        singleOpaquePhoto = true
      }
      val holder = FrameLayout(context).apply {
        clipChildren = true
        if (split) {
          clipToOutline = true
          outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, v.width, v.height, corner)
          }
          elevation = 20f * density
          setBackgroundColor(Color.BLACK)
        }
      }
      addView(holder, LayoutParams(box.width(), box.height()).apply { leftMargin = box.left; topMargin = box.top })
      val image = NativePhotoView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setImageBitmap(photo.bitmap)
        pivotX = 0f; pivotY = 0f
        if (!split) colorFilter = ColorMatrixColorFilter(NightPhotoOverlay.warmFilter())
        else if (nightLevel > 0f) colorFilter = ColorMatrixColorFilter(PhotoBackdropView.warmth(nightLevel))
      }
      holder.addView(image, LayoutParams(bw, bh))
      val start = plan.motion.start
      image.scaleX = start.scale; image.scaleY = start.scale
      image.translationX = start.x; image.translationY = start.y
      paths[0] = plan.motion
      featureEasing = if (split) HearthEasing.DRIFT else SINE
      if (plan.motion.start != plan.motion.end && animationsAllowed()) {
        val end = plan.motion.end
        movePhotos.add(0)
        moves.add(AnimatorSet().apply {
          playTogether(
              ObjectAnimator.ofFloat(image, View.SCALE_X, start.scale, end.scale),
              ObjectAnimator.ofFloat(image, View.SCALE_Y, start.scale, end.scale),
              ObjectAnimator.ofFloat(image, View.TRANSLATION_X, start.x, end.x),
              ObjectAnimator.ofFloat(image, View.TRANSLATION_Y, start.y, end.y))
          duration = motionDuration(p)
          interpolator = featureEasing
          start()
        })
      }
      return true
    }

    /** Keep [p] as this layer's scene (re-pairing dropped a companion); the next display uses it. */
    fun retain(p: Presentation) {
      if (displayed == null) return
      displayed = p
      budget.setOwner(owner, p.photos.map {
        SlideshowPixelBudget.Entry(it.bitmap, it.bitmap.allocationByteCount.toLong())
      })
    }

    /**
     * A compact window's native style ([CompactStagePlan]): stacked or paired cards, full-bleed
     * drift, panorama sweep or whole over the backdrop. False on LARGE (or a style unchanged on this
     * class), so the shipped presentation below runs exactly as before.
     */
    private fun displayCompact(p: Presentation, width: Int, height: Int): Boolean {
      val cls = ScreensaverLayout.of(context)
      if (!cls.compact || p.style == null) return false
      val density = resources.displayMetrics.density
      val first = p.photos[0]
      val plan = CompactStagePlan.plan(p.style, cls, p.photos.map { it.bitmap.width to it.bitmap.height },
          width, height, density, p.motion, p.parity, backdrop = first.look != null,
          regions = if (p.keepPeople) first.regions else emptyList(),
          occupied = p.occupied.map { ScreensaverLayout.Box(it.left, it.top, it.right, it.bottom) }) ?: return false
      compactLaid = true
      visibility = View.VISIBLE
      background = when (plan.ground) {
        CompactStagePlan.Ground.BLACK -> android.graphics.drawable.ColorDrawable(Color.BLACK)
        CompactStagePlan.Ground.MATTE -> matte
        CompactStagePlan.Ground.PRINT -> printMatte
        CompactStagePlan.Ground.BACKDROP -> null
      }
      val look = first.look
      if (plan.ground == CompactStagePlan.Ground.BACKDROP && look != null) {
        // One card: cut its hole (inset by the corner) so a dissolve never blends the backdrop
        // through the photo. Two cards: no hole; the backdrop is a single cheap opaque draw.
        val hole = plan.tiles.singleOrNull()?.let { t ->
          val inset = t.cornerDp * density
          floatArrayOf(t.box.left + inset, t.box.top + inset, t.box.right - inset, t.box.bottom - inset)
        } ?: floatArrayOf(0f, 0f, 0f, 0f)
        addView(PhotoBackdropView(context).also { it.show(look.backdrop, hole, 0f, 0f, plan.backdropBrightness) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
      }
      // A single full-screen cover (fill or sweep) is opaque: draw its alpha directly.
      val full = plan.tiles.singleOrNull()?.box?.let { it.left == 0 && it.top == 0 && it.width == width && it.height == height }
      if (plan.ground == CompactStagePlan.Ground.BLACK && full == true && plan.tiles[0].motion.start.let {
            it.x <= 0f && it.y <= 0f }) { background = null; singleOpaquePhoto = true }
      plan.tiles.forEachIndexed { index, tile ->
        val photo = p.photos[tile.photo]
        val radius = tile.cornerDp * density
        val holder = FrameLayout(context).apply {
          clipChildren = true
          if (radius > 0f || tile.elevationDp > 0f) {
            clipToOutline = radius > 0f
            outlineProvider = object : android.view.ViewOutlineProvider() {
              override fun getOutline(v: View, outline: android.graphics.Outline) =
                  outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
            elevation = tile.elevationDp * density
            if (tile.elevationDp > 0f) setBackgroundColor(Color.BLACK)
          }
        }
        addView(holder, LayoutParams(tile.box.width, tile.box.height).apply {
          leftMargin = tile.box.left; topMargin = tile.box.top
        })
        val image = NativePhotoView(context).apply {
          scaleType = ImageView.ScaleType.FIT_XY
          setImageBitmap(photo.bitmap)
          pivotX = 0f; pivotY = 0f
          showBorder = tile.border
        }
        holder.addView(image, LayoutParams(photo.bitmap.width, photo.bitmap.height))
        val start = tile.motion.start
        image.scaleX = start.scale; image.scaleY = start.scale
        image.translationX = start.x; image.translationY = start.y
        paths[index] = tile.motion
        if (tile.motion.start != tile.motion.end && animationsAllowed()) {
          val end = tile.motion.end
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
      return true
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
      if (compactLaid) { displayed = old.copy(occupied = occupied); return }
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
      val duration = motionDuration(displayed ?: return)
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
        val linear = time.toFloat() / duration
        val transform = path.at(featureEasing?.getInterpolation(linear) ?: linear)
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
      featureEasing = null
      singleOpaquePhoto = false
      compactLaid = false
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
    const val SPLIT_DISSOLVE_MS = 1800L
    const val NIGHT_DISSOLVE_MS = 4000L
    /** New-mode settles finish well before the next photo, then hold still (no idle frames). */
    const val SPLIT_SETTLE_MS = 9000L
    const val NIGHT_SETTLE_MS = 14000L
    private const val SPLIT_CORNER_DP = 30f
    /** Split glance backdrop is dimmer than the ambient fill (mock .38). */
    private const val SPLIT_BACKDROP = .38f
    /** A whole Night photo's backdrop (mock .5 under the warm night grade). */
    private const val NIGHT_BACKDROP = .5f
    private var featureParity = 0
    /** CSS sine ease-in-out, the mock's night dissolve and drift. */
    private val SINE = android.animation.TimeInterpolator { t -> (-(Math.cos(Math.PI * t) - 1) / 2).toFloat() }

    private fun motionDuration(p: Presentation): Long = when {
      !p.featureScene -> p.dwell + DISSOLVE_MS
      p.feature.split -> minOf(SPLIT_SETTLE_MS, p.dwell + p.dissolveMs)
      else -> minOf(NIGHT_SETTLE_MS, p.dwell + p.dissolveMs)
    }

    private fun animationsAllowed(): Boolean =
        android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()
    val MATTE: Int = Color.rgb(20, 22, 27)
    private val PRINT_MATTE: Int = Color.rgb(28, 27, 25)
    const val DISSOLVE_MS = 900L
  }
}
