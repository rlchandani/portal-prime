/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/** Generated pixels only; exercises the production crop toolbar without saving positions. */
@Suppress("DEPRECATION")
class SlideshowDragPreviewActivity : Activity() {
  internal lateinit var stage: SlideshowStage
  internal lateinit var controls: SlideshowViewerControls
  private lateinit var root: FrameLayout
  private val pixels = mutableListOf<Bitmap>()
  private var print = false
  private var commitSave = false
  private var pair = false

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    val token = intent.getStringExtra("owned_probe")
    if (token != null && cancelledProbe == token) { finish(); return }
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    root = FrameLayout(this)
    stage = SlideshowStage(this)
    controls = SlideshowViewerControls(this, stage, {}, {},
        { if (it) stage.pause() else stage.resume() }, { finish() },
        { _, _, callback -> callback(commitSave) },
        currentMode = {
          when {
            print -> ScreensaverConfig.MODE_GALLERY_PRINT
            pair -> ScreensaverConfig.MODE_PORTRAITS
            else -> ScreensaverConfig.MODE_SMART
          }
        })
    root.addView(stage, FrameLayout.LayoutParams(-1, -1))
    root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    setContentView(root)
  }

  fun configureDrag(width: Int, height: Int, framed: Boolean, zoom: Float) {
    controls.resetInteraction()
    stage.stop()
    print = framed
    pair = false
    commitSave = false
    stage.layoutParams = FrameLayout.LayoutParams(width, height)
    val bitmap = Bitmap.createBitmap(1600, 1600, Bitmap.Config.ARGB_8888)
    pixels.add(bitmap)
    Canvas(bitmap).apply {
      drawColor(Color.rgb(63, 67, 73))
      val paint = Paint().apply { color = Color.rgb(85, 90, 97); strokeWidth = 2f }
      for (position in 0..1600 step 100) {
        drawLine(position.toFloat(), 0f, position.toFloat(), 1600f, paint)
        drawLine(0f, position.toFloat(), 1600f, position.toFloat(), paint)
      }
      paint.color = Color.rgb(30, 220, 80)
      drawRect(784f, 784f, 816f, 816f, paint)
    }
    stage.post {
      stage.show(listOf(SlideshowPhoto("generated-drag", bitmap,
          adjustment = SlideshowAdjustment(zoom))), false, false, 8000, galleryPrint = framed)
      controls.toggle()
    }
  }

  fun configureCommit(paired: Boolean, framed: Boolean, moving: Boolean) {
    controls.resetInteraction()
    stage.stop()
    stage.resume()
    pair = paired
    print = framed
    commitSave = true
    stage.layoutParams = FrameLayout.LayoutParams(-1, -1)
    val photos = (0 until if (paired) 2 else 1).map { index ->
      val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
      pixels.add(bitmap)
      bitmap.eraseColor(if (index == 0) Color.rgb(180, 45, 50) else Color.rgb(40, 80, 220))
      SlideshowPhoto("generated-commit-$index", bitmap,
          regions = listOf(SlideshowGeometry.Region(.4f, .4f, .6f, .6f)))
    }
    stage.post {
      stage.show(photos, true, moving, 8000, galleryPrint = framed)
      controls.toggle()
    }
  }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean =
      if (::controls.isInitialized && controls.dispatchViewerTouch(event)) true
      else super.dispatchTouchEvent(event)

  override fun onDestroy() {
    if (::controls.isInitialized) controls.stop()
    if (::stage.isInitialized) stage.stop()
    // Rendered pixels are detached here; Android may retain their display-list references.
    pixels.clear()
    super.onDestroy()
  }

  companion object {
    // A cancelled launch may arrive after the instrumentation's ActivityMonitor timeout.
    private var cancelledProbe: String? = null
    @JvmStatic fun cancelOwnedProbe(token: String) { cancelledProbe = token }
  }
}
