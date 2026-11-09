/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Recently played" on this Portal, covering every room: plays here are recorded when the official
 * player really starts the queue item ([MusicPlayDetector]) and broadcast as a signed
 * [MusicHistoryCodec.PLAYED] household message; other Portals' plays are merged on receipt. Kept in
 * private prefs, never sent to any server. Clear / remove apply household-wide.
 */
internal object MusicHistoryFeature {
  private const val TAG = "HearthpaneMusicHistory"
  private const val PREFS = "music_history"
  private const val KEY = "plays_v1"

  private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
  private val flow = MutableStateFlow<List<MusicPlay>>(emptyList())
  @Volatile private var installed = false
  /** Main thread only: the queue entry last recorded, so pause/resume never adds a play. */
  private var lastEntryId: Long? = null

  /** Stored history, newest first (each room's plays; [MusicHistory.listing] dedupes for display). */
  val plays: StateFlow<List<MusicPlay>> = flow.asStateFlow()

  fun listing(): List<MusicPlay> = MusicHistory.listing(flow.value)

  fun install(context: Context) {
    if (installed) return
    installed = true
    val app = context.applicationContext
    HouseholdBus.register(MusicHistoryCodec.PLAYED) { c, m ->
      MusicHistoryCodec.decodeWire(m.body, System.currentTimeMillis())?.let { change(c) { list -> MusicHistory.record(list, it) } }
    }
    HouseholdBus.register(MusicHistoryCodec.CHANGE) { c, m -> MusicHistoryCodec.decodeChange(m.body)?.let { apply(c, it) } }
    scope.launch {
      val saved = withContext(Dispatchers.IO) {
        runCatching { MusicHistoryCodec.decodeList(prefs(app).getString(KEY, null), System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "history unreadable: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
      }
      // Plays that arrived while loading are kept; the merged list is saved back.
      if (saved.isNotEmpty()) change(app) { MusicHistory.mergeAll(it, saved) }
      YouTubeMusicEngine.state.collect { s -> MusicPlayDetector.started(s, lastEntryId)?.let { onStarted(app, it) } }
    }
  }

  /** Removes [videoId] from every Portal's history. */
  fun remove(context: Context, videoId: String) {
    val change = MusicHistoryCodec.Change.Remove(videoId)
    apply(context, change)
    broadcast(context, change)
  }

  /** Clears every Portal's history up to now (a play elsewhere after this moment stays). */
  fun clear(context: Context) {
    val change = MusicHistoryCodec.Change.Clear(System.currentTimeMillis())
    apply(context, change)
    broadcast(context, change)
  }

  private fun onStarted(context: Context, entry: YtQueueEntry) {
    lastEntryId = entry.id
    val track = entry.track
    val play = MusicPlay(track.videoId, track.title, track.artist, track.thumbnailUrl, System.currentTimeMillis(),
        DinnerCall.selfName(context))
    change(context) { MusicHistory.record(it, play) }
    if (!HouseholdBus.send(context, MusicHistoryCodec.PLAYED, MusicHistoryCodec.encodeWire(play))) {
      Log.i(TAG, "play kept on this Portal only (no household key yet)")
    }
  }

  private fun apply(context: Context, change: MusicHistoryCodec.Change) = change(context) { list ->
    when (change) {
      is MusicHistoryCodec.Change.Clear -> MusicHistory.clearedAt(list, change.atMs)
      is MusicHistoryCodec.Change.Remove -> MusicHistory.remove(list, change.videoId)
    }
  }

  private fun broadcast(context: Context, change: MusicHistoryCodec.Change) {
    if (!HouseholdBus.send(context, MusicHistoryCodec.CHANGE, MusicHistoryCodec.encodeChange(change))) {
      Log.i(TAG, "history change kept on this Portal only (no household key yet)")
    }
  }

  /** Atomic update + asynchronous save (callable from the bus thread, HTTP threads or main). */
  private fun change(context: Context, f: (List<MusicPlay>) -> List<MusicPlay>) {
    val next = flow.updateAndGet(f)
    runCatching { prefs(context).edit().putString(KEY, MusicHistoryCodec.encodeList(next)).apply() }
        .onFailure { Log.w(TAG, "history save failed: ${it.javaClass.simpleName}") }
  }

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
