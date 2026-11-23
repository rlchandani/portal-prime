/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import android.content.Context
import android.util.Log
import com.immortal.launcher.CalendarFeed
import com.immortal.launcher.GuestContent
import com.immortal.launcher.GuestMode
import com.immortal.launcher.ImmortalSettings
import com.immortal.launcher.NewsConfig
import com.immortal.launcher.NewsFeed
import com.immortal.launcher.ScreensaverConfig
import com.immortal.launcher.WhosThere
import com.immortal.launcher.WhosThereContent
import com.immortal.launcher.WhosThereView
import com.immortal.launcher.memory.MemoryAccess
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Binds the feed tools ([HearthpaneCalendar], [HearthpaneNews]) to Hearthpane's household calendar and news feeds.
 * Runs on the provider's binder thread, inside Jarvis's 5 s tool timeout: every network read gets [NETWORK_BUDGET_MS]
 * together, and a feed that hasn't answered by then counts as unreachable (its cache serves the next ask).
 */
internal object HearthpaneFeedTools {
  private const val TAG = "HearthpaneTools"
  private const val NETWORK_BUDGET_MS = 3_000L
  private val io = Executors.newCachedThreadPool { r -> Thread(r, "hearthpane-jarvis-feeds").apply { isDaemon = true } }

  fun build(context: Context): Map<String, (String?) -> JSONObject> {
    val ctx = context.applicationContext
    val calendar = HearthpaneCalendar(
        feed = { calendar(ctx) }, hidden = { GuestMode.hides(GuestContent.CALENDAR) }, now = System::currentTimeMillis,
        zone = TimeZone::getDefault, locale = Locale::getDefault, use24 = { ImmortalSettings.use24HourClock(ctx) },
        mine = { mine(ctx) })
    val news = HearthpaneNews(sources = { news(ctx) }, defaulted = { NewsConfig.load(ctx).isEmpty() })
    return mapOf(HearthpaneCalendar.NAME to calendar::invoke, HearthpaneNews.NAME to news::invoke)
  }

  /** The household calendar (Home's and the photo frame's), null when none is added. */
  private fun calendar(ctx: Context): CalendarFeed.Result? {
    val url = ScreensaverConfig.load(ctx).calendarUrl
    if (url.isNullOrBlank()) return null
    val read = io.submit<CalendarFeed.Result> { CalendarFeed.read(url, System.currentTimeMillis()) }
    return within(read, System.currentTimeMillis() + NETWORK_BUDGET_MS, "calendar")
        ?: CalendarFeed.Result(emptyList(), unavailable = true)
  }

  /**
   * The recognised person's calendar as their personal Home shows it: their own feed, else the household's filtered
   * to them. Null while nobody (or a glance still asking "is this you?") is recognised.
   */
  private fun mine(ctx: Context): HearthpaneCalendar.Mine? {
    val ui = WhosThere.state.value
    val view = ui.view as? WhosThereView.Personal ?: return null
    if (view.confirm) return null
    val person = ui.owner ?: return null
    val name = MemoryAccess.firstName(person.name) ?: "you"
    val own = person.calendarUrl?.takeIf { it.isNotBlank() }
    val url = own ?: ScreensaverConfig.load(ctx).calendarUrl?.takeIf { it.isNotBlank() } ?: return HearthpaneCalendar.Mine(name, null)
    val read = io.submit<CalendarFeed.Result> { CalendarFeed.read(url, System.currentTimeMillis()) }
    val result = within(read, System.currentTimeMillis() + NETWORK_BUDGET_MS, "personal calendar")
        ?: CalendarFeed.Result(emptyList(), unavailable = true)
    val events = if (own != null) WhosThereContent.fromOwnFeed(result.events, person)
        else WhosThereContent.fromShared(result.events, person, ui.people)
    return HearthpaneCalendar.Mine(name, CalendarFeed.Result(HearthpaneCalendar.personal(events, ui.hidePrivate), result.unavailable))
  }

  /** Every added feed (or the default one), fetched side by side. */
  private fun news(ctx: Context): List<HearthpaneNews.Source> {
    val feeds = NewsConfig.load(ctx).ifEmpty { listOf(NewsConfig.Feed(0, HearthpaneNews.DEFAULT_NAME, HearthpaneNews.DEFAULT_URL)) }
    val reads = feeds.map { f -> f to io.submit<List<NewsFeed.Headline>> { NewsFeed.fetch(ctx, f.url) } }
    val deadline = System.currentTimeMillis() + NETWORK_BUDGET_MS
    return reads.map { (f, read) ->
      HearthpaneNews.Source(f.name.ifBlank { hostOf(f.url) }, within(read, deadline, "news").orEmpty())
    }
  }

  private fun <T> within(f: Future<T>, deadline: Long, what: String): T? = runCatching {
    f.get(maxOf(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
  }.getOrElse { f.cancel(true); Log.i(TAG, "$what source late: ${it.javaClass.simpleName}"); null }

  private fun hostOf(url: String): String =
      runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull()?.ifBlank { null } ?: "News"
}
