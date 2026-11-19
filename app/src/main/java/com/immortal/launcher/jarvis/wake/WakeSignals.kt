/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.util.Log

/**
 * The platform signals [WakePolicy] reads that arrive as callbacks: the screen and the dream, and camera 0. All callbacks run on the main [handler] and only flip a field and call [changed];
 * the decision stays in [WakePolicy]. Registered once for the process lifetime (a few cheap callbacks, no polling).
 */
internal class WakeSignals(private val context: Context, private val handler: Handler, private val changed: () -> Unit) {
  @Volatile var dreaming = false
    private set
  @Volatile var cameraInUse = false
    private set

  fun register() {
    registerScreen()
    registerCamera()
  }

  private fun registerScreen() {
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_SCREEN_OFF)
      addAction(Intent.ACTION_DREAMING_STARTED)
      addAction(Intent.ACTION_DREAMING_STOPPED)
    }
    context.registerReceiver(object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
          Intent.ACTION_DREAMING_STARTED -> dreaming = true
          Intent.ACTION_DREAMING_STOPPED -> dreaming = false
          Intent.ACTION_SCREEN_OFF -> dreaming = false // a dream ends with the screen; SCREEN_OFF pauses anyway
        }
        changed()
      }
    }, filter, null, handler)
  }

  private fun registerCamera() {
    val cameras = context.getSystemService(CameraManager::class.java) ?: return
    runCatching {
      cameras.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) = camera(cameraId, inUse = false)
        override fun onCameraUnavailable(cameraId: String) = camera(cameraId, inUse = true)
      }, handler)
    }.onFailure { Log.w(TAG, "camera availability unavailable: ${it.javaClass.simpleName}") }
  }

  /** Only camera 0: Meta's presence service holds camera 1 for good, so it can't mean "someone is using it". */
  private fun camera(id: String, inUse: Boolean) {
    if (id != WATCHED_CAMERA || cameraInUse == inUse) return
    cameraInUse = inUse
    changed()
  }

  private companion object {
    const val TAG = "HearthpaneWake"
    const val WATCHED_CAMERA = "0"
  }
}
