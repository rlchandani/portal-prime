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

/**
 * The Portal's alarms beyond the main one ([AlarmConfig], id [MAIN_ID], the one Tools › Alarm edits). Jarvis adds
 * them ("also wake me at 6:30 on Saturdays"); each is armed by [AlarmScheduler] with its own request code and rings
 * through [AlarmReceiver] like the main alarm. Up to [MAX] are kept, ids 1..[MAX]. The codec is pure and tolerant:
 * a bad entry is dropped, never the whole list.
 */
internal object MoreAlarms {
  const val MAIN_ID = 0
  const val MAX = 5
  private const val PREFS = "immortal_alarms_more"
  private const val KEY = "alarms"
  private val lock = Any()

  /** One alarm with its slot id ([MAIN_ID] for the main alarm). */
  data class Alarm(val id: Int, val config: AlarmConfig.Config)

  fun encode(alarms: List<Alarm>): String = JSONArray().apply {
    alarms.forEach { a ->
      val c = a.config
      put(JSONObject().put("id", a.id).put("enabled", c.enabled).put("hour", c.hour).put("minute", c.minute)
          .put("label", c.label).put("days", JSONArray(c.days.sorted())))
    }
  }.toString()

  fun decode(raw: String?): List<Alarm> {
    val array = runCatching { JSONArray(raw ?: return emptyList()) }.getOrNull() ?: return emptyList()
    val out = LinkedHashMap<Int, Alarm>()
    for (i in 0 until array.length()) {
      val o = array.optJSONObject(i) ?: continue
      val alarm = runCatching { entry(o) }.getOrNull() ?: continue
      if (alarm.id !in out) out[alarm.id] = alarm
    }
    return out.values.sortedBy { it.id }
  }

  private fun entry(o: JSONObject): Alarm? {
    val id = o.optInt("id", -1).takeIf { it in 1..MAX } ?: return null
    val hour = o.optInt("hour", -1).takeIf { it in 0..23 } ?: return null
    val minute = o.optInt("minute", -1).takeIf { it in 0..59 } ?: return null
    val daysJson = o.optJSONArray("days") ?: JSONArray()
    val days = (0 until daysJson.length()).map { daysJson.optInt(it, -1) }.filter { it in 1..7 }.toSet()
    val label = o.optString("label").trim().ifEmpty { "Alarm" }
    return Alarm(id, AlarmConfig.Config(o.optBoolean("enabled", false), hour, minute, label, days))
  }

  /** The lowest free id, or null when [MAX] are kept. */
  fun freeId(alarms: List<Alarm>): Int? = (1..MAX).firstOrNull { id -> alarms.none { it.id == id } }

  /**
   * [alarms] with a new, turned-on alarm in the lowest free slot (Tools › Alarm's Add alarm); null when all [MAX] are
   * kept. Never takes a turned-off alarm's slot: the household may want it back.
   */
  fun add(alarms: List<Alarm>, config: AlarmConfig.Config): List<Alarm>? {
    val id = freeId(alarms) ?: return null
    return put(alarms, Alarm(id, config.copy(enabled = true, label = config.label.trim().ifEmpty { "Alarm" })))
  }

  /** [alarms] with [alarm] put in its slot (added or replaced). */
  fun put(alarms: List<Alarm>, alarm: Alarm): List<Alarm> =
      (alarms.filterNot { it.id == alarm.id } + alarm).sortedBy { it.id }

  fun load(context: Context): List<Alarm> =
      decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

  fun find(context: Context, id: Int): Alarm? = load(context).firstOrNull { it.id == id }

  /** Applies [change] to the saved list under a lock; returns the saved list. */
  fun edit(context: Context, change: (List<Alarm>) -> List<Alarm>): List<Alarm> = synchronized(lock) {
    val next = change(load(context))
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(next)).commit()
    next
  }

  /** The main alarm then the others, as one list. */
  fun all(context: Context): List<Alarm> = listOf(Alarm(MAIN_ID, AlarmConfig.load(context))) + load(context)

  /** Observes the others' saved list; disposal unregisters the listener. */
  fun observe(context: Context, changed: () -> Unit): () -> Unit = observeSettingsPreferenceChanges(
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), setOf(KEY), changed)
}
