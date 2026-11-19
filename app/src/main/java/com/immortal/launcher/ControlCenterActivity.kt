/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.jarvis.HearthpaneScreens
import com.immortal.launcher.jarvis.ScreenContext
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
  /** Screen asked for by the latest intent (fleet `open_control_center`, banners). */
  private val requested = mutableStateOf<Pair<CcScreen, Long>?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val start = screenOf(intent)
    setContent { CcApp(motion = motion.value, initialScreen = start ?: CcScreen.MAP, requested = requested.value, onExit = ::finish) }
    // The Compose root paints the whole window opaque, so the theme's window background would only
    // be a second full-screen fill on every frame.
    window.setBackgroundDrawable(null)
    // Floating Jarvis: Control Center is what `screen_context` describes while it's on screen.
    HearthpaneScreens.track(this, HearthpaneScreens.Entry({ HearthpaneScreens.CONTROL_CENTER }) {
      ScreenContext.controlCenter(ControlCenter.state(this).value)
    })
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    screenOf(intent)?.let { requested.value = it to System.nanoTime() }
  }

  private fun screenOf(intent: Intent?): CcScreen? =
      intent?.getStringExtra(EXTRA_SCREEN)?.let { n -> CcScreen.entries.firstOrNull { it.name.equals(n, ignoreCase = true) } }

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

  /** Focus means no dream window is on top, so a dream left over from before CC opened is over. */
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) { PresenceHub.onAppFocused(this); updateMotion() }
  }

  private fun updateMotion() {
    val interactive = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
    motion.value = ccMotionAllowed(resumed, interactive, PresenceHub.current.screen)
  }

  companion object {
    /** Optional [CcScreen] name to open on (fleet `open_control_center`, banners). */
    const val EXTRA_SCREEN = "screen"

    fun intent(context: Context): Intent = Intent(context, ControlCenterActivity::class.java)

    fun open(context: Context) {
      runCatching { context.startActivity(intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
  }
}

/** Animations run only while resumed, with the screen on and no dream on top. */
internal fun ccMotionAllowed(resumed: Boolean, interactive: Boolean, screen: ScreenState): Boolean =
    resumed && interactive && screen != ScreenState.DREAMING
