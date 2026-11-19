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
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.util.Log

/**
 * The platform signals [WakePolicy] reads that arrive as callbacks: the screen and the dream, camera 0, and the
 * device's other recordings. All callbacks run on the main [handler] and only flip a field and call [changed];
 * the decision stays in [WakePolicy]. Registered once for the process lifetime (a few cheap callbacks, no polling).
 */
internal class WakeSignals(private val context: Context, private val handler: Handler, private val changed: () -> Unit) {
  @Volatile var dreaming = false
    private set
  @Volatile var cameraInUse = false
    private set
  @Volatile var otherRecording = false
    private set

  /** Our own capture's session id, so it never counts as "another recording". 0 = not capturing. */
  @Volatile var ownSession = 0
    set(value) {
      field = value
      handler.post { recordingsChanged(audio?.activeRecordingConfigurations.orEmpty()) }
    }

  private val audio = context.getSystemService(AudioManager::class.java)

  fun register() {
    registerScreen()
    registerCamera()
    registerRecordings()
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

  private fun registerRecordings() {
    val manager = audio ?: return
    manager.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
      override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) =
          recordingsChanged(configs)
    }, handler)
    recordingsChanged(manager.activeRecordingConfigurations)
  }

  /**
   * Another app's (or another Hearthpane feature's) live recording claims the mic. A silenced one doesn't: Android
   * 10 silences background captures (Jarvis's own background wake arms and gets silence), and we are what it hears.
   */
  private fun recordingsChanged(configs: List<AudioRecordingConfiguration>) {
    val busy = configs.any { it.clientAudioSessionId != ownSession && !silenced(it) }
    if (busy == otherRecording) return
    otherRecording = busy
    changed()
  }

  private fun silenced(config: AudioRecordingConfiguration): Boolean =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && config.isClientSilenced

  private companion object {
    const val TAG = "HearthpaneWake"
    const val WATCHED_CAMERA = "0"
  }
}
