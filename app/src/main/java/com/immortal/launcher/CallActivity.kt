/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * The call screen: outgoing, incoming (over anything, the photo frame included), drop-in banner,
 * in call and ended. It owns the capture lifecycle rules from the camera spike and the privacy
 * rules from the feasibility doc:
 * - media may open only while this screen is started, with the screen awake;
 * - leaving it (Back, Home, another app, screen off) ends the call and closes camera and mic now.
 */
class CallActivity : ComponentActivity() {
  private val motion = mutableStateOf(false)
  /** Bumped when Android says memory is low, so the screen can say why the picture got lighter. */
  private val lowMemory = mutableIntStateOf(0)
  private var resumed = false
  private val screen = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == Intent.ACTION_SCREEN_OFF) endIfActive("screen off") else updateMotion()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    }
    @Suppress("DEPRECATION")
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    applyToolsDisplayBars(this)
    setContent {
      CallApp(motion = motion.value, lowMemory = lowMemory.intValue, onClose = ::finish,
          onNeedPermissions = ::requestCallPermissions)
    }
    window.setBackgroundDrawable(null)
  }

  override fun onStart() {
    super.onStart()
    CallMediaHub.foreground(true)
    registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) })
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    applyToolsDisplayBars(this)
    updateMotion()
  }

  override fun onPause() {
    resumed = false
    updateMotion()
    super.onPause()
  }

  override fun onStop() {
    runCatching { unregisterReceiver(screen) }
    CallMediaHub.foreground(false)
    if (!isChangingConfigurations) endIfActive("left the call screen")
    if (Calling.state.value.call == null) finish()
    super.onStop()
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    @Suppress("DEPRECATION")
    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && Calling.state.value.call != null) {
      CallMediaHub.degrade()
      lowMemory.intValue++
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != PERMISSION_REQUEST) return
    val call = Calling.state.value.call ?: return
    val camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    if (!mic) { Calling.dispatch(CallEvent.MediaFailed(call.id, CallEndReason.NO_PERMISSION, Calling.now())); return }
    val wantedCamera = call.cameraOn || call.cameraIssue == CameraIssue.PERMISSION
    when {
      !wantedCamera -> Unit
      !camera -> Calling.dispatch(CallEvent.CameraProblem(CameraIssue.PERMISSION))
      call.cameraOn -> CallMediaHub.camera(true)
      else -> Calling.toggleCamera()
    }
  }

  private fun requestCallPermissions(camera: Boolean) {
    val wanted = buildList {
      add(Manifest.permission.RECORD_AUDIO)
      if (camera) add(Manifest.permission.CAMERA)
    }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), PERMISSION_REQUEST)
  }

  private fun endIfActive(why: String) {
    if (Calling.state.value.call == null) return
    Log.i(TAG, "ending call: $why")
    Calling.left()
  }

  private fun updateMotion() {
    val interactive = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
    motion.value = resumed && interactive
  }

  companion object {
    private const val TAG = "HearthpaneCalling"
    private const val PERMISSION_REQUEST = 7301

    fun open(context: Context) {
      runCatching {
        context.startActivity(Intent(context, CallActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
      }.onFailure { Log.w(TAG, "call screen could not open", it) }
    }
  }
}
