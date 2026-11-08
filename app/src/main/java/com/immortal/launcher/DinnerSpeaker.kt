/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * Chime once, then speak the message once, through [ChimePlayer]. Music that honors audio focus
 * pauses (transient focus) while the message plays. Without any TTS engine the call still chimes
 * and the card says so; [ChimePlayer.announce] itself fails quietly if an engine misbehaves.
 */
internal object DinnerSpeaker {
  private const val TAG = "ImmortalDinner"
  private const val SPEAK_AFTER_CHIME_MS = 1_300L
  private const val MS_PER_WORD = 450L
  private const val MAX_FOCUS_MS = 9_000L
  private val main = Handler(Looper.getMainLooper())
  private var focus: AudioFocusRequest? = null
  private var pendingRelease: Runnable? = null
  private var boost: AnnouncementVolume? = null
  private var pendingRestore: Runnable? = null
  /** Speech-length estimates run short on slow engines; restore a little after the hold ends. */
  private const val RESTORE_MARGIN_MS = 2_500L

  fun ttsAvailable(context: Context): Boolean = runCatching {
    context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
  }.getOrDefault(false)

  /**
   * Main thread. [speak] false plays the chime only (Ping, voice off, no engine). [loud] (a call
   * received from another room) plays chime and speech on the alarm stream at full volume, even if
   * the Portal is turned down to 0, then restores the room's level; a local preview stays as set.
   */
  fun play(context: Context, text: String, speak: Boolean, loud: Boolean = false) {
    val app = context.applicationContext
    val holdMs = if (speak) (SPEAK_AFTER_CHIME_MS + 1_500L + MS_PER_WORD * text.split(' ').size).coerceAtMost(MAX_FOCUS_MS) else SPEAK_AFTER_CHIME_MS
    requestFocus(app, holdMs)
    if (loud) boostVolume(app, holdMs + RESTORE_MARGIN_MS)
    val chimeVolume = if (loud) 1f else null
    runCatching { ChimePlayer.playPing(app, repeats = 1, volumeOverride = chimeVolume) }.onFailure { Log.w(TAG, "chime failed", it) }
    if (speak) main.postDelayed({
      runCatching { if (loud) ChimePlayer.announceOnAlarmStream(app, text) else ChimePlayer.announce(app, text) }
          .onFailure { Log.w(TAG, "speech failed", it) }
    }, SPEAK_AFTER_CHIME_MS)
  }

  private fun boostVolume(context: Context, restoreAfterMs: Long) {
    val audio = context.getSystemService(AudioManager::class.java) ?: return
    val volume = boost ?: AnnouncementVolume(AnnouncementVolume.System(audio)).also { boost = it }
    volume.boost(AudioManager.STREAM_ALARM)
    // A second call while boosted extends the window; the first saved level is what comes back.
    pendingRestore?.let(main::removeCallbacks)
    val restore = Runnable { volume.restore(); pendingRestore = null }
    pendingRestore = restore
    main.postDelayed(restore, restoreAfterMs)
  }

  private fun requestFocus(context: Context, holdMs: Long) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val audio = context.getSystemService(AudioManager::class.java) ?: return
    abandonFocus(audio)
    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { }
        .build()
    runCatching { audio.requestAudioFocus(request) }.onFailure { Log.w(TAG, "audio focus failed", it) }
    focus = request
    val release = Runnable { abandonFocus(audio) }
    pendingRelease = release
    main.postDelayed(release, holdMs)
  }

  private fun abandonFocus(audio: AudioManager) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    pendingRelease?.let(main::removeCallbacks)
    pendingRelease = null
    focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
    focus = null
  }
}
