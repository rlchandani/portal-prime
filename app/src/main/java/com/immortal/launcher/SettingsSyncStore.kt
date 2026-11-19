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
 * Persists the [SyncLedger] as one JSON string in `immortal_settings_sync`. It holds household
 * settings values only — secrets and device-local keys never reach the ledger, because only
 * catalog keys are recorded (see [com.immortal.launcher.settings.SettingsSyncCatalog]).
 */
internal object SettingsSyncStore {
  private const val PREFS = "immortal_settings_sync"
  private const val KEY = "ledger"

  fun load(context: Context, wellFormed: (String, Any?) -> Any?): SyncLedger? =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { decode(it, wellFormed) }

  fun save(context: Context, ledger: SyncLedger) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(ledger).toString()).apply()
  }

  private const val ADOPT_CARD_DISMISSED = "adopt_card_dismissed"

  fun adoptCardDismissed(context: Context): Boolean =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ADOPT_CARD_DISMISSED, false)

  fun setAdoptCardDismissed(context: Context, on: Boolean) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(ADOPT_CARD_DISMISSED, false) != on) prefs.edit().putBoolean(ADOPT_CARD_DISMISSED, on).apply()
  }

  fun clear(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
  }

  fun encode(l: SyncLedger): JSONObject {
    val known = JSONObject()
    l.lastKnown.forEach { (k, v) -> known.put(k, v) }
    val peers = JSONArray()
    l.peers.values.forEach { peers.put(JSONObject().put("id", it.id).put("name", it.name).put("seen", it.seenAt).put("synced", it.syncedAt)) }
    return JSONObject().put("v", 1).put("phase", l.phase.name).put("clock", l.clock)
        .put("records", records(l.records.values)).put("known", known).put("peers", peers)
        .put("lastSyncAt", l.lastSyncAt)
        .apply { l.adoptedFrom?.let { put("adoptedFrom", it) } }
        .apply {
          l.pending?.let { p ->
            put("pending", JSONObject().put("fromId", p.fromId).put("fromName", p.fromName).put("records", records(p.records.values)))
          }
        }
  }

  /** The stored ledger with anything that is no longer a household key dropped, or null if unreadable. */
  fun decode(text: String, wellFormed: (String, Any?) -> Any?): SyncLedger? = runCatching {
    val o = JSONObject(text)
    val phase = SyncPhase.entries.firstOrNull { it.name == o.optString("phase") } ?: SyncPhase.JOINING
    val known = o.optJSONObject("known")
    val lastKnown = buildMap {
      known?.keys()?.forEach { k -> wellFormed(k, known.opt(k))?.let { put(k, it) } }
    }
    val peers = o.optJSONArray("peers")?.let { arr ->
      (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
        SyncPeer(it.getString("id"), it.optString("name", "Portal"), it.optLong("seen"), it.optLong("synced"))
      }.associateBy { it.id }
    }.orEmpty()
    val pending = o.optJSONObject("pending")?.let { p ->
      PendingAdopt(p.getString("fromId"), p.optString("fromName", "Portal"), parse(p.optJSONArray("records"), wellFormed))
          .takeIf { it.records.isNotEmpty() }
    }
    SyncLedger(phase, o.optLong("clock").coerceIn(0L, SettingsSyncEngine.MAX_COUNTER),
        parse(o.optJSONArray("records"), wellFormed), lastKnown, peers, pending, o.optLong("lastSyncAt"),
        o.optString("adoptedFrom").takeIf { it.isNotBlank() })
  }.getOrNull()

  private fun records(list: Collection<SyncRecord>): JSONArray =
      JSONArray().apply { list.sortedBy { it.key }.forEach { put(SettingsSyncWire.recordJson(it)) } }

  private fun parse(arr: JSONArray?, wellFormed: (String, Any?) -> Any?): Map<String, SyncRecord> {
    arr ?: return emptyMap()
    val records = (0 until arr.length()).mapNotNull { SettingsSyncWire.parseRecord(arr.optJSONObject(it)) }
    return SettingsSyncEngine.mergeAll(SyncLedger(), records, wellFormed).ledger.records
  }
}
