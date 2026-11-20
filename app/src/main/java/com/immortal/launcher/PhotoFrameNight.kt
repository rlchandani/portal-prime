/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Night and dark-room switching: which night a dark room becomes, what a night-settings broadcast
 * changes, and the touches the night clock answers. Pure; [PhotoFrameController] applies it.
 */
internal object PhotoFrameNight {
  /** What a dark room (or light returning) turns into. */
  enum class Ambient {
    /** Nothing changes: stopped, or already in that state. */
    NONE,
    /** Photos keep showing, warm and dim under the night photo veil. */
    NIGHT_PHOTO,
    /** Photos are suspended under the quiet clock (a finite cross-dissolve both ways). */
    QUIET_CLOCK,
    /**
     * Split glance keeps its layout, warm and dimmed (the approved mock's night state), whatever
     * the night colour: it already is a clock, so neither the quiet clock nor the full-bleed night
     * photo replaces it.
     */
    SPLIT_DIM,
  }

  /** The dark room turning [night] on or off, while it is [current]. */
  fun ambient(night: Boolean, current: Boolean, stopped: Boolean, tone: String, nativePhotoMode: Boolean,
              splitGlance: Boolean = false): Ambient = when {
    stopped || current == night -> Ambient.NONE
    splitGlance && nativePhotoMode -> Ambient.SPLIT_DIM
    tone == ScreensaverConfig.NIGHT_TONE_PHOTO && nativePhotoMode -> Ambient.NIGHT_PHOTO
    else -> Ambient.QUIET_CLOCK
  }

  /** A night-settings broadcast: the session's settings with only its clock controls replaced. */
  data class Change(
      val settings: ScreensaverConfig.Settings,
      val sensorChanged: Boolean,
      val brightnessChanged: Boolean,
      val toneChanged: Boolean,
  )

  /** Source discovery and the retained media session stay; only night controls move. */
  fun change(current: ScreensaverConfig.Settings, fresh: ScreensaverConfig.Settings): Change = Change(
      settings = current.copy(darkRoomClock = fresh.darkRoomClock, darkRoomLux = fresh.darkRoomLux,
          nightClockBrightness = fresh.nightClockBrightness, nightClockTone = fresh.nightClockTone),
      sensorChanged = current.darkRoomClock != fresh.darkRoomClock || current.darkRoomLux != fresh.darkRoomLux,
      brightnessChanged = current.nightClockBrightness != fresh.nightClockBrightness,
      toneChanged = current.nightClockTone != fresh.nightClockTone,
  )

  /** The photos render only while the host is visible and no night cover hides them. */
  fun photosActive(hostVisible: Boolean, coversPhotos: Boolean): Boolean = hostVisible && !coversPhotos

  /** The quiet clock draws while the host is visible and a night (or its dissolve) is showing. */
  fun quietClockActive(hostVisible: Boolean, nightShowing: Boolean, coversPhotos: Boolean, stopped: Boolean): Boolean =
      hostVisible && (nightShowing || coversPhotos) && !stopped

  /** Log line for the quiet-clock dissolve. */
  fun quietClockLog(night: Boolean): String =
      if (night) "dark room: fading to clock; photos suspended" else "light returned: fading to retained photo"

  /** Log line for Split glance's dark room. */
  fun splitNightLog(night: Boolean): String =
      if (night) "dark room: split glance dimmed" else "light returned: split glance day ink"

  /** Log line for the night photo veil. */
  fun nightPhotoLog(night: Boolean): String =
      if (night) "night photo: warm dim photos" else "night photo: day frame restored"
}
