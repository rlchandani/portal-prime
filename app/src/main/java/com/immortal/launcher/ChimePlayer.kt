/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Calendar
import java.util.Locale

/**
 * Plays the ambient audio cues. Everything routes through the alarm/notification
 * stream so it's audible even when media is paused, and uses one-shot players that
 * release themselves on completion — these fire from a [android.content.BroadcastReceiver]
 * with no UI to own a player lifecycle.
 */
object ChimePlayer {
private const val TAG = "ImmortalChime"
private const val SHERPA_VOICE_PARAM = "sherpa_voice_name"

  private val ambientAttrs =
      AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ALARM)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()

  /** Play the soft hourly chime (res/raw/chime.mp3), releasing on completion. */
  fun playChime(context: Context) {
    runCatching {
      val vol = ChimeConfig.load(context).volume / 100f
      if (vol <= 0f) return
      playBundled(context, R.raw.chime, vol, "chime playback failed")
    }.onFailure { Log.w(TAG, "chime playback failed", it) }
  }

  /** Ring for an elapsed kitchen timer: the chime sound a few times. Independent
   *  of the hourly-chime volume (you set a timer on purpose, so it always rings). */
  fun playTimerRing(context: Context, repeats: Int = 3) {
    val h = Handler(Looper.getMainLooper())
    for (i in 0 until repeats) {
      h.postDelayed({
        playBundled(context, R.raw.chime, null, "timer ring failed")
      }, i * 1400L)
    }
  }

  /** Ring for an incoming "ping the other room" (res/raw/ping.mp3), a few times at the
   *  ping volume from Sounds settings. Separate sound + volume from the kitchen timer. */
  fun playPing(context: Context, repeats: Int = 2, volumeOverride: Float? = null) {
    val vol = (volumeOverride ?: (ChimeConfig.load(context).volume / 100f)).coerceIn(0f, 1f)
    if (vol <= 0f) return
    val h = Handler(Looper.getMainLooper())
    for (i in 0 until repeats) {
      h.postDelayed({
        playBundled(context, R.raw.ping, vol, "ping ring failed")
      }, i * 1400L)
    }
  }

  /** Speak an arbitrary phrase (e.g. "Pasta timer done") at full volume. */
  fun announce(context: Context, text: String) = speak(context, text, volumeOverride = 1f)

  /** [announce] on the alarm stream, so a household call is heard however the media volume is set. */
  fun announceOnAlarmStream(context: Context, text: String) =
      speak(context, text, volumeOverride = 1f, alarmStream = true)

  /** Audition the currently selected spoken-time voice without using a clock phrase. */
  fun testVoice(context: Context, voiceName: String = ChimeConfig.load(context).spokenVoice) {
    val sample =
        if (voiceName.contains("ro_RO", ignoreCase = true) || voiceName.contains("ro-ro", ignoreCase = true)) {
          "Aceasta este vocea selectata pentru Calder."
        } else {
          "This is the selected voice for Calder."
        }
    speak(context, sample, voiceNameOverride = voiceName)
  }

  /** Speak the current time, e.g. "It's three o'clock" / "It's half past four".
   * Uses the platform TTS (fast, always present); Piper is overkill for a chime. */
  fun speakTime(context: Context, now: Calendar = Calendar.getInstance()) {
    val phrase = spokenTimePhrase(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
    val app = context.applicationContext ?: context
    val automatic = AutomaticTimeSpeech({ ChimeConfig.load(app) }, {
      val clock = Calendar.getInstance()
      clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
    })
    speak(app, phrase, automatic = automatic)
  }

  /** Build the spoken phrase for [hour24]:[minute]. Pure + unit-testable. */
  fun spokenTimePhrase(hour24: Int, minute: Int): String {
    val names = arrayOf(
        "twelve", "one", "two", "three", "four", "five", "six",
        "seven", "eight", "nine", "ten", "eleven")
    val h12 = hour24 % 12
    val hourWord = names[h12]
    return when (minute) {
      0 -> "It's $hourWord o'clock"
      15 -> "It's quarter past $hourWord"
      30 -> "It's half past $hourWord"
      45 -> {
        val next = names[(h12 + 1) % 12]
        "It's quarter to $next"
      }
      else -> {
        val mm = if (minute < 10) "oh $minute" else "$minute"
        "It's $hourWord $mm"
      }
    }
  }

  /** Gentle sound marking sunrise. Two variants, chosen in Sounds settings:
   *  0 = "Morning" (golden_sunrise), 1 = "Rooster" (golden_sunrise_2). */
  fun playSunriseTone(context: Context) {
    val res = if (ChimeConfig.load(context).sunriseVariant == 1) R.raw.golden_sunrise_2 else R.raw.golden_sunrise
    playRaw(context, res)
  }

  /** Gentle sound marking sunset (res/raw/golden_sunset.mp3 — "Goodnight"). */
  fun playSunsetTone(context: Context) = playRaw(context, R.raw.golden_sunset)

  /** Generic golden-hour preview (used by the Sounds "Play tone" test button). */
  fun playGoldenHourTone(context: Context) = playSunriseTone(context)

  /** Play a bundled raw audio resource through the alarm stream at the golden volume. */
  private fun playRaw(context: Context, resId: Int) {
    runCatching {
      val vol = (ChimeConfig.load(context).volume / 100f).coerceIn(0f, 1f)
      if (vol <= 0f) return
      playBundled(context, resId, vol, "golden-hour tone failed")
    }.onFailure { Log.w(TAG, "golden-hour tone failed", it) }
  }

  /** Own the player before setup so failures and terminal callbacks share one cleanup path. */
  private fun playBundled(context: Context, resId: Int, volume: Float?, failureMessage: String) {
    runCatching {
      lateinit var owner: OneShotSpeechOwner<MediaPlayer>
      owner = OneShotSpeechOwner(
          ready = { mp ->
            try {
              mp.setAudioAttributes(ambientAttrs)
              val afd = context.resources.openRawResourceFd(resId)
                  ?: error("Bundled sound unavailable")
              afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
              if (volume != null) mp.setVolume(volume, volume)
              mp.setOnCompletionListener { owner.finish() }
              mp.setOnErrorListener { _, _, _ -> owner.finish(); true }
              mp.prepare()
              mp.start()
            } catch (t: Throwable) {
              Log.w(TAG, failureMessage, t)
              throw t
            }
          },
          release = { it.release() },
      )
      owner.created(MediaPlayer())
      owner.initialized(true)
    }.onFailure { Log.w(TAG, failureMessage, it) }
  }

  private fun speak(
      context: Context,
      text: String,
      volumeOverride: Float? = null,
      voiceNameOverride: String? = null,
      automatic: AutomaticTimeSpeech? = null,
      alarmStream: Boolean = false,
  ) {
    runCatching {
      val cfg = if (automatic == null) ChimeConfig.load(context)
          else automatic.playbackSettings() ?: return
      val vol = (volumeOverride ?: (cfg.volume / 100f)).coerceIn(0f, 1f)
      if (vol <= 0f) return
      lateinit var owner: OneShotSpeechOwner<TextToSpeech>
      owner = OneShotSpeechOwner(
          ready = { t ->
            fun deliver(playback: ChimeConfig.Settings) {
              val playbackVolume = (volumeOverride ?: (playback.volume / 100f)).coerceIn(0f, 1f)
              val voiceName = voiceNameOverride ?: playback.spokenVoice
              // Apply the chosen voice; otherwise keep the existing offline quality fallback.
              runCatching {
                val lang = Locale.getDefault().language
                val v =
                    if (voiceName.isNotBlank()) t.voices?.firstOrNull { it.name == voiceName }
                    else t.voices
                        ?.filter { it.locale.language == lang && !it.isNetworkConnectionRequired }
                        ?.maxByOrNull { it.quality }
                if (v != null) {
                  t.language = v.locale
                  t.voice = v
                } else {
                  t.language = Locale.getDefault()
                }
              }
              if (alarmStream) runCatching { t.setAudioAttributes(ambientAttrs) }
              val listening = t.setOnUtteranceProgressListener(
                  object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onError(id: String?) { owner.finish() }
                    override fun onError(id: String?, errorCode: Int) { owner.finish() }
                    override fun onDone(id: String?) { owner.finish() }
                    override fun onStop(id: String?, interrupted: Boolean) { owner.finish() }
                  })
              if (listening == TextToSpeech.ERROR) {
                owner.finish()
                return
              }
              val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, playbackVolume)
                if (voiceName.isNotBlank()) putString(SHERPA_VOICE_PARAM, voiceName)
              }
              owner.submitted(t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "chime_time") == TextToSpeech.SUCCESS)
            }
            if (automatic == null) deliver(cfg)
            else automatic.deliverOrCancel(cancel = owner::finish, deliver = ::deliver)
          },
          release = { t ->
            // Avoid shutdown inside a binder progress callback; retain the existing grace period.
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { t.shutdown() } }, 100)
          },
      )
      val engine = TextToSpeech(context.applicationContext) { status ->
        owner.initialized(status == TextToSpeech.SUCCESS)
      }
      owner.created(engine)
    }.onFailure { Log.w(TAG, "spoken time failed", it) }
  }
}
