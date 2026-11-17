/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** How one preview slide frames its photos, after the photo-frame style it stands for. */
internal enum class SlideLayout { FILL, PAIR, WHOLE, PRINT }

/** How the preview changes slides: the native styles crossfade; the push styles slide across. */
internal enum class SlideTransition { CROSSFADE, PUSH }

internal data class SlideshowPreviewSpec(
    val on: Boolean,
    /** One layout per preview slide; Shuffle mixes styles. */
    val slides: List<SlideLayout>,
    val transition: SlideTransition,
    /** Gentle photo motion (slow zoom and drift) on the showing slide. */
    val motion: Boolean,
    val showClock: Boolean,
    val showNowPlaying: Boolean,
    val caption: String,
)

/**
 * Photo Frame's live preview, pure: what the inline stage shows for the saved style. The real
 * interval is shown in the caption; the preview itself changes every [SLIDE_MS].
 */
internal object SlideshowPreview {
    const val SLIDE_MS = 4_000L
    const val CROSSFADE_MS = 1_100
    const val PUSH_MS = 900
    /** One leg of the gentle-motion drift (it then eases back). */
    const val MOTION_MS = 7_000
    const val MOTION_START_SCALE = 1.02f
    const val MOTION_END_SCALE = 1.12f
    const val MOTION_DRIFT_X = -.02f
    const val MOTION_DRIFT_Y = -.015f
    const val SLIDE_COUNT = 3
    private const val SECONDS_PER_MINUTE = 60
    private const val EXISTING_SUFFIX = " (existing)"

    private val SHUFFLE = listOf(SlideLayout.FILL, SlideLayout.PAIR, SlideLayout.PRINT)
    private val PAIRED = setOf(ScreensaverConfig.MODE_PORTRAITS, ScreensaverConfig.MODE_GALLERY_SPREAD, "diptych", "mosaic")
    private val PRINTED = setOf(ScreensaverConfig.MODE_GALLERY_PRINT, "framed", "polaroid", "galleryWall")
    private val PUSHED = setOf("push", "filmstrip")

    fun layoutFor(mode: String, fit: String): SlideLayout = when {
        mode in PAIRED -> SlideLayout.PAIR
        mode in PRINTED -> SlideLayout.PRINT
        mode == ScreensaverConfig.MODE_WHOLE -> SlideLayout.WHOLE
        mode == ScreensaverConfig.MODE_CLASSIC && fit == ScreensaverConfig.FIT_FIT -> SlideLayout.WHOLE
        else -> SlideLayout.FILL
    }

    fun slidesFor(mode: String, fit: String): List<SlideLayout> =
        if (mode == ScreensaverConfig.MODE_SHUFFLE) SHUFFLE else List(SLIDE_COUNT) { layoutFor(mode, fit) }

    fun transitionFor(mode: String): SlideTransition = if (mode in PUSHED) SlideTransition.PUSH else SlideTransition.CROSSFADE

    fun intervalLabel(seconds: Int): String =
        if (seconds >= SECONDS_PER_MINUTE && seconds % SECONDS_PER_MINUTE == 0) "${seconds / SECONDS_PER_MINUTE} min"
        else "$seconds s"

    fun spec(settings: ScreensaverConfig.Settings): SlideshowPreviewSpec {
        val motion = settings.photoMotion && ScreensaverConfig.supportsPhotoMotion(settings.mode)
        val style = ScreensaverConfig.modeLabel(settings.mode).removeSuffix(EXISTING_SUFFIX)
        val caption = if (!settings.enabled) "Photo frame is off"
            else "$style · ${if (motion) "motion" else "still"} · every ${intervalLabel(settings.intervalSec)} (preview: ${SLIDE_MS / 1_000} s)"
        return SlideshowPreviewSpec(
            on = settings.enabled,
            slides = slidesFor(settings.mode, settings.fit),
            transition = transitionFor(settings.mode),
            motion = motion,
            showClock = settings.facesEnabled,
            showNowPlaying = settings.showNowPlaying,
            caption = caption,
        )
    }

    /** The slide after [index], wrapping. */
    fun next(index: Int, count: Int = SLIDE_COUNT): Int = if (count <= 0) 0 else (index + 1) % count
}
