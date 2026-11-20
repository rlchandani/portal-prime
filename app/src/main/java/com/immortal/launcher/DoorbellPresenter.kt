/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.TimeZone

/**
 * Shows an admitted doorbell alert in this room: wakes the screen, shows [DoorbellCardOverlay]
 * and plays the doorbell chime unless the chime is off or it is quiet hours ([DoorbellRing]).
 */
internal object DoorbellPresenter {
  private const val TAG = "HearthpaneDoorbell"
  private const val DOORBELL_RINGS = 2
  /** The doorbell must be heard even when the hourly chime is set very low. */
  private const val MIN_VOLUME = 0.6f
  private const val PERCENT = 100f
  private val main by lazy { Handler(Looper.getMainLooper()) }

  fun show(context: Context, alert: DoorbellAlert, settings: DoorbellConfig.Settings) {
    val app = context.applicationContext
    val chime = runCatching { ChimeConfig.load(app) }.getOrNull()
    val quiet = chime?.let { DinnerQuiet.isQuiet(it, DoorbellAlerts.clock(), TimeZone.getDefault()) } ?: false
    val decision = DoorbellRing.decide(settings.chime, quiet)
    val card = DoorbellCardOverlay.Card(alert.id, DoorbellRing.title(alert), line(alert),
        DoorbellRing.statusLine(decision, settings.chime), decision.quiet, alert.camera)
    val volume = ((chime?.volume ?: 0) / PERCENT).coerceIn(MIN_VOLUME, 1f)
    main.post {
      ScreenControl.wake(app)
      DoorbellCardOverlay.show(app, card)
      if (decision.sound) runCatching { ChimePlayer.playPing(app, DOORBELL_RINGS, volume) }
          .onFailure { Log.w(TAG, "doorbell chime failed", it) }
    }
  }

  /** "Front door · Living Area": the camera, and the room that saw it when that wasn't here. */
  private fun line(alert: DoorbellAlert): String =
      if (alert.camera.isBlank()) alert.room else "${alert.cameraName} · ${alert.room}"
}
