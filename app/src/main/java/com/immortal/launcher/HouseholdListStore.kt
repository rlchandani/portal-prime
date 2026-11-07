/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * This Portal's replica of the household list. The in-memory [state] is the source of truth;
 * every change is persisted (atomically, off the caller's thread) and published through a
 * [StateFlow] that Compose collects on the main thread. Mutations are serialized on one lock, so
 * screen taps, phone batches (HTTP pool threads) and peer exchanges can't interleave.
 */
internal object HouseholdListStore {
  private const val TAG = "ImmortalHouseholdList"
  private const val FILE = "household_list.json"
  private const val PREFS = "immortal_household_list"
  private const val MIGRATED = "migrated_todo"
  private const val MIGRATION_SEEN = "migration_dialog_seen"

  /** Where a change came from; remote ones mark arrivals on the widget. */
  enum class Source { LOCAL, PHONE, PEER }

  fun interface Listener { fun onChanged(context: Context, before: HouseholdListState, after: HouseholdListState, source: Source) }

  private val lock = Any()
  private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "immortal-household-list-io").apply { isDaemon = true } }
  private val flow = MutableStateFlow(HouseholdListState())
  @Volatile private var loaded = false
  @Volatile var clock: () -> Long = System::currentTimeMillis
  @Volatile private var listener: Listener? = null

  val state: StateFlow<HouseholdListState> get() = flow

  fun setListener(l: Listener) { listener = l }

  fun current(context: Context): HouseholdListState { ensureLoaded(context); return flow.value }

  /** Loads the saved replica once (small file; called from install()). */
  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(lock) {
      if (loaded) return
      val app = context.applicationContext
      val read = runCatching { read(app) }.onFailure {
        // Keep the unreadable file for diagnosis instead of overwriting it with the next save.
        Log.w(TAG, "saved household list unreadable; set aside, starting empty", it)
        runCatching { File(app.filesDir, FILE).renameTo(File(app.filesDir, "$FILE.unreadable")) }
      }
      flow.value = read.getOrNull() ?: HouseholdListState()
      loaded = true
    }
  }

  /**
   * Applies [change] atomically and returns its result. The new state is saved and announced only
   * when it differs from the old one.
   */
  fun <R> update(context: Context, source: Source, change: (HouseholdListState) -> Pair<HouseholdListState, R>): R {
    ensureLoaded(context)
    val app = context.applicationContext
    val (before, after, result) = synchronized(lock) {
      val before = flow.value
      val (next, result) = change(before)
      val pruned = HouseholdListOps.prune(next, clock())
      if (pruned != before) {
        flow.value = pruned
        persist(app, pruned)
      }
      Triple(before, pruned, result)
    }
    if (before != after) runCatching { listener?.onChanged(app, before, after, source) }
        .onFailure { Log.w(TAG, "list change listener failed", it) }
    return result
  }

  /** Convenience for screen taps: applies one [HouseholdListOps] edit made on this Portal. */
  fun edit(context: Context, op: (HouseholdListState) -> HouseholdListOps.Result): HouseholdListOps.Outcome =
      update(context, Source.LOCAL) { s -> op(s).let { it.state to it.outcome } }

  /** One-time merge of this Portal's old To-do list (README Q3). Safe to call on every start. */
  fun migrateTodosOnce(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(MIGRATED, false)) return
    val todos = runCatching { TodoStore.load(context) }.getOrElse {
      Log.w(TAG, "old To-do list unreadable; not migrating it yet", it); return
    }
    val portal = HouseholdListText.cleanName(FleetConfig.name(context)) ?: "This Portal"
    update(context, Source.LOCAL) { s -> HouseholdListMigration.migrate(s, todos, portal, clock()) to Unit }
    prefs.edit().putBoolean(MIGRATED, true).putBoolean(MIGRATION_SEEN, todos.isEmpty()).apply()
  }

  /** The one-time "lists are now one" dialog copy, until it has been shown. */
  fun pendingMigrationDialog(context: Context): HouseholdListMigration.Summary? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(MIGRATION_SEEN, true)) return null
    return HouseholdListMigration.summary(current(context))
  }

  fun markMigrationDialogSeen(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MIGRATION_SEEN, true).apply()
  }

  private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE))

  private fun read(context: Context): HouseholdListState? {
    val f = file(context)
    if (!f.baseFile.exists()) return null
    val json = JSONObject(String(f.readFully(), Charsets.UTF_8))
    return HouseholdListCodec.decodeState(json, clock()) ?: error("saved household list failed validation")
  }

  private fun persist(context: Context, s: HouseholdListState) {
    val bytes = HouseholdListCodec.encodeState(s).toString().toByteArray(Charsets.UTF_8)
    writer.execute {
      val f = file(context)
      val out = runCatching { f.startWrite() }.getOrElse { Log.w(TAG, "couldn't save household list", it); return@execute }
      runCatching { out.write(bytes); f.finishWrite(out) }
          .onFailure { f.failWrite(out); Log.w(TAG, "couldn't save household list", it) }
    }
  }
}
