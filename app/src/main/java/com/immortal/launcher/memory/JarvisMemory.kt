/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import android.content.Context
import android.util.Log
import com.immortal.launcher.ControlCenterConfig
import com.immortal.launcher.DinnerCall
import com.immortal.launcher.GuestMode
import com.immortal.launcher.WhosThere
import com.immortal.launcher.WhosThereRole
import com.immortal.launcher.WhosThereView
import com.immortal.launcher.jarvis.wake.JarvisWakeConfig
import com.immortal.launcher.youtube.YtKeystoreSecureStore
import java.io.File
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Binds Jarvis personalization to the live Portal: the switch ("Jarvis learns from conversations", off by default,
 * or the operator's [MemoryDebug] force), Who's there, Guest and Kids mode, the sealed store and a background
 * writer. Everything with a rule lives in the pure classes ([MemoryHandoff], [MemoryAccess], [MemoryLearner],
 * [MemoryNote]). Logs carry counts and scope tokens only, never words or labels.
 */
internal object JarvisMemory {
  private const val TAG = "HearthpaneMemory"
  private const val USAGE_FILE = "usage.bin"
  private const val FEEDBACK_FILE = "foryou.bin"
  private const val PORTAL_MAX = 40

  private val io = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "hearthpane-memory").apply { isDaemon = true }
  }

  @Volatile private var bound: Bound? = null

  private class Bound(val runtime: MemoryRuntime, val handoff: MemoryHandoff)

  /** Whether learning and the note are on for this Portal. */
  fun enabled(context: Context): Boolean =
      JarvisWakeConfig.load(context).learnFromConversations || MemoryDebug.forceOn(context)

  /** Who the moment belongs to right now (see [MemoryAccess.decide]). */
  fun accessNow(context: Context): MemoryAccess {
    val ui = WhosThere.state.value
    fun role(id: String) = when (ui.person(id)?.role) {
      WhosThereRole.ADULT -> MemoryAccess.Role.ADULT
      WhosThereRole.TEEN -> MemoryAccess.Role.TEEN
      WhosThereRole.KID, null -> MemoryAccess.Role.KID // unknown id: treat as the most protected
    }
    val seen = when (val view = ui.view) {
      WhosThereView.Shared -> MemoryAccess.Seen.Nobody
      is WhosThereView.Personal ->
        MemoryAccess.Seen.One(view.personId, ui.person(view.personId)?.name, role(view.personId), view.othersNear)
      is WhosThereView.Family -> MemoryAccess.Seen.Several(view.personIds.map(::role))
    }
    val kids = runCatching { ControlCenterConfig.load(context).kids }.getOrDefault(true)
    return MemoryAccess.decide(MemoryAccess.Inputs(enabled(context), GuestMode.isOn(), kids, seen))
  }

  fun runtime(context: Context): MemoryRuntime = bind(context).runtime

  fun handoff(context: Context): MemoryHandoff = bind(context).handoff

  /**
   * Counts one open of [surface] (silent; slice 2's "For you right now"). Nothing for guests, kids, with the switch
   * off, or while the scope's learning is paused.
   */
  fun recordUsage(context: Context, surface: String) {
    val ctx = context.applicationContext
    io.execute {
      runCatching {
        val on = accessNow(ctx) as? MemoryAccess.On ?: return@runCatching
        record(ctx, on.scope, surface)
      }.onFailure { Log.w(TAG, "usage not recorded (${it.javaClass.simpleName})") }
    }
  }

  /** Loads the household profile off the main thread so the first conversation's note is quick. */
  fun warm(context: Context) {
    val ctx = context.applicationContext
    if (!enabled(ctx)) return
    io.execute { runCatching { runtime(ctx).profile(MemoryScope.Household) } }
  }

  /** Forget all, from the operator or a reset: every profile and count on this Portal. */
  fun forgetEverything(context: Context): Boolean = runtime(context).forgetAll().also {
    Log.i(TAG, "forget everything → ${if (it) "done" else "incomplete"}")
  }

  private fun record(context: Context, scope: MemoryScope, surface: String) {
    val zone = ZoneId.systemDefault()
    val runtime = runtime(context)
    if (runtime.profile(scope).paused) return
    runtime.recordUsage { it.record(scope, surface, System.currentTimeMillis(), zone) }
  }

  /** Runs [task] on the memory thread (sealed reads and writes stay off the main thread). */
  fun background(task: () -> Unit) {
    io.execute { runCatching(task).onFailure { Log.w(TAG, "memory task failed (${it.javaClass.simpleName})") } }
  }

  private fun bind(context: Context): Bound = bound ?: synchronized(this) {
    bound ?: create(context.applicationContext).also { bound = it }
  }

  private fun create(ctx: Context): Bound {
    val store = MemoryProfileStore.get(ctx)
    val usageFile = File(File(ctx.noBackupFilesDir, MemoryProfileStore.DIR), USAGE_FILE)
    val usageStore by lazy { YtKeystoreSecureStore(usageFile, MemoryProfileStore.ALIAS) }
    val feedbackFile = File(File(ctx.noBackupFilesDir, MemoryProfileStore.DIR), FEEDBACK_FILE)
    val feedbackStore by lazy { YtKeystoreSecureStore(feedbackFile, MemoryProfileStore.ALIAS) }
    val runtime = MemoryRuntime(
        load = store::load,
        save = store::save,
        loadUsage = { UsageCounts.decode(runCatching { usageStore.load() }.getOrNull()) },
        saveUsage = { counts -> runCatching { usageStore.save(counts.encode()) }.isSuccess },
        remove = store::forget,
        wipe = store::forgetAll,
        schedule = { delay, task -> io.schedule({ runCatching(task) }, delay, TimeUnit.MILLISECONDS) },
        now = System::currentTimeMillis,
        loadFeedback = { ForYouFeedback.decode(runCatching { feedbackStore.load() }.getOrNull()) },
        saveFeedback = { log -> runCatching { feedbackStore.save(log.encode()) }.isSuccess },
    )
    val handoff = MemoryHandoff(
        runtime = runtime,
        dictionary = { MemoryDictionary.load(ctx) },
        accessNow = { accessNow(ctx) },
        portal = { runCatching { DinnerCall.selfName(ctx) }.getOrDefault("Portal").take(PORTAL_MAX) },
        now = System::currentTimeMillis,
        zone = ZoneId::systemDefault,
        recordUsage = { scope, surface -> io.execute { runCatching { record(ctx, scope, surface) } } },
    )
    return Bound(runtime, handoff)
  }
}
