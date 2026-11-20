/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.immortal.launcher.PhotoFrameViews.Companion.WRAP
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * The ambient dashboard (fork feature): a full-screen glanceable card (clock, date, weather, the
 * next event, almanac) that periodically pauses the classic photos. [post] is the frame's pausable
 * main-thread queue; [io] its photo worker.
 */
internal class PhotoFrameDashboard(
    private val context: Context,
    private val views: PhotoFrameViews,
    private val io: () -> Executor,
    private val post: (Runnable, Long) -> Unit,
    /** The ambient dashboard is switched on. */
    private val enabled: () -> Boolean,
    /** The night photo is showing: never pop the bright card over it. */
    private val nightPhoto: () -> Boolean,
) {
  var panel: View? = null
    private set
  private lateinit var dashClock: TextView
  private lateinit var dashDate: TextView
  private lateinit var dashWeather: TextView
  private lateinit var dashEvent: TextView
  private lateinit var dashAlmanac: TextView
  private var visible = false
  /** Weather line for the card. The Face overlay fetches its own; this keeps the card self-contained. */
  @Volatile var weatherText: String = ""

  fun build(): View {
    val panel = FrameLayout(context)
    panel.setBackgroundColor(0xF20D0D12.toInt()) // near-opaque dark
    val col = LinearLayout(context)
    col.orientation = LinearLayout.VERTICAL
    col.gravity = Gravity.CENTER
    panel.addView(col, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    dashClock = views.text(120f, Color.WHITE, true).also { it.gravity = Gravity.CENTER; col.addView(it) }
    dashDate = views.text(28f, Color.WHITE, false).also { it.gravity = Gravity.CENTER; col.addView(it) }
    dashWeather = views.text(26f, Color.WHITE, false).also {
      it.gravity = Gravity.CENTER
      (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = views.dp(18)
      col.addView(it)
    }
    dashEvent = views.text(22f, Color.WHITE, false).also {
      it.gravity = Gravity.CENTER
      (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = views.dp(10)
      col.addView(it)
    }
    // Almanac line(s): enabled calendar packs (Irish holidays, prayer times) + a quote of the day.
    dashAlmanac = views.text(20f, Color.WHITE, false).also {
      it.gravity = Gravity.CENTER
      (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = views.dp(14)
      col.addView(it)
    }
    this.panel = panel
    return panel
  }

  /** Shows the dashboard card for a few seconds, then returns to the photos. */
  val cycle: Runnable =
      object : Runnable {
        override fun run() {
          if (!enabled()) return
          // Never pop the bright dashboard over the dark-room Night photo.
          if (nightPhoto()) { post(this, PhotoFrameSchedule.DASHBOARD_RETRY_MS); return }
          show()
          post(Runnable { hide() }, SHOW_MS)
          post(this, EVERY_MS) // reappear roughly every 90s
        }
      }

  fun bringToFront() { panel?.let { runCatching { it.bringToFront() } } }

  private fun show() {
    val panel = panel ?: return
    val now = Date()
    val clockPattern = if (ImmortalSettings.use24HourClock(context)) "H:mm" else "h:mm"
    dashClock.text = SimpleDateFormat(clockPattern, Locale.getDefault()).format(now)
    dashDate.text = DateFormatter.format(now, "EEEEMMMMd")
    dashWeather.text = weatherText
    dashWeather.visibility = if (weatherText.isNotBlank()) View.VISIBLE else View.GONE
    dashEvent.visibility = View.GONE
    dashAlmanac.visibility = View.GONE
    io().execute {
      val lines = buildList {
        runCatching { CalendarPacks.headerLines(context) }.getOrDefault(emptyList()).forEach { add(it) }
        val q = DailyContent.quoteOfDay()
        add("“${q.text}”  — ${q.author}")
      }
      val txt = lines.joinToString("\n")
      post(Runnable {
        if (visible && txt.isNotBlank()) {
          dashAlmanac.text = txt
          dashAlmanac.visibility = View.VISIBLE
        }
      }, 0L)
    }
    io().execute {
      val ev = runCatching {
        if (CalendarHelper.hasPermission(context)) CalendarHelper.upcoming(context).firstOrNull() else null
      }.getOrNull()
      post(Runnable {
        if (ev != null) {
          val cal = Calendar.getInstance().apply { timeInMillis = ev.begin }
          val whenStr =
              if (ev.allDay) SimpleDateFormat("MMM d", Locale.getDefault()).format(cal.time)
              else SimpleDateFormat(if (ImmortalSettings.use24HourClock(context)) "HH:mm" else "h:mm a",
                  Locale.getDefault()).format(cal.time)
          dashEvent.text = "Next: $whenStr · ${ev.title}"
          if (visible) dashEvent.visibility = View.VISIBLE
        }
      }, 0L)
    }
    panel.alpha = 0f
    panel.visibility = View.VISIBLE
    panel.animate().alpha(1f).setDuration(600).start()
    visible = true
  }

  private fun hide() {
    val panel = panel ?: return
    panel.animate().alpha(0f).setDuration(600).withEndAction { panel.visibility = View.GONE }.start()
    visible = false
  }

  companion object {
    /** The card stays up this long. */
    const val SHOW_MS = 9_000L
    /** And comes back about this often. */
    const val EVERY_MS = 90_000L
  }
}
