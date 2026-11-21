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

  private lateinit var column: LinearLayout
  private lateinit var clockBlock: LinearLayout
  private lateinit var infoBlock: LinearLayout
  private var laidOut: LayoutClass? = null

  fun build(): View {
    val panel = FrameLayout(context)
    panel.setBackgroundColor(0xF20D0D12.toInt()) // near-opaque dark
    column = LinearLayout(context)
    column.orientation = LinearLayout.VERTICAL
    column.gravity = Gravity.CENTER
    panel.addView(column, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    clockBlock = block()
    infoBlock = block()
    dashClock = views.text(120f, Color.WHITE, true)
    dashDate = views.text(28f, Color.WHITE, false)
    dashWeather = views.text(26f, Color.WHITE, false)
    dashEvent = views.text(22f, Color.WHITE, false)
    // Almanac line(s): enabled calendar packs (Irish holidays, prayer times) + a quote of the day.
    dashAlmanac = views.text(20f, Color.WHITE, false)
    // The line gaps are LinearLayout params handed to addView. (Setting margins on a fresh view's
    // layoutParams did nothing: they are null until the view is added, so every line sat flush.)
    clockBlock.addView(dashClock, line(0))
    clockBlock.addView(dashDate, line(0))
    infoBlock.addView(dashWeather, line(LINE_GAPS_DP[0]))
    infoBlock.addView(dashEvent, line(LINE_GAPS_DP[1]))
    infoBlock.addView(dashAlmanac, line(LINE_GAPS_DP[2]))
    column.addView(clockBlock, LinearLayout.LayoutParams(WRAP, WRAP))
    column.addView(infoBlock, LinearLayout.LayoutParams(WRAP, WRAP))
    // As built, this is the Portal+ arrangement: one centred column of centred lines.
    listOf(dashClock, dashDate, dashWeather, dashEvent, dashAlmanac).forEach { it.gravity = Gravity.CENTER }
    laidOut = LayoutClass.LARGE
    this.panel = panel
    return panel
  }

  private fun block() = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
  }

  private fun line(topDp: Int) = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = views.dp(topDp) }

  /**
   * The Portal+ keeps its one centred column. The Mini upright gets a left-aligned column with
   * 56 dp margins and a 560 dp quote; turned, two columns (clock and date | weather, next, quote).
   */
  private fun applyLayout(cls: LayoutClass) {
    if (laidOut == cls) return
    laidOut = cls
    val spec = layoutFor(cls)
    val textGravity = if (spec.centred) Gravity.CENTER else Gravity.START
    listOf(dashClock, dashDate, dashWeather, dashEvent, dashAlmanac).forEach { it.gravity = textGravity }
    listOf(clockBlock, infoBlock).forEach {
      it.gravity = if (spec.centred) Gravity.CENTER_HORIZONTAL else Gravity.START
    }
    column.orientation = if (spec.columns) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    column.gravity = if (spec.centred) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
    val columnLp = column.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(WRAP, WRAP)
    columnLp.width = if (spec.sideDp > 0) FrameLayout.LayoutParams.MATCH_PARENT else WRAP
    columnLp.gravity = Gravity.CENTER
    columnLp.leftMargin = views.dp(spec.sideDp); columnLp.rightMargin = views.dp(spec.sideDp)
    column.layoutParams = columnLp
    fun lineParams(v: View): LinearLayout.LayoutParams =
        (v.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(WRAP, WRAP)).also { v.layoutParams = it }
    lineParams(infoBlock).apply {
      topMargin = views.dp(spec.infoTopDp)
      leftMargin = views.dp(spec.columnGapDp)
      width = if (spec.columns) 0 else WRAP
      weight = if (spec.columns) 1f else 0f
    }
    lineParams(clockBlock).apply {
      width = if (spec.columns) 0 else WRAP
      weight = if (spec.columns) 1f else 0f
    }
    dashClock.textSize = spec.clockSp
    dashDate.textSize = spec.dateSp
    lineParams(dashDate).topMargin = views.dp(spec.dateTopDp)
    val info = listOf(dashWeather, dashEvent, dashAlmanac)
    if (spec.compact) {
      dashWeather.textSize = 28f; dashEvent.textSize = 24f; dashAlmanac.textSize = 21f
      info.forEachIndexed { i, v -> lineParams(v).topMargin = if (i == 0) 0 else views.dp(COMPACT_GAP_DP) }
      dashAlmanac.setLineSpacing(0f, 1.45f)
    } else {
      dashWeather.textSize = 26f; dashEvent.textSize = 22f; dashAlmanac.textSize = 20f
      info.forEachIndexed { i, v -> lineParams(v).topMargin = views.dp(LINE_GAPS_DP[i]) }
      dashAlmanac.setLineSpacing(0f, 1f)
    }
    dashAlmanac.maxWidth = if (spec.quoteMaxDp > 0) views.dp(spec.quoteMaxDp) else Int.MAX_VALUE
    column.requestLayout()
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
    applyLayout(ScreensaverLayout.of(context))
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

  /** How the card is arranged for one [LayoutClass]; see [layoutFor]. */
  data class Arrangement(
      val compact: Boolean, val centred: Boolean, val columns: Boolean, val sideDp: Int,
      val clockSp: Float, val dateSp: Float, val dateTopDp: Int, val infoTopDp: Int,
      val columnGapDp: Int, val quoteMaxDp: Int,
  )

  companion object {
    /** Gaps above the weather, next-event and almanac lines on the Portal+ (dp). */
    val LINE_GAPS_DP = listOf(18, 10, 14)
    /** Gap between the info lines on the Mini (dp). */
    const val COMPACT_GAP_DP = 18

    /** Portal+ unchanged (sizes as built); the Mini's mock variants upright and turned. */
    fun layoutFor(cls: LayoutClass): Arrangement = when (cls) {
      LayoutClass.LARGE -> Arrangement(false, true, false, 0, 120f, 28f, 0, 0, 0, 0)
      LayoutClass.COMPACT_PORTRAIT -> Arrangement(true, false, false, 56, 168f, 30f, 14, 48, 0, 560)
      LayoutClass.COMPACT_LANDSCAPE -> Arrangement(true, false, true, 72, 150f, 30f, 14, 0, 56, 0)
    }

    /** The card stays up this long. */
    const val SHOW_MS = 9_000L
    /** And comes back about this often. */
    const val EVERY_MS = 90_000L
  }
}
