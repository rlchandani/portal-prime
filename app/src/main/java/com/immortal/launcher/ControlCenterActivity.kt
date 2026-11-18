/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf

/**
 * Home Control Center (the approved big-ideas mock): the live floor plan and everything around it.
 * Live state only while this is on screen ([ControlCenter.setVisible]); animations run only while
 * resumed with the screen on and stop the moment it dims ([motion]).
 */
class ControlCenterActivity : ComponentActivity() {
  /** True while animations may run: resumed, screen interactive, no dream on top. */
  private val motion = mutableStateOf(false)
  private val screen = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { updateMotion() }
  }
  private var resumed = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { CcApp(motion = motion.value, onExit = ::finish) }
  }

  override fun onStart() {
    super.onStart()
    ControlCenter.reloadSettings(this)
    ControlCenter.setVisible(this, true)
    registerReceiver(screen, IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_DREAMING_STARTED); addAction(Intent.ACTION_DREAMING_STOPPED)
    })
  }

  override fun onResume() { super.onResume(); resumed = true; updateMotion() }

  override fun onPause() { resumed = false; updateMotion(); super.onPause() }

  override fun onStop() {
    runCatching { unregisterReceiver(screen) }
    ControlCenter.setVisible(this, false)
    super.onStop()
  }

  private fun updateMotion() {
    val interactive = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
    motion.value = resumed && interactive && PresenceHub.current.screen != ScreenState.DREAMING
  }

  companion object {
    fun intent(context: Context): Intent = Intent(context, ControlCenterActivity::class.java)

    fun open(context: Context) {
      runCatching { context.startActivity(intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
  }
}
