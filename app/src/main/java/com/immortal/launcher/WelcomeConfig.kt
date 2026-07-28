/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

/**
 * User configuration for the welcome-back overlay shown when the screensaver
 * starts. Persisted across restarts.
 */
object WelcomeConfig {

  private const val PREFS = "immortal_welcome"

  data class Settings(
      // Display duration in milliseconds before auto-dismiss
      val durationMs: Int = 3200,

      // Greeting text for different times of day (user-customizable)
      val greetingNight: String = "Good night",      // 22:00 - 04:59
      val greetingMorning: String = "Good morning",  // 05:00 - 11:59
      val greetingAfternoon: String = "Good afternoon", // 12:00 - 16:59
      val greetingEvening: String = "Good evening",  // 17:00 - 21:59

      // User's name (appended to greeting if not empty)
      val userName: String = "",

      // Text colors (ARGB hex)
      val greetingColor: Int = 0xFFDADADA.toInt(),
      val clockColor: Int = 0xFFFFFFFF.toInt(),
      val dateColor: Int = 0xFFDADADA.toInt(),

      // Background scrim opacity (0.0 = transparent, 1.0 = opaque)
      val backgroundOpacity: Float = 0.7f,

      // Overall text size as one preset (Small / Medium / Large) — consolidated from the three
      // free-float per-element sizes. The per-element SP values below are derived from it, so the
      // renderer keeps reading greetingSize/clockSize/dateSize unchanged.
      val textScale: String = SCALE_MEDIUM,

      // Show/hide individual elements
      val showGreeting: Boolean = true,
      val showClock: Boolean = true,
      val showDate: Boolean = true,

      // Letter spacing for greeting text
      val greetingLetterSpacing: Float = 0.08f,

      // Text-to-speech
      val enableTts: Boolean = false,

      // Android TextToSpeech voice name (TextToSpeech.Voice.getName()); "" = engine default.
      // The greeting speaks through Android TTS — Piper neural TTS was removed because its
      // model download is unreliable on the Portal's connection. See project notes.
      val ttsVoice: String = "",
  ) {
    // Per-element SP sizes derived from [textScale]. Kept as properties so the renderer
    // (PhotoFrameController) reads greetingSize/clockSize/dateSize exactly as before.
    val greetingSize: Float get() = 26f * scaleFactor(textScale)
    val clockSize: Float get() = 88f * scaleFactor(textScale)
    val dateSize: Float get() = 22f * scaleFactor(textScale)
  }

  const val SCALE_SMALL = "small"
  const val SCALE_MEDIUM = "medium"
  const val SCALE_LARGE = "large"

  /** Multiplier applied to the base per-element sizes for each preset. */
  private fun scaleFactor(scale: String): Float = when (scale) {
    SCALE_SMALL -> 0.8f
    SCALE_LARGE -> 1.25f
    else -> 1f // medium / unknown
  }

  fun clampDuration(ms: Int): Int = ms.coerceIn(1000, 10000)
  fun clampOpacity(opacity: Float): Float = opacity.coerceIn(0f, 1f)

  /** Bucket a legacy free-float greeting size (base 26sp) into the nearest S/M/L preset, so an
   *  existing install keeps a comparable size instead of snapping to Medium. */
  private fun scaleFromLegacy(greetingSize: Float): String {
    // Thresholds at the midpoints between the presets' greeting sizes (20.8 / 26 / 32.5).
    return when {
      greetingSize < 23.4f -> SCALE_SMALL
      greetingSize > 29.25f -> SCALE_LARGE
      else -> SCALE_MEDIUM
    }
  }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): Settings {
    val p = prefs(context)
    return Settings(
        durationMs = clampDuration(p.getInt("duration_ms", 3200)),
        greetingNight = p.getString("greeting_night", "Good night") ?: "Good night",
        greetingMorning = p.getString("greeting_morning", "Good morning") ?: "Good morning",
        greetingAfternoon = p.getString("greeting_afternoon", "Good afternoon") ?: "Good afternoon",
        greetingEvening = p.getString("greeting_evening", "Good evening") ?: "Good evening",
        userName = p.getString("user_name", "") ?: "",
        greetingColor = p.getInt("greeting_color", 0xFFDADADA.toInt()),
        clockColor = p.getInt("clock_color", 0xFFFFFFFF.toInt()),
        dateColor = p.getInt("date_color", 0xFFDADADA.toInt()),
        backgroundOpacity = clampOpacity(p.getFloat("background_opacity", 0.7f)),
        textScale = loadTextScale(p),
        showGreeting = p.getBoolean("show_greeting", true),
        showClock = p.getBoolean("show_clock", true),
        showDate = p.getBoolean("show_date", true),
        greetingLetterSpacing = p.getFloat("greeting_letter_spacing", 0.08f),
        enableTts = p.getBoolean("enable_tts", false),
        ttsVoice = p.getString("tts_voice", "") ?: "",
    )
  }

  fun setDuration(context: Context, ms: Int) {
    prefs(context).edit().putInt("duration_ms", clampDuration(ms)).apply()
  }

  fun setGreetingNight(context: Context, text: String) {
    prefs(context).edit().putString("greeting_night", text).apply()
  }

  fun setGreetingMorning(context: Context, text: String) {
    prefs(context).edit().putString("greeting_morning", text).apply()
  }

  fun setGreetingAfternoon(context: Context, text: String) {
    prefs(context).edit().putString("greeting_afternoon", text).apply()
  }

  fun setGreetingEvening(context: Context, text: String) {
    prefs(context).edit().putString("greeting_evening", text).apply()
  }

  fun setGreetingColor(context: Context, color: Int) {
    prefs(context).edit().putInt("greeting_color", color).apply()
  }

  fun setClockColor(context: Context, color: Int) {
    prefs(context).edit().putInt("clock_color", color).apply()
  }

  fun setDateColor(context: Context, color: Int) {
    prefs(context).edit().putInt("date_color", color).apply()
  }

  fun setBackgroundOpacity(context: Context, opacity: Float) {
    prefs(context).edit().putFloat("background_opacity", clampOpacity(opacity)).apply()
  }

  /** The one text-size preset. Reads the new `text_scale` key; else migrates from the legacy
   *  free-float `greeting_size` by bucketing to the nearest preset; else Medium. */
  private fun loadTextScale(p: android.content.SharedPreferences): String {
    p.getString("text_scale", null)?.let { return it }
    if (p.contains("greeting_size")) return scaleFromLegacy(p.getFloat("greeting_size", 26f))
    return SCALE_MEDIUM
  }

  fun setTextScale(context: Context, scale: String) {
    prefs(context).edit().putString("text_scale", scale).apply()
  }

  fun setShowGreeting(context: Context, show: Boolean) {
    prefs(context).edit().putBoolean("show_greeting", show).apply()
  }

  fun setShowClock(context: Context, show: Boolean) {
    prefs(context).edit().putBoolean("show_clock", show).apply()
  }

  fun setShowDate(context: Context, show: Boolean) {
    prefs(context).edit().putBoolean("show_date", show).apply()
  }

  fun setGreetingLetterSpacing(context: Context, spacing: Float) {
    prefs(context).edit().putFloat("greeting_letter_spacing", spacing).apply()
  }

  fun setUserName(context: Context, name: String) {
    prefs(context).edit().putString("user_name", name).apply()
  }

  fun setEnableTts(context: Context, enable: Boolean) {
    prefs(context).edit().putBoolean("enable_tts", enable).apply()
  }

  fun setTtsVoice(context: Context, name: String) {
    prefs(context).edit().putString("tts_voice", name).apply()
  }

}
