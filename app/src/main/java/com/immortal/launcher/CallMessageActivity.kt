/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * "Leave a message" (record → review → send) and message playback. Not exported. The camera and
 * mic open only while this screen is started; leaving it (Back, Home, screen off) discards the
 * take and closes everything. Measurement builds accept [EXTRA_TEST_FRAMES] (generated frames, no
 * audio) and [EXTRA_AUTO] (record 5 s, then send) for night tests; normal builds ignore both.
 */
class CallMessageActivity : ComponentActivity() {
  private var composer: CallMessageComposer? = null
  private val screen = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { if (intent.action == Intent.ACTION_SCREEN_OFF) finish() }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    applyToolsDisplayBars(this)
    CallMessageStore.ensureLoaded(this)
    val msgId = intent.getStringExtra(EXTRA_MESSAGE)
    if (msgId != null) {
      val m = CallMessageStore.messages.value.firstOrNull { it.id == msgId } ?: return finish()
      CallMessageStore.markSeen(this, m.id)
      setContent { Box(Modifier.fillMaxSize().background(CallColors.VideoBg)) { CallMessagePlayScreen(m, onClose = ::finish) } }
      return
    }
    val peer = intent.getStringExtra(EXTRA_PEER)?.let { id -> CallingStore.peers.value.firstOrNull { it.id == id } }
    if (peer == null || !CallMessages.canSend(peer)) { Log.w(TAG, "no deliverable recipient"); finish(); return }
    val test = BuildConfig.PORTAL_CALL_LOOPBACK && intent.getBooleanExtra(EXTRA_TEST_FRAMES, false)
    val auto = test && intent.getBooleanExtra(EXTRA_AUTO, false)
    // No message is being composed yet, so anything in the sender's temp directory is a leftover.
    CallMessageStore.sweepOutgoing(this)
    if (!test) askForMedia()
    val callId = intent.getStringExtra(EXTRA_CALL)?.takeIf(CallWire::isCallId)
    val c = CallMessageComposer(this, peer, test, onFinish = ::finish, callId = callId)
    composer = c
    setContent { Box(Modifier.fillMaxSize().background(CallColors.VideoBg)) { CallMessageComposeScreen(c, auto) } }
  }

  override fun onStart() {
    super.onStart()
    registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF))
  }

  override fun onResume() {
    super.onResume()
    applyToolsDisplayBars(this)
  }

  override fun onStop() {
    runCatching { unregisterReceiver(screen) }
    // Privacy: off screen means nothing is kept, camera and mic close now.
    if (!isChangingConfigurations) { composer?.release(); composer = null; finish() }
    super.onStop()
  }

  override fun onDestroy() {
    composer?.release()
    composer = null
    super.onDestroy()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == PERMISSIONS && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      composer?.let { if (it.state.cameraOn) it.recorder.camera(true) }
    }
  }

  private fun askForMedia() {
    val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSIONS)
  }

  companion object {
    private const val TAG = "HearthpaneCallMsg"
    private const val PERMISSIONS = 7311
    const val EXTRA_PEER = "peer"
    const val EXTRA_MESSAGE = "message"
    /** The unanswered call a message is left after (links it to that missed call on the other Portal). */
    const val EXTRA_CALL = "call"
    const val EXTRA_TEST_FRAMES = "testFrames"
    const val EXTRA_AUTO = "auto"
  }
}

/** Entry points for the rest of Calling (ended screen, Calls app, Home widget, probes). */
internal object CallMessages {
  /** A message can go to a Portal in this home whose address we heard (never to a phone). */
  fun canSend(peer: CallPeer?): Boolean = peer != null && !CallPhoneId.isPhone(peer.id) && peer.host != null && peer.port in 1..65535

  fun canSend(contact: CallContact): Boolean =
      !contact.phone && canSend(CallingStore.peers.value.firstOrNull { it.id == contact.deviceId })

  fun compose(context: Context, peer: CallContact, testFrames: Boolean = false, auto: Boolean = false, callId: String? = null) {
    runCatching {
      context.startActivity(Intent(context, CallMessageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          .putExtra(CallMessageActivity.EXTRA_PEER, peer.deviceId)
          .apply { callId?.let { putExtra(CallMessageActivity.EXTRA_CALL, it) } }
          .apply { if (BuildConfig.PORTAL_CALL_LOOPBACK && testFrames) { putExtra(CallMessageActivity.EXTRA_TEST_FRAMES, true); putExtra(CallMessageActivity.EXTRA_AUTO, auto) } })
    }
  }

  fun play(context: Context, id: String) {
    runCatching {
      context.startActivity(Intent(context, CallMessageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          .putExtra(CallMessageActivity.EXTRA_MESSAGE, id))
    }
  }
}
