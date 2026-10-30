/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only, silent original clip. Exercises production playback, never household media. */
class VideoDesignPreviewActivity : ComponentActivity() {
  private lateinit var video: SlideshowVideoView
  private var closed = false
  private var preparedCount = 0
  private lateinit var status: TextView
  private lateinit var position: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
    video = SlideshowVideoView(this)
    root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(0xdd202124.toInt())
      setPadding(24, 12, 24, 12)
    }
    status = TextView(this).apply { text = "Loading silent video"; textSize = 20f; setTextColor(-1) }
    position = TextView(this).apply {
      text = "Video position: 0"; textSize = 20f; setTextColor(-1)
    }
    panel.addView(status); panel.addView(position)
    val controls = LinearLayout(this)
    val file = File(cacheDir, "silent-video-design.mp4")
    fun button(label: String, action: () -> Unit) {
      controls.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }
    button("Pause") { video.pause(); status.text = "Video paused" }
    button("Resume") { video.start(); status.text = "Video resumed" }
    button("Seek") { video.seekTo(3_000); status.text = "Video seek requested" }
    button("Stop") { video.stopPlayback(); status.text = "Video stopped" }
    button("Read position") { position.text = "Video position: ${video.currentPosition}" }
    button("Replace rapidly") {
      repeat(100) { video.setVideoPath(file.path) }
      status.text = "Video replacement requested"
    }
    button("Close") {
      video.close(); closed = true; status.text = "Video closed"
      position.text = "Video position: ${video.currentPosition}"
    }
    panel.addView(controls)
    root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    setContentView(root)
    video.setOnPreparedListener { width, height ->
      preparedCount++
      status.text = "Video ready: ${width}x${height}; preparations: $preparedCount"
      video.start()
    }
    video.setOnCompletionListener { status.text = "Video complete" }
    video.setOnErrorListener { what, extra -> status.text = "Video error: $what/$extra" }
    lifecycleScope.launch {
      withContext(Dispatchers.IO) {
        assets.open("slideshow-design/native-video.mp4").use { input ->
          file.outputStream().use { input.copyTo(it) }
        }
      }
      if (!closed && !isFinishing) video.setVideoPath(file.path)
    }
  }

  override fun onDestroy() {
    closed = true
    if (this::video.isInitialized) video.close()
    super.onDestroy()
  }
}
