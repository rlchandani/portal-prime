/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/** What the Portal remembers about one paired phone. [id] is [RemoteSessionPolicy.idOf] the token. */
internal data class RemoteSessionRecord(
    val id: String,
    val createdMs: Long,
    val lastSeenMs: Long,
    val label: String,
    val test: Boolean = false,
)

/** The sessions that remain after [RemoteSessionPolicy.reconcile], and whether storage must change. */
internal data class ReconciledSessions(
    val tokens: List<String>,
    val records: Map<String, RemoteSessionRecord>,
    val changed: Boolean,
)

/**
 * Pure lifetime rules for phone sessions. A session expires after [IDLE_EXPIRY_MS] without use;
 * each use refreshes it (written at most once per [SEEN_WRITE_INTERVAL_MS], so a touchpad drag
 * doesn't rewrite prefs). A token stored by an older build has no record: it gets one stamped
 * "now", so an upgrade starts the clock instead of expiring anybody.
 */
internal object RemoteSessionPolicy {
  const val IDLE_EXPIRY_MS = 180L * 24 * 60 * 60 * 1000
  const val SEEN_WRITE_INTERVAL_MS = 60L * 60 * 1000
  const val LEGACY_LABEL = "Phone"
  const val TEST_LABEL = "Test session"
  private const val MAX_LABEL_CHARS = 40
  private const val ID_BYTES = 8
  private const val BYTE_MASK = 0xff

  /** An opaque per-session id: the first 64 bits of SHA-256 over the token (never the token). */
  fun idOf(token: String): String =
      java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
          .take(ID_BYTES).joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

  /** A clock that went backwards never expires anyone; only real idle time does. */
  fun isExpired(record: RemoteSessionRecord, now: Long): Boolean = now - record.lastSeenMs > IDLE_EXPIRY_MS

  /** The refreshed record, or null when the stored one is recent enough to leave alone. */
  fun touched(record: RemoteSessionRecord, now: Long): RemoteSessionRecord? =
      if (now - record.lastSeenMs >= SEEN_WRITE_INTERVAL_MS) record.copy(lastSeenMs = now) else null

  /** Printable, single-line, short. Blank or hostile input falls back to [LEGACY_LABEL]. */
  fun cleanLabel(raw: String?): String =
      raw.orEmpty().filter { !it.isISOControl() }.trim().take(MAX_LABEL_CHARS).ifBlank { LEGACY_LABEL }

  /**
   * Joins the stored token list (the format older builds read, kept so a downgrade still works)
   * with the records: a token without a record gets one starting now, a record without a token is
   * dropped, and expired sessions are dropped from both.
   */
  fun reconcile(tokens: List<String>, records: Map<String, RemoteSessionRecord>, now: Long): ReconciledSessions {
    val keptTokens = ArrayList<String>()
    val keptRecords = LinkedHashMap<String, RemoteSessionRecord>()
    var changed = false
    for (token in tokens.distinct()) {
      val id = idOf(token)
      val record = records[id] ?: RemoteSessionRecord(id, now, now, LEGACY_LABEL).also { changed = true }
      if (isExpired(record, now)) { changed = true; continue }
      keptTokens += token
      keptRecords[id] = record
    }
    if (keptRecords.size != records.size) changed = true
    return ReconciledSessions(keptTokens, keptRecords, changed || tokens.size != keptTokens.size)
  }

  fun encode(records: Map<String, RemoteSessionRecord>): String = JSONObject().also { out ->
    records.values.forEach { r ->
      out.put(r.id, JSONObject().put("created", r.createdMs).put("seen", r.lastSeenMs).put("label", r.label)
          .put("test", r.test))
    }
  }.toString()

  /** Unreadable storage yields no records, which [reconcile] turns into fresh "now" records. */
  fun decode(raw: String?): Map<String, RemoteSessionRecord> {
    if (raw.isNullOrBlank()) return emptyMap()
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
    val out = LinkedHashMap<String, RemoteSessionRecord>()
    for (id in json.keys()) {
      val o = json.optJSONObject(id) ?: continue
      val created = o.optLong("created", -1L)
      val seen = o.optLong("seen", -1L)
      if (created < 0 || seen < 0) continue
      out[id] = RemoteSessionRecord(id, created, seen, cleanLabel(o.optString("label")), o.optBoolean("test"))
    }
    return out
  }
}
