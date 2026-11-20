/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON form of a [ReminderState], for storage and (sealed, see [ReminderSeal]) the Portal↔Portal
 * exchange: `{v:1, r:[{i,t,p,pn,d,dn,dm,pv,cb,cm,q,b,x}]}` (`p` "" = household, `d` 0 = no due time,
 * `x` = tombstone). Peers and files are untrusted: a bad entry is skipped, a bad envelope is null.
 */
internal object ReminderCodec {
  const val VERSION = 1
  private const val SEP = '\u0001'
  private const val DIGEST_CHARS = 12
  const val MAX_ENTRIES = ReminderLimits.MAX_LIVE + ReminderLimits.MAX_TOMBSTONES
  private const val MAX_SEQ = Long.MAX_VALUE / 2
  /** 2001-09-09: anything earlier is junk. */
  private const val MIN_TIME_MS = 1_000_000_000_000L
  private const val MAX_TIME_MS = 32_503_680_000_000L // year 3000

  fun encodeState(s: ReminderState): JSONObject = JSONObject().put("v", VERSION)
      .put("r", JSONArray(s.reminders.values.sortedBy { it.id }.map(::reminderJson)))

  fun decodeState(j: JSONObject?): ReminderState? {
    if (j == null || j.optInt("v", -1) != VERSION) return null
    val arr = j.optJSONArray("r") ?: return null
    // Bounded: a peer can't grow this replica past what the limits allow (live + tombstones).
    val list = (0 until minOf(arr.length(), MAX_ENTRIES)).mapNotNull { arr.optJSONObject(it) }.mapNotNull(::reminder)
    return ReminderState(list.associateBy { it.id })
  }

  fun reminderJson(r: Reminder): JSONObject = JSONObject().put("i", r.id).put("t", r.title).put("p", r.person.orEmpty())
      .put("pn", r.personName).put("d", r.dueMs ?: 0L).put("dn", r.done).put("dm", r.doneMs).put("pv", r.private)
      .put("cb", r.createdBy).put("cm", r.createdMs).put("q", r.seq).put("b", r.by).put("x", r.deleted)

  fun canonical(r: Reminder): String = listOf(r.id, r.seq, r.by, r.deleted, r.title, r.person.orEmpty(), r.personName,
      r.dueMs ?: 0L, r.done, r.doneMs, r.private, r.createdBy, r.createdMs).joinToString("$SEP")

  /**
   * 12 hex chars that change whenever anything does. Keyed with the household key: the digest
   * crosses the LAN in the clear (bus signal), and an unkeyed hash of short private titles could be
   * guessed offline.
   */
  fun digest(s: ReminderState, key: ByteArray): String {
    val text = s.reminders.values.map(::canonical).sorted().joinToString("\n")
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    return mac.doFinal("reminders-digest|$text".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(DIGEST_CHARS)
  }

  private fun reminder(o: JSONObject): Reminder? {
    val id = (o.opt("i") as? String)?.takeIf(ChoreText::isId) ?: return null
    val deleted = o.opt("x") as? Boolean ?: return null
    val seq = ChoreCodec.long(o.opt("q"))?.takeIf { it in 1L..MAX_SEQ } ?: return null
    val by = (o.opt("b") as? String)?.takeIf(ChoreText::isDevice) ?: return null
    val title = ChoreText.clean(o.opt("t") as? String, ReminderLimits.TITLE_MAX) ?: return null
    val person = (o.opt("p") as? String ?: return null).ifEmpty { null }
    if (person != null && !isPersonId(person)) return null
    val personName = (o.opt("pn") as? String)?.let { ChoreText.clean(it, ReminderLimits.NAME_MAX) }.orEmpty()
    val due = ChoreCodec.long(o.opt("d")) ?: return null
    if (due != 0L && !isTime(due)) return null
    val done = o.opt("dn") as? Boolean ?: return null
    val doneMs = ChoreCodec.long(o.opt("dm"))?.takeIf { it == 0L || isTime(it) } ?: return null
    val private = o.opt("pv") as? Boolean ?: return null
    if (private && person == null) return null // the household has no private items
    val createdBy = (o.opt("cb") as? String)?.let { ChoreText.clean(it, ReminderLimits.CREATOR_MAX) }.orEmpty()
    val createdMs = ChoreCodec.long(o.opt("cm"))?.takeIf(::isTime) ?: return null
    return Reminder(id, title, person, personName, due.takeIf { it != 0L }, done, private, createdBy, createdMs, seq, by,
        deleted, doneMs)
  }

  fun isTime(ms: Long): Boolean = ms in MIN_TIME_MS..MAX_TIME_MS

  /** Who's there? ids are random hex/alnum tokens; accept the same shape as device ids. */
  fun isPersonId(text: String?): Boolean = ChoreText.isDevice(text)
}
