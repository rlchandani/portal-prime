/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import android.content.Context
import android.util.Log
import com.immortal.launcher.Http
import com.immortal.launcher.ImmortalSettings
import com.immortal.launcher.jarvis.HearthpaneMarketTools
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Binds the "For you right now" row to the live Portal. It is behind the same switch as the rest of personalization
 * ("Jarvis learns from conversations", off by default) and follows the same rule of who it belongs to
 * ([MemoryAccess]): nothing for guests, children or teens; the recognised adult's own suggestions; the household's
 * when nobody is recognised, or when others are near (plan §4.4). Reads are on the caller's (background) thread;
 * writes go through the memory thread. Logs carry counts only.
 *
 * Slice 3 adds the interest chips ([ForYouInterests]): the scope's ACTIVE teams, tickers and news topics read live
 * through [ForYouInterestFeed] (the `hearthpane.sports` / `hearthpane.stocks` feeds, cached and time-boxed), ranked
 * on the same scale and with the same feedback as the habit chips. A paused scope still gets them (what is known is
 * still used).
 */
internal object ForYouHome {
  private const val TAG = "HearthpaneForYou"
  private const val NEWS_BUDGET_MS = 2_500L
  private const val NEWS_TTL_MS = 15L * 60 * 1000
  private const val MAX_NEWS_PAGES = 6
  private val io = Executors.newCachedThreadPool { r -> Thread(r, "hearthpane-foryou-news").apply { isDaemon = true } }

  /**
   * What the row shows for the moment: whose suggestions, whether learning is paused, the ranking (habit and interest
   * together), what each interest chip says ([targets]), and when to read again ([refreshMs]).
   */
  data class Snapshot(
      val scope: MemoryScope, val paused: Boolean, val ranked: List<ForYouRanker.Suggestion>,
      val targets: Map<String, ForYouChips.Target> = emptyMap(), val refreshMs: Long = ForYouInterests.REFRESH_MS,
  ) {
    val household: Boolean get() = scope is MemoryScope.Household
  }

  /** The row for right now, or null when nothing may be shown. Call off the main thread (it may read the network). */
  fun load(context: Context): Snapshot? {
    val on = JarvisMemory.accessNow(context) as? MemoryAccess.On ?: return null
    val scope = if (on.othersNear) MemoryScope.Household else on.scope
    val runtime = JarvisMemory.runtime(context)
    val nowMs = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val feedback = runtime.feedback()
    val profile = runtime.profile(scope)
    val habits = ForYouRanker.rank(runtime.usage(), feedback, scope, nowMs, zone)
    val read = runCatching { feed(context).read(profile.facts, nowMs) }
        .onFailure { Log.w(TAG, "interests unavailable: ${it.javaClass.simpleName}") }.getOrNull()
    val candidates = read?.let {
      ForYouInterests.candidates(it.inputs, scope is MemoryScope.Household, nowMs, zone, ImmortalSettings.use24HourClock(context))
    }.orEmpty()
    val interests = ForYouInterests.rank(candidates, feedback, scope, nowMs)
    val ranked = (habits + interests).sortedWith(compareByDescending<ForYouRanker.Suggestion> { it.score }.thenBy { it.surface })
    val shown = ranked.take(ForYouChips.MAX).mapNotNull { s -> candidates.firstOrNull { it.surface == s.surface } }
    if (read != null && (read.teams + read.tickers + read.topics) > 0) {
      Log.i(TAG, "interests: ${read.teams} teams, ${read.tickers} tickers, ${read.topics} topics, " +
          "${candidates.size} chips, ${interests.size} ranked")
    }
    return Snapshot(scope, profile.paused, ranked, candidates.associate { it.surface to ForYouInterests.target(it) },
        ForYouInterests.refreshMs(shown, nowMs))
  }

  private fun feed(context: Context): ForYouInterestFeed {
    val ctx = context.applicationContext
    return ForYouInterestFeed(
        espn = { paths, budget -> HearthpaneMarketTools.espn(ctx, paths, budget) },
        quotes = { symbols -> HearthpaneMarketTools.recentQuotes(ctx, symbols) },
        rss = ::news,
    )
  }

  private class Page(val body: String?, val atMs: Long)

  /** Topic searches in RAM only (the topic is personal), at most [MAX_NEWS_PAGES], for [NEWS_TTL_MS]. */
  private val newsPages = LinkedHashMap<String, Page>()

  /** Topic search bodies for [urls] through the same RAM-only cache (also the News widget's topic feeds). */
  internal fun topicPages(urls: List<String>): List<String?> = news(urls)

  private fun news(urls: List<String>): List<String?> {
    val nowMs = System.currentTimeMillis()
    val deadline = nowMs + NEWS_BUDGET_MS
    val reads: List<Pair<java.util.concurrent.Future<String?>?, String?>> = urls.map { url ->
      val cached = synchronized(newsPages) { newsPages[url]?.takeIf { nowMs - it.atMs < NEWS_TTL_MS } }
      if (cached != null) return@map null to cached.body
      io.submit<String?> {
        Http.getString(url, headers = mapOf("Accept" to "application/rss+xml, application/xml, text/xml, */*"),
            connectTimeoutMs = NEWS_BUDGET_MS.toInt(), readTimeoutMs = NEWS_BUDGET_MS.toInt())
            .also { body -> if (body != null) remember(url, body) }
      } to null
    }
    return reads.map { (future, cached) ->
      if (future == null) cached else runCatching {
        future.get(maxOf(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
      }.getOrElse { future.cancel(true); null }
    }
  }

  private fun remember(url: String, body: String) = synchronized(newsPages) {
    newsPages.remove(url)
    newsPages[url] = Page(body, System.currentTimeMillis())
    while (newsPages.size > MAX_NEWS_PAGES) newsPages.remove(newsPages.keys.first())
  }

  /** Forget all: drop the topic searches held in RAM. */
  fun clearCaches() = synchronized(newsPages) { newsPages.clear() }

  /** Something done with a chip; "Don't suggest this" is written at once. */
  fun note(context: Context, scope: MemoryScope, surface: String, kind: ForYouFeedback.Kind) {
    val ctx = context.applicationContext
    JarvisMemory.background {
      JarvisMemory.runtime(ctx).recordFeedback(immediate = kind == ForYouFeedback.Kind.NEVER) {
        it.with(scope, surface, kind, System.currentTimeMillis())
      }
      if (kind != ForYouFeedback.Kind.SHOWN) Log.i(TAG, "chip ${kind.name.lowercase()}")
    }
  }

  /** Undo of "Don't suggest this". */
  fun allowAgain(context: Context, scope: MemoryScope, surface: String) {
    val ctx = context.applicationContext
    JarvisMemory.background { JarvisMemory.runtime(ctx).recordFeedback(immediate = true) { it.allowAgain(scope, surface) } }
  }

  /** The row's "Resume" on the paused banner: learning back on for [scope]. */
  fun resume(context: Context, scope: MemoryScope) {
    val ctx = context.applicationContext
    JarvisMemory.background { JarvisMemory.runtime(ctx).update(scope) { MemoryLearner.setPaused(it, false, System.currentTimeMillis()) } }
  }
}
