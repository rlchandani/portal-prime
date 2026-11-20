/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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

/**
 * The calendar widget: a clean upcoming-events panel over the photo, fed by a public ICS feed
 * (Google secret-iCal / Apple iCloud public calendar), or the test source's synthetic events.
 * Under Split glance or the night photo it hands only the next event to the split panel.
 */
internal class PhotoFrameCalendarPanel(
    private val context: Context,
    private val views: PhotoFrameViews,
    private val clock: PhotoFrameClock,
    private val settings: () -> ScreensaverConfig.Settings,
    private val testSource: () -> Boolean,
    /** Split glance or the night photo owns the screen. */
    private val featureOwnsScreen: () -> Boolean,
    private val splitPanel: () -> SplitGlancePanel?,
) {
  private lateinit var panel: LinearLayout
  private lateinit var header: TextView
  private lateinit var rows: LinearLayout
  /** The household calendar's events, as last fetched. */
  var events: List<CalendarFeed.Event> = emptyList()
  // Text/padding scale for the current calendar size (0/1/2 → small/medium/large),
  // applied as the panel is (re)rendered so a size change takes effect live.
  private var scale: Float = 1f

  val built: Boolean get() = this::panel.isInitialized

  /** A clean upcoming-events panel top-right, over the photo. Its own translucent
   *  rounded backing keeps it legible without a full-screen scrim. Hidden until a
   *  calendar link is set and there's something to show. */
  fun build(root: FrameLayout) {
    panel = LinearLayout(context)
    panel.orientation = LinearLayout.VERTICAL
    panel.visibility = View.GONE
    val bg = GradientDrawable()
    bg.setColor(0x66000000)
    bg.cornerRadius = views.dp(18).toFloat()
    panel.background = bg
    panel.setPadding(views.dp(22), views.dp(18), views.dp(22), views.dp(18))
    val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
    lp.setMargins(0, views.dp(40), views.dp(40), 0)
    root.addView(panel, lp)

    header = views.text(15f, 0xCCFFFFFF.toInt(), false)
    header.typeface = Typeface.DEFAULT_BOLD
    header.letterSpacing = 0.08f
    panel.addView(header)

    rows = LinearLayout(context)
    rows.orientation = LinearLayout.VERTICAL
    val rowsLp = LinearLayout.LayoutParams(WRAP, WRAP)
    rowsLp.topMargin = views.dp(10)
    panel.addView(rows, rowsLp)
  }

  fun hide() { if (built) panel.visibility = View.GONE }

  fun bringToFront() { runCatching { panel.bringToFront() } }

  private val showing: Boolean get() = built && panel.visibility == View.VISIBLE && panel.width > 0

  /** The panel's area plus [margin] px when it is showing (photos avoid it), else null. */
  fun occlusion(margin: () -> Int): Rect? = if (!showing) null else margin().let { m ->
    Rect(panel.left - m, panel.top - m, panel.right + m, panel.bottom + m)
  }

  /** The same area as [occlusion], as the engine's paper-photo layout reads it. */
  fun bounds(margin: () -> Int): SlideshowPresentationPolicy.Bounds? = if (!showing) null else margin().let { m ->
    SlideshowPresentationPolicy.Bounds(panel.left - m, panel.top - m, panel.right + m, panel.bottom + m)
  }

  /** Rebuild the calendar panel from the cached events for the chosen range. Cheap
   *  (no network) — called when a fetch lands and once per minute from the minute tick. */
  fun render() {
    if (!built) return
    val settings = settings()
    val testSourceOn = testSource()
    if (featureOwnsScreen()) {
      // The new modes own the screen: Split glance shows only the next event in its panel.
      panel.visibility = View.GONE
      val now = clock.wallMs()
      val events = if (testSourceOn) ScreensaverTestSource.events(now)
          else if (settings.usesCalendar) events else emptyList()
      val next = SplitGlancePanel.nextEvent(events, now)
      val pattern = if (ImmortalSettings.use24HourClock(context)) "H:mm" else "h:mm a"
      splitPanel()?.setNext(next?.title, next?.let { SimpleDateFormat(pattern, Locale.getDefault()).format(Date(it.startMillis)) })
      return
    }
    if (!settings.usesCalendar && !testSourceOn) {
      panel.visibility = View.GONE
      return
    }
    val now = clock.wallMs()
    // The verification source shows synthetic events, never the household's own agenda.
    val shown = if (testSourceOn) CalendarFeed.window(ScreensaverTestSource.events(now), CalendarFeed.RANGE_3DAY, now)
        else CalendarFeed.window(events, settings.calendarRange, now)
    // Size + position can change at runtime (settings screen or a fleet push), so
    // (re)apply the chrome each render. Scale drives text/padding; side drives the edge.
    scale = scaleFor(settings.calendarSize)
    applyChrome(settings)
    header.textSize = 15f * scale
    header.text = rangeLabel(settings.calendarRange).uppercase(Locale.getDefault())
    rows.removeAllViews()
    panel.visibility = View.VISIBLE

    if (shown.isEmpty()) {
      val empty = views.text(17f * scale, 0xCCFFFFFF.toInt(), false)
      empty.text = "Nothing scheduled"
      rows.addView(empty)
      return
    }

    val agenda = settings.calendarRange == CalendarFeed.RANGE_AGENDA
    var lastDayKey = ""
    for (ev in shown) {
      // Day header whenever the day changes (and always in agenda mode, which spans
      // arbitrary dates). A single-day range needs no per-day header.
      val key = dayKey(ev.startMillis)
      if (key != lastDayKey && (agenda || settings.calendarRange != CalendarFeed.RANGE_DAY)) {
        val header = views.text(13f * scale, 0x99FFFFFF.toInt(), false)
        header.typeface = Typeface.DEFAULT_BOLD
        header.text = dayHeader(ev.startMillis).uppercase(Locale.getDefault())
        val hlp = LinearLayout.LayoutParams(WRAP, WRAP)
        hlp.topMargin = if (rows.childCount == 0) 0 else views.dp(12)
        rows.addView(header, hlp)
      }
      lastDayKey = key
      rows.addView(buildEventRow(ev))
    }
  }

  /** Position the panel against the chosen edge and scale its padding to the size. */
  private fun applyChrome(settings: ScreensaverConfig.Settings) {
    val pad = (22 * scale).toInt()
    val padV = (18 * scale).toInt()
    panel.setPadding(views.dp(pad), views.dp(padV), views.dp(pad), views.dp(padV))
    val left = settings.calendarSide == ScreensaverConfig.CAL_SIDE_LEFT
    val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or (if (left) Gravity.START else Gravity.END))
    val side = views.dp(40)
    if (left) lp.setMargins(side, views.dp(40), 0, 0) else lp.setMargins(0, views.dp(40), side, 0)
    panel.layoutParams = lp
  }

  private fun buildEventRow(ev: CalendarFeed.Event): View {
    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    val rowLp = LinearLayout.LayoutParams(WRAP, WRAP)
    rowLp.topMargin = views.dp(6)

    val time = views.text(17f * scale, Color.WHITE, false)
    time.text = if (ev.allDay) "All day" else timeLabel(ev.startMillis)
    time.width = views.dp((96 * scale).toInt())

    val title = views.text(17f * scale, Color.WHITE, false)
    title.typeface = Typeface.DEFAULT_BOLD
    title.text = ev.title
    title.maxLines = 1
    title.ellipsize = TextUtils.TruncateAt.END
    title.maxWidth = views.dp((360 * scale).toInt())

    row.addView(time)
    row.addView(title)
    row.layoutParams = rowLp
    return row
  }

  private fun timeLabel(millis: Long): String {
    val pattern = if (ImmortalSettings.use24HourClock(context)) "H:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
  }

  companion object {
    /** Text and padding scale for the household's size (0 small, 1 medium, 2 large). */
    fun scaleFor(size: Int): Float = when (size) {
      0 -> 0.82f
      2 -> 1.22f
      else -> 1f
    }

    fun rangeLabel(range: String): String =
        when (range) {
          CalendarFeed.RANGE_3DAY -> "Next 3 days"
          CalendarFeed.RANGE_WEEK -> "This week"
          CalendarFeed.RANGE_AGENDA -> "Upcoming"
          else -> "Today"
        }

    /** A stable per-day key (yyyyDDD) so the renderer can tell when the day changes. */
    fun dayKey(millis: Long): String = SimpleDateFormat("yyyyDDD", Locale.US).format(Date(millis))

    /** "Today" / "Tomorrow" / "Sat, Jun 21" relative to now. */
    fun dayHeader(millis: Long): String {
      val day = Calendar.getInstance()
      val today = dayKey(day.timeInMillis)
      day.add(Calendar.DAY_OF_YEAR, 1)
      val tomorrow = dayKey(day.timeInMillis)
      return when (dayKey(millis)) {
        today -> "Today"
        tomorrow -> "Tomorrow"
        else -> DateFormatter.format(Date(millis), "EEEMMMd")
      }
    }
  }
}
