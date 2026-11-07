/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** Raw persistence for [RoutineRepository]; writes return false when nothing was stored. */
internal interface RoutineStorage {
  fun readDoc(): String?
  fun readRuns(): String?
  fun writeDoc(json: String): Boolean
  fun writeRuns(json: String): Boolean
}

/** Wall clock for routines: local ms (UTC + zone offset), injectable for tests. */
internal interface RoutineClock {
  fun nowLocalMs(): Long
  fun toUtcMs(localMs: Long): Long
}

internal object SystemRoutineClock : RoutineClock {
  override fun nowLocalMs(): Long = System.currentTimeMillis().let { it + TimeZone.getDefault().getOffset(it) }
  override fun toUtcMs(localMs: Long): Long {
    val zone = TimeZone.getDefault()
    val guess = localMs - zone.getOffset(localMs)
    return localMs - zone.getOffset(guess)
  }
}

/** App-private prefs. Not a user setting: routines are content edited from Toolkit, like To-do. */
internal class PrefsRoutineStorage(context: Context) : RoutineStorage {
  private val prefs = context.applicationContext.getSharedPreferences("immortal_routines", Context.MODE_PRIVATE)
  override fun readDoc(): String? = prefs.getString("doc", null)
  override fun readRuns(): String? = prefs.getString("runs", null)
  override fun writeDoc(json: String): Boolean = prefs.edit().putString("doc", json).commit()
  override fun writeRuns(json: String): Boolean = prefs.edit().putString("runs", json).commit()
}

/**
 * The Portal's RoutineStore: the routine document (edited from the phone) and today's runs.
 * Thread-safe (route thread, alarm receiver and UI). An unreadable document is reported as
 * [Unreadable] and never overwritten blindly.
 */
internal class RoutineRepository(private val storage: RoutineStorage, val clock: RoutineClock) {
  object Unreadable : Exception("routine storage unreadable")

  sealed interface Outcome {
    data class Saved(val doc: RoutineDoc) : Outcome
    data class Stale(val doc: RoutineDoc) : Outcome
    data class Invalid(val code: String) : Outcome
    data object StorageFull : Outcome
    data object Unavailable : Outcome
  }

  private val empty = RoutineDoc(0, emptyList(), emptyList())

  /** The stored document (empty when none yet). Throws [Unreadable] when storage is corrupt. */
  @Synchronized fun docOrThrow(): RoutineDoc {
    val raw = storage.readDoc() ?: return empty
    return runCatching { RoutineCodec.parseDoc(JSONObject(raw)) }.getOrNull() ?: throw Unreadable
  }

  /** The document, or an empty one when storage is unreadable (display paths). */
  fun doc(): RoutineDoc = runCatching { docOrThrow() }.getOrDefault(empty)

  @Synchronized fun mutate(body: JSONObject): Outcome {
    val doc = try { docOrThrow() } catch (_: Unreadable) { return Outcome.Unavailable }
    return when (val r = RoutineMutations.apply(doc, body, RoutineDates.day(clock.nowLocalMs()))) {
      is RoutineMutations.Result.Stale -> Outcome.Stale(r.doc)
      is RoutineMutations.Result.Invalid -> Outcome.Invalid(r.code)
      is RoutineMutations.Result.Ok ->
        if (storage.writeDoc(RoutineCodec.docJson(r.doc).toString())) Outcome.Saved(r.doc) else Outcome.StorageFull
    }
  }

  /** Today's runs by routine id; runs from other days are dropped. */
  @Synchronized fun runs(): Map<String, RoutineRun> {
    val today = RoutineDates.day(clock.nowLocalMs())
    val arr = storage.readRuns()?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyMap()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(RoutineRunLogic::parseRun) }
        .filter { it.day == today }.associateBy { it.rid }
  }

  /** Stores [run], replacing the same routine's run. False when storage refused it. */
  @Synchronized fun putRun(run: RoutineRun): Boolean {
    val next = runs() + (run.rid to run)
    return storage.writeRuns(JSONArray().apply { next.values.forEach { put(RoutineRunLogic.runJson(it)) } }.toString())
  }
}
