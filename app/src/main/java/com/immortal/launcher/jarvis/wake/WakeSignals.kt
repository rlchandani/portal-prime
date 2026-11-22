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
import android.os.Handler
import android.util.Log

/**
 * The platform signals [WakePolicy] reads that arrive as callbacks: the screen and the dream, camera 0, and the mic
 * (a mute or privacy switch, another app starting a call's capture). All callbacks run on the main [handler] and only
 * flip a field and call [changed]; the decision stays in [WakePolicy]. Registered once for the process lifetime (a
 * few cheap callbacks, no polling). The audio mode has no callback before API 31: [audio] is read at each decision.
 */
internal class WakeSignals(private val context: Context, private val handler: Handler, private val changed: () -> Unit) {
  @Volatile var dreaming = false
    private set
  @Volatile var cameraInUse = false
    private set

  /** The mic as the audio system reports it right now. Main thread. */
  fun audio(): WakeAudioFacts = WakeAudioFacts.read(context.getSystemService(AudioManager::class.java))

  fun register() {
    registerScreen()
    registerCamera()
    registerMic()
  }

  /** A mute (the hardware privacy switch mutes the mic system-wide) or another app's capture starting or ending. */
  private fun registerMic() {
    context.registerReceiver(object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) = changed()
    }, IntentFilter(ACTION_MIC_MUTE_CHANGED), null, handler)
    val audio = context.getSystemService(AudioManager::class.java) ?: return
    runCatching {
      audio.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) = changed()
      }, handler)
    }.onFailure { Log.w(TAG, "recording callback unavailable: ${it.javaClass.simpleName}") }
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
    /** `AudioManager.ACTION_MICROPHONE_MUTE_CHANGED` (API 28; the Portals run 28 and 29). Registered receivers only. */
    const val ACTION_MIC_MUTE_CHANGED = "android.media.action.MICROPHONE_MUTE_CHANGED"
  }
}
