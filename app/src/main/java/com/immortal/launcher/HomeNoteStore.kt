/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId

/**
 * Process-wide owner of shared notes (persisted, survives restarts). Compose collects [notes];
 * HTTP and bus handlers call the mutators from any thread. Clock and zone are injectable.
 */
internal object HomeNoteStore {
  private const val PREFS = "immortal_share_notes"
  private const val KEY = "notes"
  private val state = MutableStateFlow(HomeNotes())
  @Volatile private var loaded = false
  @Volatile var clock: () -> Long = System::currentTimeMillis
  @Volatile var zone: () -> ZoneId = ZoneId::systemDefault
  /** When the last note chimed (for the "Soft chime" bubble); never set in quiet hours. */
  val chimedAt = MutableStateFlow(0L)

  fun notes(context: Context): StateFlow<HomeNotes> { ensureLoaded(context); return state.asStateFlow() }

  fun current(context: Context): HomeNotes { ensureLoaded(context); return state.value }

  private fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      state.value = HomeNotes.fromJson(prefs.getString(KEY, null))
      loaded = true
    }
  }

  private fun change(context: Context, transform: (HomeNotes) -> HomeNotes): HomeNotes = synchronized(this) {
    ensureLoaded(context)
    val before = state.value
    val after = transform(before)
    if (after != before) {
      state.value = after
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, after.toJson()).apply()
    }
    after
  }

  fun add(context: Context, note: HomeNote) = change(context) { it.add(note) }
  fun dismiss(context: Context, id: String) = change(context) { it.dismiss(id, clock()) }
  fun retract(context: Context, id: String) = change(context) { it.retract(id, clock()) }
  fun undo(context: Context, id: String) = change(context) { it.undo(id) }
  fun link(context: Context, id: String, linkState: String) = change(context) { it.withLink(id, linkState) }

  /** Applies the 4:00 AM clear when enabled. Called on Home resume and before each read-out. */
  fun clearIfDue(context: Context) {
    if (!ShareConfig.load(context).clearNotesOvernight) return
    change(context) { it.clearOvernight(clock(), zone()) }
  }

  /** Millis until the next note would clear, for a single deadline timer on Home (no polling). */
  fun nextClearInMs(context: Context): Long? {
    if (!ShareConfig.load(context).clearNotesOvernight) return null
    val next = current(context).active.minOfOrNull { HomeNoteTime.overnightAfter(it.at, zone()) } ?: return null
    return (next - clock()).coerceAtLeast(0)
  }
}

/** Process-wide fresh-photo queue, shared by the slideshow and the Home photo widget. */
internal object FreshPhotoStore {
  private const val PREFS = "immortal_share_fresh"
  private const val KEY = "queue"
  private val state = MutableStateFlow(FreshPhotoQueue())
  @Volatile private var loaded = false
  @Volatile var clock: () -> Long = System::currentTimeMillis
  @Volatile var zone: () -> ZoneId = ZoneId::systemDefault

  fun queue(context: Context): StateFlow<FreshPhotoQueue> { ensureLoaded(context); return state.asStateFlow() }

  private fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      state.value = FreshPhotoQueue.fromJson(context.applicationContext
          .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
      loaded = true
    }
  }

  private fun <T> change(context: Context, transform: (FreshPhotoQueue) -> Pair<T, FreshPhotoQueue>): T = synchronized(this) {
    ensureLoaded(context)
    val (result, after) = transform(state.value)
    if (after != state.value) {
      state.value = after
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, after.toJson()).apply()
    }
    result
  }

  fun add(context: Context, photo: FreshPhoto) = change(context) { Unit to it.add(photo) }

  /** A slideshow turn: the next fresh photo (or null). Waits during quiet hours. */
  fun takeTurn(context: Context): FreshPhoto? =
      change(context) { it.takeTurn(clock(), ShareQuiet.isQuietNow(context, clock())) }

  data class SlideshowTurn(val path: String, val index: Int, val inserted: Boolean)

  /** Slideshow seam: the fresh photo taking this turn and where it sits in (or joins) [paths]. */
  fun slideshowTurn(context: Context, paths: List<String>, current: Int): SlideshowTurn? {
    val fresh = takeTurn(context) ?: return null
    if (!java.io.File(fresh.path).isFile) return null
    val (index, inserted) = FreshPhotoSplice.place(paths, current, fresh.path)
    return SlideshowTurn(fresh.path, index, inserted)
  }

  /** The fresh photo the Home widget should show now, joining a running turn if there is one. */
  fun forHome(context: Context): FreshPhoto? =
      change(context) { it.forHome(clock(), ShareQuiet.isQuietNow(context, clock())) }

  /** The badge for [path] while its turn runs, or null. Respects the "New from" setting. */
  fun badgeFor(context: Context, path: String): String? {
    if (!ShareConfig.load(context).freshPhotoBadge) return null
    ensureLoaded(context)
    val now = clock()
    val fresh = state.value.current(now)?.takeIf { it.path == path } ?: return null
    return FreshPhotoCaption.label(fresh.from, fresh.at, now, zone())
  }
}
