/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

/**
 * When Hearthpane may listen for "Hey Jarvis" (decision D6 of the floating-Jarvis note): only while one of its
 * own screens — Home, any Hearthpane screen, or the idle photo frame Activity — is the resumed, interactive
 * foreground and nothing else has a claim on the mic or the camera. The system dream is never one: Android 10
 * silences every app's mic behind it. The one place this is decided; pure, so every rule is JVM-tested. Order in
 * [decide] = which reason the log shows.
 */
internal object WakePolicy {
  /** Everything the decision reads, as one immutable snapshot. */
  data class Facts(
      /** Settings › Jarvis › "Hey Jarvis on Hearthpane screens". */
      val enabled: Boolean,
      val micPermission: Boolean,
      /** Jarvis is installed, so a wake has somewhere to go. */
      val jarvisInstalled: Boolean,
      /** The wake model loaded (a 32-bit process has no TFLite native library). */
      val modelReady: Boolean,
      val screenInteractive: Boolean,
      val dreaming: Boolean,
      /**
       * A Hearthpane Activity is resumed: not another app, not Jarvis's card (which pauses the screen behind). The
       * photo frame Activity counts: it is in front, so it hears the room.
       */
      val hostResumed: Boolean,
      /** A Hearthpane call or drop-in (ringing included). */
      val callActive: Boolean,
      /** The audio system is in another app's call ([WakeAudioFacts.systemCall]). */
      val systemCall: Boolean,
      /** The mic is muted system-wide (the Portal's privacy switch, or a mute) ([WakeAudioFacts.micMuted]). */
      val micMuted: Boolean,
      /** The camera Hearthpane and Meta's calls use (camera 0) is open: a Who's there? glance, a call, a message. */
      val cameraInUse: Boolean,
      /**
       * Another Hearthpane feature holds the mic ([MicClaims]: the intercom's broadcast, a voice memo). Other apps
       * need no fact: one in front pauses this screen, and one behind it is silenced while Hearthpane is on top.
       */
      val micClaimed: Boolean,
      val quietHours: Boolean,
      /** A wake was just handed to Jarvis; the mic is Jarvis's until this screen pauses and resumes again. */
      val handingOff: Boolean,
  )

  enum class Reason(val log: String) {
    DISABLED("turned off in Settings"),
    NO_PERMISSION("no microphone permission"),
    NO_JARVIS("Jarvis isn't installed"),
    NO_MODEL("wake model unavailable"),
    SCREEN_OFF("screen off"),
    DREAMING("the system screensaver is showing"),
    NOT_IN_FRONT("another app or Jarvis is in front"),
    CALL("a call is active"),
    MIC_MUTED("the microphone is muted"),
    CAMERA("the camera is in use"),
    MIC_BUSY("another Hearthpane feature is using the mic"),
    QUIET_HOURS("quiet hours"),
    HANDING_OFF("Jarvis is opening"),
  }

  sealed interface Decision {
    data object Listen : Decision
    data class Pause(val reason: Reason) : Decision
  }

  fun decide(f: Facts): Decision {
    val reason = when {
      !f.enabled -> Reason.DISABLED
      !f.micPermission -> Reason.NO_PERMISSION
      !f.jarvisInstalled -> Reason.NO_JARVIS
      !f.modelReady -> Reason.NO_MODEL
      !f.screenInteractive -> Reason.SCREEN_OFF
      f.dreaming -> Reason.DREAMING
      f.handingOff -> Reason.HANDING_OFF
      !f.hostResumed -> Reason.NOT_IN_FRONT
      f.callActive || f.systemCall -> Reason.CALL
      f.micMuted -> Reason.MIC_MUTED
      f.cameraInUse -> Reason.CAMERA
      f.micClaimed -> Reason.MIC_BUSY
      f.quietHours -> Reason.QUIET_HOURS
      else -> null
    }
    return reason?.let { Decision.Pause(it) } ?: Decision.Listen
  }
}
