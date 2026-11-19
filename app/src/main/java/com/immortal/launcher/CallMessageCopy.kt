/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Words for video messages, from the approved mock. Pure (JVM-tested). */
internal object CallMessageCopy {
  const val LEAVE = "Leave a message"
  const val AUDIO_ONLY = "Camera off · audio message"
  const val STORED = "Only on this Portal · deleted after 30 days"
  const val FOOTER = "Messages stay encrypted on the Portal they were left for and delete themselves after 30 days."

  fun title(name: String) = "Message for $name"

  fun sub(phase: ComposePhase, failure: String?): String = when (phase) {
    ComposePhase.READY -> "Up to 2 minutes · they’ll get it on their Portal"
    ComposePhase.RECORDING -> "Recording · camera and mic on"
    ComposePhase.REVIEW -> "Only you have seen this"
    ComposePhase.SENDING -> "Sending over your home Wi-Fi…"
    ComposePhase.SENT -> "Sent · it’s gone from this Portal"
    ComposePhase.FAILED -> failure(failure)
  }

  fun liveBar(phase: ComposePhase, cameraOn: Boolean): String = when {
    phase == ComposePhase.RECORDING && cameraOn -> "Recording · Camera on · Mic on"
    phase == ComposePhase.RECORDING -> "Recording · Camera off · Mic on"
    cameraOn -> "Camera on · Mic off"
    else -> "Camera off · Mic off"
  }

  fun stopLabel(elapsedMs: Long) = "Stop · ${CallingCopy.clock(elapsedMs)} of 2:00"

  fun sent(name: String) = "Message sent to $name · end-to-end encrypted"

  /** A send that didn't land, in plain words (route error codes and sender reasons). */
  fun failure(code: String?): String = when (CallMessageRefusal.fromCode(code)) {
    CallMessageRefusal.MESSAGES_OFF -> "They’ve turned video messages off on that Portal"
    CallMessageRefusal.FULL -> "That Portal has too many messages waiting"
    CallMessageRefusal.NO_SPACE -> "That Portal is out of space"
    CallMessageRefusal.TOO_LARGE, CallMessageRefusal.TOO_LONG -> "That message is too long to send"
    CallMessageRefusal.DUPLICATE -> "That message was already sent"
    CallMessageRefusal.BUSY -> "That Portal is taking another message. Try again in a moment"
    null -> when (code) {
      "recording_failed" -> "The recording didn’t work. Try again"
      "no_address", "unreachable" -> "Couldn’t reach that Portal. Is it on the home Wi-Fi?"
      else -> "It didn’t send. Your message is still here"
    }
  }

  /** Settings: "3 messages · 41 MB". */
  fun storedLine(count: Int, bytes: Long): String = when (count) {
    0 -> "None right now"
    1 -> "1 message · ${size(bytes)}"
    else -> "$count messages · ${size(bytes)}"
  }

  fun size(bytes: Long): String = if (bytes < MB) "${(bytes + KB - 1) / KB} KB" else "%.1f MB".format(bytes.toDouble() / MB)

  private const val KB = 1024L
  private const val MB = 1024L * 1024

  /** "Video message · 0:48". */
  fun kind(m: CallMessage) = "${if (m.audioOnly) "Audio message" else "Video message"} · ${CallingCopy.clock(m.durationMs)}"

  /** Home widget and list line: "2:14 PM · left a 0:48 video message". */
  fun line(m: CallMessage, time: String) = "$time · left a ${CallingCopy.clock(m.durationMs)} ${if (m.audioOnly) "audio" else "video"} message"
}
