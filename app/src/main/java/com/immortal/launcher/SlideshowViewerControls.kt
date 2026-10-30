/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Gallery spread always shows whole photos, so saved crops cannot affect its presentation. */
internal fun slideshowCropEditingAllowed(
    mode: String, stageVisible: Boolean, hasPhoto: Boolean,
): Boolean = stageVisible && hasPhoto && mode != ScreensaverConfig.MODE_GALLERY_SPREAD

/** Deliberate-viewer controls; the idle Dream never displays or dispatches this toolbar. */
internal class SlideshowViewerControls(
    context: Context,
    private val stage: SlideshowStage,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onPause: (Boolean) -> Unit,
    private val onExit: () -> Unit,
  private val onSave: (String, SlideshowAdjustment?, (Boolean) -> Unit) -> Unit,
  private val onEditingStart: () -> Unit = {},
  private val currentMode: () -> String = { ScreensaverConfig.MODE_SMART },
) : LinearLayout(context) {
  private val ui = Handler(Looper.getMainLooper())
  private val row = LinearLayout(context).apply { gravity = Gravity.CENTER; orientation = HORIZONTAL }
  private val editor = LinearLayout(context).apply { orientation = VERTICAL; visibility = View.GONE }
  private val status = TextView(context).apply {
    textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
  }
  private val pauseButton: TextView
  private val adjustButton: TextView
  private var isPaused = false
  private var editing = false
  val isEditing: Boolean get() = editing
  private var saving = false
  private var editRevision = 0L
  private var wasPaused = false
  private var draft = SlideshowAdjustment()
  private var assetKey: String? = null
  private var controlsTouch = false
  private var dragX = 0f
  private var dragY = 0f
  private val hide = Runnable { if (!isPaused && !editing) visibility = View.GONE }

  init {
    orientation = VERTICAL
    gravity = Gravity.CENTER
    setPadding(dp(32), dp(18), dp(32), dp(18))
    background = GradientDrawable().apply { setColor(0xf2363636.toInt()) }
    addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    row.addView(button("Previous") { onPrevious(); scheduleHide() })
    pauseButton = button("Pause") { changePause(!isPaused) }
    row.addView(pauseButton)
    row.addView(button("Next") { onNext(); scheduleHide() })
    adjustButton = button("Adjust") { beginEdit() }
    row.addView(adjustButton)
    row.addView(button("Done") { onExit() })
    addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    val guidance = TextView(context).apply {
      text = "Drag the photo to position it. Your original stays unchanged."
      textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
    }
    editor.addView(guidance)
    editor.addView(SeekBar(context).apply {
      contentDescription = "Photo zoom"
      max = 200
      setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
          if (fromUser && editing && !saving) {
            draft = draft.copy(zoom = 1f + progress / 100f)
            stage.adjust(draft)
          }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar) { }
        override fun onStopTrackingTouch(seekBar: SeekBar) { }
      })
    }, LayoutParams(dp(620), dp(64)))
    val editButtons = LinearLayout(context).apply { gravity = Gravity.CENTER }
    editButtons.addView(button("Show whole photo") {
      if (!saving) {
        draft = SlideshowAdjustment()
        (editor.getChildAt(1) as SeekBar).progress = 0
        stage.adjust(draft)
      }
    })
    editButtons.addView(button("Cancel") { cancelEdit() })
    editButtons.addView(button("Save position") { save() })
    editor.addView(editButtons)
    addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
  }

  fun toggle() {
    if (editing) return
    visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
    photoChanged()
    scheduleHide()
  }

  fun photoChanged() {
    if (editing) {
      editRevision++
      editing = false; saving = false; assetKey = null
      editor.visibility = View.GONE; row.visibility = View.VISIBLE
    }
    adjustButton.visibility =
        if (currentMode() == ScreensaverConfig.MODE_GALLERY_SPREAD) View.GONE else View.VISIBLE
    adjustButton.isEnabled = canEditPhoto()
    adjustButton.alpha = if (adjustButton.isEnabled) 1f else .4f
    status.text = if (isPaused) "Paused" else ""
  }

  private fun changePause(value: Boolean) {
    isPaused = value
    pauseButton.text = if (value) "Resume" else "Pause"
    status.text = if (value) "Paused" else ""
    onPause(value)
    scheduleHide()
  }

  private fun beginEdit() {
    val photo = stage.currentPhoto ?: return
    if (!canEditPhoto()) return
    onEditingStart()
    editRevision++
    wasPaused = isPaused
    changePause(true)
    editing = true
    assetKey = photo.key
    draft = photo.adjustment ?: SlideshowAdjustment()
    (editor.getChildAt(1) as SeekBar).progress = ((draft.zoom - 1) * 100).toInt()
    row.visibility = View.GONE; editor.visibility = View.VISIBLE
    status.text = "Adjust photo"
    stage.adjust(draft)
  }

  private fun canEditPhoto(): Boolean = slideshowCropEditingAllowed(
      currentMode(), stage.visibility == View.VISIBLE, stage.currentPhoto != null)

  fun cancelEdit() {
    if (!editing || saving) return
    editing = false; assetKey = null
    editRevision++
    stage.finishAdjustment(false)
    editor.visibility = View.GONE; row.visibility = View.VISIBLE
    changePause(wasPaused)
  }

  private fun save() {
    val key = assetKey ?: return
    if (saving) return
    saving = true
    val revision = editRevision
    val savedDraft = draft
    status.text = "Saving position…"
    onSave(key, savedDraft) { success ->
      if (!editing || assetKey != key || editRevision != revision) return@onSave
      saving = false
      if (!success) { status.text = "Position could not be saved. Try again."; return@onSave }
      stage.finishAdjustment(true)
      editing = false; assetKey = null
      editRevision++
      editor.visibility = View.GONE; row.visibility = View.VISIBLE
      changePause(wasPaused)
      status.text = "Position saved"
    }
  }

  /** Coordinates are relative to the same full-screen root as the photo stage. */
  fun dispatchViewerTouch(event: MotionEvent): Boolean {
    if (visibility != View.VISIBLE) return false
    if (event.actionMasked == MotionEvent.ACTION_DOWN) controlsTouch =
        event.x >= left && event.x < right && event.y >= top && event.y < bottom
    if (controlsTouch) {
      val copy = MotionEvent.obtain(event)
      try {
        copy.offsetLocation(-left.toFloat(), -top.toFloat())
        dispatchTouchEvent(copy)
      } finally { copy.recycle() }
      if (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) controlsTouch = false
      return true
    }
    if (!editing) return false
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> { dragX = event.x; dragY = event.y }
      MotionEvent.ACTION_MOVE -> {
        if (saving) return true
        val bitmap = stage.currentPhoto?.bitmap ?: return true
        val transform = draft.transform(bitmap.width, bitmap.height, stage.width, stage.height)
        draft = draft.copy(
            focusX = (draft.focusX - (event.x - dragX) / (bitmap.width * transform.scale)).coerceIn(0f, 1f),
            focusY = (draft.focusY - (event.y - dragY) / (bitmap.height * transform.scale)).coerceIn(0f, 1f))
        dragX = event.x; dragY = event.y
        if (!saving) stage.adjust(draft)
      }
    }
    return true
  }

  fun stop() { editRevision++; ui.removeCallbacksAndMessages(null); assetKey = null }
  fun resetInteraction() {
    stage.finishAdjustment(false)
    editRevision++
    editing = false; saving = false; assetKey = null; isPaused = false
    pauseButton.text = "Pause"
    status.text = ""
    editor.visibility = View.GONE; row.visibility = View.VISIBLE
    ui.removeCallbacks(hide)
    visibility = View.GONE
  }
  private fun scheduleHide() { ui.removeCallbacks(hide); ui.postDelayed(hide, 8_000L) }
  private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
  private fun button(label: String, action: () -> Unit): TextView = TextView(context).apply {
    text = label; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
    minWidth = dp(128); minHeight = dp(64)
    setPadding(dp(20), dp(12), dp(20), dp(12))
    isFocusable = true
    background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        .let { array -> try { array.getDrawable(0) } finally { array.recycle() } }
    setOnClickListener { action() }
  }
}
