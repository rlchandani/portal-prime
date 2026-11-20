/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.immortal.launcher.PhotoFrameViews.Companion.MATCH
import com.immortal.launcher.PhotoFrameViews.Companion.WRAP
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val SHERPA_VOICE_PARAM = "sherpa_voice_name"

/**
 * The welcome-back overlay and its spoken greeting (fork feature; off unless [WelcomeConfig]
 * enables it). [post]/[remove] are the frame's pausable main-thread queue.
 */
internal class PhotoFrameWelcome(
    private val context: Context,
    private val views: PhotoFrameViews,
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
  lateinit var overlay: View
    private set
  var visible = false
    private set
  private val dismissRunnable = Runnable { dismissAnimated() }
  private var tts: TextToSpeech? = null
  @Volatile private var ttsReady = false
  @Volatile private var pendingSpeech: String? = null

  fun build(): View {
    val welcomeCfg = WelcomeConfig.load(context)

    val overlay = FrameLayout(context)
    // Semi-opaque dark scrim so the photo is faintly visible behind the greeting.
    val bgAlpha = (welcomeCfg.backgroundOpacity * 255).toInt()
    overlay.setBackgroundColor((bgAlpha shl 24) or 0x000000)

    val col = LinearLayout(context)
    col.orientation = LinearLayout.VERTICAL
    col.gravity = Gravity.CENTER_HORIZONTAL
    // Add horizontal padding to prevent text cutoff at screen edges
    col.setPadding(views.dp(40), views.dp(20), views.dp(40), views.dp(20))

    val fullGreeting = greeting(welcomeCfg, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))

    if (welcomeCfg.showGreeting) {
      val greetingView = views.text(welcomeCfg.greetingSize, welcomeCfg.greetingColor, false)
      greetingView.text = fullGreeting
      greetingView.gravity = Gravity.CENTER
      greetingView.letterSpacing = welcomeCfg.greetingLetterSpacing
      greetingView.maxLines = 2
      val greetingLp = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT)
      col.addView(greetingView, greetingLp)
    }

    if (welcomeCfg.showClock) {
      val clockPattern = if (ImmortalSettings.use24HourClock(context)) "H:mm" else "h:mm"
      val clockView = views.text(welcomeCfg.clockSize, welcomeCfg.clockColor, true)
      clockView.text = SimpleDateFormat(clockPattern, Locale.getDefault()).format(Date())
      clockView.gravity = Gravity.CENTER
      clockView.maxLines = 1
      val clockLp = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT)
      col.addView(clockView, clockLp)
    }

    if (welcomeCfg.showDate) {
      val dateView = views.text(welcomeCfg.dateSize, welcomeCfg.dateColor, false)
      dateView.text = DateFormatter.format(Date(), "EEEEMMMd")
      dateView.gravity = Gravity.CENTER
      dateView.maxLines = 1
      val dateLp = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT)
      dateLp.topMargin = views.dp(4)
      col.addView(dateView, dateLp)
    }

    // Use MATCH_PARENT width with the padding above to ensure text stays visible
    overlay.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
    this.overlay = overlay
    return overlay
  }

  /** Bring up the device's TTS voice: the household's choice, else its best offline US voice. */
  fun startVoice(welcomeCfg: WelcomeConfig.Settings) {
    tts = TextToSpeech(context) { status ->
      if (status == TextToSpeech.SUCCESS && !ttsReady) {
        tts?.language = Locale.US
        tts?.setPitch(1.0f)
        tts?.setSpeechRate(0.9f)
        // Apply the user's chosen voice; if none chosen, auto-pick the highest-quality
        // voice the device has so the greeting sounds as good as possible by default.
        runCatching {
          val voices = tts?.voices
          val chosen =
              if (welcomeCfg.ttsVoice.isNotBlank()) voices?.firstOrNull { it.name == welcomeCfg.ttsVoice }
              else voices
                  ?.filter { it.locale.language == Locale.US.language && !it.isNetworkConnectionRequired }
                  ?.maxByOrNull { it.quality }
          chosen?.let {
            tts?.language = it.locale
            tts?.voice = it
          }
        }
        ttsReady = true
        pendingSpeech?.let { text ->
          speak(text, welcomeCfg)
          pendingSpeech = null
        }
      }
    }
  }

  fun show() {
    val welcomeCfg = WelcomeConfig.load(context)
    visible = true
    overlay.alpha = 0f
    overlay.visibility = View.VISIBLE
    overlay.animate().alpha(1f).setDuration(500).start()

    // Speak the greeting if TTS is enabled
    if (welcomeCfg.enableTts) {
      val fullGreeting = greeting(welcomeCfg, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
      // Speak immediately if TTS is ready, otherwise queue it
      if (ttsReady) {
        speak(fullGreeting, welcomeCfg)
      } else {
        pendingSpeech = fullGreeting
      }
    }

    // Auto-dismiss after configured duration.
    post(dismissRunnable, welcomeCfg.durationMs.toLong())
  }

  /** Dismiss immediately (e.g. on tap). */
  fun dismiss() {
    if (!visible) return
    remove(dismissRunnable)
    tts?.stop()
    pendingSpeech = null
    dismissAnimated()
  }

  private fun dismissAnimated() {
    visible = false
    overlay.animate()
        .alpha(0f)
        .setDuration(600)
        .withEndAction { overlay.visibility = View.GONE }
        .start()
  }

  private fun speak(text: String, cfg: WelcomeConfig.Settings) {
    val params = ttsParams(cfg)
    runCatching {
      tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "welcome_greeting")
    }.onFailure { Log.w(PhotoFrameController.TAG, "Welcome TTS speak failed", it) }
  }

  private fun ttsParams(cfg: WelcomeConfig.Settings): android.os.Bundle? =
      if (cfg.ttsVoice.isBlank()) {
        null
      } else {
        android.os.Bundle().apply { putString(SHERPA_VOICE_PARAM, cfg.ttsVoice) }
      }

  fun bringToFront() { runCatching { overlay.bringToFront() } }

  /** The frame is stopping: hands back the voice to shut down off the main thread. */
  fun release(): TextToSpeech? {
    val ref = tts
    tts = null
    ttsReady = false
    pendingSpeech = null
    return ref
  }

  companion object {
    /** "Good evening, Sam": the greeting for [hour] (0..23), with the household's name when set. */
    fun greeting(cfg: WelcomeConfig.Settings, hour: Int): String {
      val greeting = when {
        hour < 5  -> cfg.greetingNight
        hour < 12 -> cfg.greetingMorning
        hour < 17 -> cfg.greetingAfternoon
        hour < 22 -> cfg.greetingEvening
        else      -> cfg.greetingNight
      }
      return if (cfg.userName.isNotBlank()) "$greeting, ${cfg.userName}" else greeting
    }
  }
}
