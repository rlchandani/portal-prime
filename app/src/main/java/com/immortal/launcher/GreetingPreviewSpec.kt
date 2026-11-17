/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

internal data class GreetingPreviewSpec(
    val greeting: String?,
    val showClock: Boolean,
    val showDate: Boolean,
    val scale: Float,
    val backgroundOpacity: Float,
    val greetingColor: Int,
    val clockColor: Int,
    val dateColor: Int,
    val caption: String,
)

/**
 * Welcome Greeting › Preview, pure: the overlay the photo frame shows when someone walks up,
 * with the same time-of-day greeting the overlay picks (PhotoFrameController) and the saved
 * size, opacity, colours and duration.
 */
internal object GreetingPreview {
    private const val NIGHT_UNTIL = 5
    private const val MORNING_UNTIL = 12
    private const val AFTERNOON_UNTIL = 17
    private const val EVENING_UNTIL = 22
    private const val MS_PER_SECOND = 1_000f
    private const val SMALL = .8f
    private const val LARGE = 1.25f

    fun greetingFor(hour: Int, settings: WelcomeConfig.Settings): String = when {
        hour < NIGHT_UNTIL -> settings.greetingNight
        hour < MORNING_UNTIL -> settings.greetingMorning
        hour < AFTERNOON_UNTIL -> settings.greetingAfternoon
        hour < EVENING_UNTIL -> settings.greetingEvening
        else -> settings.greetingNight
    }

    fun fullGreeting(hour: Int, settings: WelcomeConfig.Settings): String {
        val word = greetingFor(hour, settings)
        return if (settings.userName.isBlank()) word else "$word, ${settings.userName}"
    }

    fun scale(textScale: String): Float = when (textScale) {
        WelcomeConfig.SCALE_SMALL -> SMALL
        WelcomeConfig.SCALE_LARGE -> LARGE
        else -> 1f
    }

    fun caption(settings: WelcomeConfig.Settings): String {
        val seconds = WelcomeConfig.clampDuration(settings.durationMs) / MS_PER_SECOND
        val spoken = if (settings.enableTts) " · spoken" else ""
        return "Shown for ${"%.1f".format(java.util.Locale.US, seconds)} s$spoken"
    }

    fun spec(hour: Int, settings: WelcomeConfig.Settings) = GreetingPreviewSpec(
        greeting = if (settings.showGreeting) fullGreeting(hour, settings) else null,
        showClock = settings.showClock,
        showDate = settings.showDate,
        scale = scale(settings.textScale),
        backgroundOpacity = WelcomeConfig.clampOpacity(settings.backgroundOpacity),
        greetingColor = settings.greetingColor,
        clockColor = settings.clockColor,
        dateColor = settings.dateColor,
        caption = caption(settings),
    )
}
