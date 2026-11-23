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
import com.immortal.launcher.WhosTherePerson
import com.immortal.launcher.WhosThereRole
import com.immortal.launcher.WhosThereStore
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

  /** Whether Jarvis may send what was said for a closer text read (plan D3), on top of the switch. */
  fun cloudExtraction(context: Context): Boolean = JarvisWakeConfig.load(context).closerReading

  /**
   * Who the moment belongs to right now (see [MemoryAccess.decide]). A recognised person's profile is kept under
   * their household-stable id ([HouseholdPersonId]), so it is the same profile on every Portal.
   */
  fun accessNow(context: Context): MemoryAccess {
    val ui = WhosThere.state.value
    fun role(id: String) = when (ui.person(id)?.role) {
      WhosThereRole.ADULT -> MemoryAccess.Role.ADULT
      WhosThereRole.TEEN -> MemoryAccess.Role.TEEN
      WhosThereRole.KID, null -> MemoryAccess.Role.KID // unknown id: treat as the most protected
    }
    val seen = when (val view = ui.view) {
      WhosThereView.Shared -> MemoryAccess.Seen.Nobody
      is WhosThereView.Personal -> MemoryAccess.Seen.One(stableId(ui.people, view.personId).orEmpty(),
          ui.person(view.personId)?.name, role(view.personId), view.othersNear)
      is WhosThereView.Family -> MemoryAccess.Seen.Several(view.personIds.map(::role))
    }
    val kids = runCatching { ControlCenterConfig.load(context).kids }.getOrDefault(true)
    return MemoryAccess.decide(MemoryAccess.Inputs(enabled(context), GuestMode.isOn(), kids, seen))
  }

  /** The household-stable scope for this Portal's person [localId], if they have one. */
  fun personScope(localId: String): MemoryScope.Person? =
      stableId(WhosThereStore.householdState.value.people, localId)?.let(MemoryScope::Person)

  private fun stableId(people: List<WhosTherePerson>, localId: String): String? =
      HouseholdPersonId.resolve(localId, people.map { it.id to it.name })

  /**
   * The scopes this Portal syncs (slice 5): the household's and those of the adults enrolled here. Never a kid's or a
   * teen's (D2), and nobody this Portal doesn't know. Empty with the switch off.
   */
  fun syncScopes(context: Context): List<MemoryScope> {
    if (!enabled(context)) return emptyList()
    val adults = WhosThereStore.householdState.value.people.filter { it.role == WhosThereRole.ADULT }
        .mapNotNull { personScope(it.id) }
    return (listOf<MemoryScope>(MemoryScope.Household) + adults).distinct().take(MemorySync.MAX_SCOPES)
  }

  /** [syncScopes]' profiles as they are now. */
  fun syncProfiles(context: Context): List<MemoryProfile> = syncScopes(context).map(runtime(context)::profile)

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

  /**
   * At start-up: listens for the other Portals (slice 5), then, with the switch on, loads the household profile off
   * the main thread so the first conversation's note is quick, moves profiles kept under a Portal-local id to the
   * household-stable one, and announces this Portal's revisions (the catch-up path for a Portal that was off).
   */
  fun warm(context: Context) {
    val ctx = context.applicationContext
    MemorySyncNet.install(ctx)
    if (!enabled(ctx)) return
    io.execute {
      runCatching {
        val runtime = runtime(ctx)
        runtime.profile(MemoryScope.Household)
        adoptLocalProfiles(ctx, runtime)
      }.onFailure { Log.w(TAG, "memory warm-up failed (${it.javaClass.simpleName})") }
      MemorySyncNet.signalSoon(ctx)
    }
  }

  /** Profiles under a Portal-local Who's there id (before slice 5) move to that person's stable id. */
  private fun adoptLocalProfiles(context: Context, runtime: MemoryRuntime) {
    val stored = MemoryProfileStore.get(context).scopes().filterIsInstance<MemoryScope.Person>().toSet()
    var moved = 0
    WhosThereStore.householdState.value.people.forEach { person ->
      val local = runCatching { MemoryScope.Person(person.id) }.getOrNull() ?: return@forEach
      val stable = personScope(person.id) ?: return@forEach
      if (local in stored && local != stable) {
        runtime.adopt(local, stable)
        moved++
      }
    }
    if (moved > 0) Log.i(TAG, "moved $moved profiles to household ids")
  }

  /** Forget all, from the operator or a reset: every profile and count on this Portal. */
  fun forgetEverything(context: Context): Boolean = runtime(context).forgetAll().also {
    ForYouHome.clearCaches()
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
        profilesSaved = { MemorySyncNet.signalSoon(ctx) },
    )
    val handoff = MemoryHandoff(
        runtime = runtime,
        dictionary = { MemoryDictionary.load(ctx) },
        accessNow = { accessNow(ctx) },
        portal = { runCatching { DinnerCall.selfName(ctx) }.getOrDefault("Portal").take(PORTAL_MAX) },
        now = System::currentTimeMillis,
        zone = ZoneId::systemDefault,
        recordUsage = { scope, surface -> io.execute { runCatching { record(ctx, scope, surface) } } },
        closerReading = { cloudExtraction(ctx) },
    )
    return Bound(runtime, handoff)
  }
}
