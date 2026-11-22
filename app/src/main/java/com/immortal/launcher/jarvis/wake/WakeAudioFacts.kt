/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import android.media.AudioManager
import android.media.MediaRecorder

/**
 * What the audio system says about the mic, for [WakePolicy]. The same three reads Jarvis's own `MicGuard` refuses
 * on (`MicrophoneState.facts` in portal-assistant): a system-wide mute (the Portal's privacy switch mutes the mic
 * this way), a call or ringtone audio mode, and another app capturing with `VOICE_COMMUNICATION` (a Meta, WebRTC
 * or VoIP call's mic, even when it never set the audio mode). Immutable; [read] is the only Android edge.
 */
internal data class WakeAudioFacts(
    val micMuted: Boolean,
    /** The audio mode is a call (`IN_CALL` / `IN_COMMUNICATION`) or a ringtone. */
    val callAudioMode: Boolean,
    /** Another client is capturing with `VOICE_COMMUNICATION`. Hearthpane's own wake capture is `VOICE_RECOGNITION`. */
    val callCaptureActive: Boolean,
) {
  /** Someone else's call holds the audio system: a call outranks a mute, as in Jarvis. */
  val systemCall: Boolean get() = callAudioMode || callCaptureActive

  companion object {
    val FREE = WakeAudioFacts(micMuted = false, callAudioMode = false, callCaptureActive = false)

    /** Pure: the modes in which a call (or its ring) owns the mic. */
    fun isCallMode(mode: Int): Boolean =
        mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE

    /** Pure: any of the active capture sources is a call's. */
    fun hasCallCapture(sources: List<Int>): Boolean = sources.any { it == MediaRecorder.AudioSource.VOICE_COMMUNICATION }

    /** Thin read of the live audio state; a missing service reads as free (the mic open then decides). */
    fun read(audio: AudioManager?): WakeAudioFacts {
      audio ?: return FREE
      val sources = runCatching { audio.activeRecordingConfigurations.map { it.clientAudioSource } }.getOrDefault(emptyList())
      return WakeAudioFacts(
          micMuted = runCatching { audio.isMicrophoneMute }.getOrDefault(false),
          callAudioMode = isCallMode(audio.mode),
          callCaptureActive = hasCallCapture(sources),
      )
    }
  }
}
