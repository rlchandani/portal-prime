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
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * This Portal's reminders replica, in app-private storage (excluded from backups like the rest of
 * `filesDir`). In memory is the source of truth; every change is pruned, saved (atomic file, off
 * the caller's thread) and published as a [StateFlow]. Mutations are serialized on one lock.
 */
internal object ReminderStore {
  private const val TAG = "HearthpaneReminders"
  private const val FILE = "household_reminders.json"

  enum class Source { LOCAL, PHONE, JARVIS, PEER }

  fun interface Listener { fun onChanged(context: Context, before: ReminderState, after: ReminderState, source: Source) }

  private val lock = Any()
  private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "hearthpane-reminders-io").apply { isDaemon = true } }
  private val flow = MutableStateFlow(ReminderState())
  @Volatile private var loaded = false
  @Volatile private var listener: Listener? = null
  @Volatile internal var clock: () -> Long = System::currentTimeMillis

  val state: StateFlow<ReminderState> get() = flow

  fun setListener(l: Listener) { listener = l }

  fun current(context: Context): ReminderState { ensureLoaded(context); return flow.value }

  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(lock) {
      if (loaded) return
      val app = context.applicationContext
      val read = runCatching { read(app) }.onFailure {
        Log.w(TAG, "saved reminders unreadable; set aside, starting empty (${it.javaClass.simpleName})")
        runCatching { File(app.filesDir, FILE).renameTo(File(app.filesDir, "$FILE.unreadable")) }
      }
      flow.value = read.getOrNull()?.let { ReminderOps.prune(it, clock()) } ?: ReminderState()
      loaded = true
    }
  }

  /** Applies [change] atomically; saves and announces only a real change. */
  fun <R> update(context: Context, source: Source, change: (ReminderState) -> Pair<ReminderState, R>): R {
    ensureLoaded(context)
    val app = context.applicationContext
    val (before, after, result) = synchronized(lock) {
      val before = flow.value
      val (next, result) = change(before)
      val pruned = ReminderOps.prune(next, clock())
      if (pruned != before) {
        flow.value = pruned
        persist(app, pruned)
      }
      Triple(before, pruned, result)
    }
    if (before != after) runCatching { listener?.onChanged(app, before, after, source) }
        .onFailure { Log.w(TAG, "reminders change listener failed", it) }
    return result
  }

  /** Applies one edit; a refusal leaves the state as it was. */
  fun apply(context: Context, source: Source, edit: (ReminderState) -> ReminderResult): ReminderResult =
      update(context, source) { s ->
        when (val r = edit(s)) {
          is ReminderResult.Ok -> r.state to r
          is ReminderResult.Refused -> s to r
        }
      }

  private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE))

  private fun read(context: Context): ReminderState? {
    val f = file(context)
    if (!f.baseFile.exists()) return null
    return ReminderCodec.decodeState(JSONObject(String(f.readFully(), Charsets.UTF_8))) ?: error("saved reminders failed validation")
  }

  private fun persist(context: Context, s: ReminderState) {
    val bytes = ReminderCodec.encodeState(s).toString().toByteArray(Charsets.UTF_8)
    writer.execute {
      val f = file(context)
      val out = runCatching { f.startWrite() }.getOrElse { Log.w(TAG, "couldn't save reminders", it); return@execute }
      runCatching { out.write(bytes); f.finishWrite(out) }
          .onFailure { f.failWrite(out); Log.w(TAG, "couldn't save reminders", it) }
    }
  }
}
