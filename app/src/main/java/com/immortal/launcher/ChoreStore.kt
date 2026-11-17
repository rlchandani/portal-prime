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
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * This Portal's chores replica. In memory is the source of truth; every change is pruned, saved
 * (atomic file, off the caller's thread) and published as a [StateFlow]. Mutations from widget
 * taps, phone requests (HTTP threads) and peer exchanges are serialized on one lock.
 */
internal object ChoreStore {
  private const val TAG = "HearthpaneChores"
  private const val FILE = "household_chores.json"

  /** Where a change came from: celebrations chime only for LOCAL and PHONE (one room, not all). */
  enum class Source { LOCAL, PHONE, PEER }

  fun interface Listener { fun onChanged(context: Context, before: ChoreState, after: ChoreState, source: Source) }

  private val lock = Any()
  private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "hearthpane-chores-io").apply { isDaemon = true } }
  private val flow = MutableStateFlow(ChoreState())
  @Volatile private var loaded = false
  @Volatile private var listener: Listener? = null
  @Volatile internal var clock: () -> Long = System::currentTimeMillis
  @Volatile internal var zone: () -> TimeZone = TimeZone::getDefault

  val state: StateFlow<ChoreState> get() = flow

  fun today(): Int = ChoreDays.day(clock(), zone())

  fun setListener(l: Listener) { listener = l }

  fun current(context: Context): ChoreState { ensureLoaded(context); return flow.value }

  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(lock) {
      if (loaded) return
      val app = context.applicationContext
      val read = runCatching { read(app) }.onFailure {
        Log.w(TAG, "saved chores unreadable; set aside, starting empty", it)
        runCatching { File(app.filesDir, FILE).renameTo(File(app.filesDir, "$FILE.unreadable")) }
      }
      flow.value = read.getOrNull()?.let { ChoreOps.prune(it, today()) } ?: ChoreState()
      loaded = true
    }
  }

  /** Applies [change] atomically; saves and announces only a real change. Returns the change's result. */
  fun <R> update(context: Context, source: Source, change: (ChoreState) -> Pair<ChoreState, R>): R {
    ensureLoaded(context)
    val app = context.applicationContext
    val (before, after, result) = synchronized(lock) {
      val before = flow.value
      val (next, result) = change(before)
      val pruned = ChoreOps.prune(next, today())
      if (pruned != before) {
        flow.value = pruned
        persist(app, pruned)
      }
      Triple(before, pruned, result)
    }
    if (before != after) runCatching { listener?.onChanged(app, before, after, source) }
        .onFailure { Log.w(TAG, "chores change listener failed", it) }
    return result
  }

  /** Applies one edit; a refusal leaves the state as it was. */
  fun apply(context: Context, source: Source, edit: (ChoreState) -> ChoreResult): ChoreResult =
      update(context, source) { s ->
        when (val r = edit(s)) {
          is ChoreResult.Ok -> r.state to r
          is ChoreResult.Refused -> s to r
        }
      }

  private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE))

  private fun read(context: Context): ChoreState? {
    val f = file(context)
    if (!f.baseFile.exists()) return null
    return ChoreCodec.decodeState(JSONObject(String(f.readFully(), Charsets.UTF_8))) ?: error("saved chores failed validation")
  }

  private fun persist(context: Context, s: ChoreState) {
    val bytes = ChoreCodec.encodeState(s).toString().toByteArray(Charsets.UTF_8)
    writer.execute {
      val f = file(context)
      val out = runCatching { f.startWrite() }.getOrElse { Log.w(TAG, "couldn't save chores", it); return@execute }
      runCatching { out.write(bytes); f.finishWrite(out) }
          .onFailure { f.failWrite(out); Log.w(TAG, "couldn't save chores", it) }
    }
  }
}
