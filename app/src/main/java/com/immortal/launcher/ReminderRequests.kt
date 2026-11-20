/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** A parsed phone request for `/remote/reminders`. */
internal sealed interface ReminderRequest {
  data class Add(val title: String, val person: String?, val dueMs: Long?, val private: Boolean?) : ReminderRequest
  data class Edit(val id: String, val title: String, val person: String?, val dueMs: Long?, val private: Boolean?,
                  val baseSeq: Long) : ReminderRequest
  data class Done(val id: String, val done: Boolean, val baseSeq: Long) : ReminderRequest
  data class Delete(val id: String, val baseSeq: Long) : ReminderRequest
  data class Invalid(val code: String) : ReminderRequest
}

/**
 * The phone side of reminders, pure. The phone is a shared surface: it sees and may change only
 * non-private reminders ([ReminderVisibility.shared]). A private reminder's id is answered exactly
 * like an unknown one, so the phone can't even probe for it. It may *add* a private reminder for
 * someone (write-only: it then shows only as a count).
 */
internal object ReminderRequests {
  fun parse(body: JSONObject): ReminderRequest {
    val id = body.opt("id") as? String
    val base = ChoreCodec.long(body.opt("base")) ?: 0L
    if (base < 0) return ReminderRequest.Invalid("bad_base")
    fun needId(): String? = id?.takeIf(ChoreText::isId)
    return when (body.opt("action")) {
      "add" -> fields(body) { t, p, d, pv -> ReminderRequest.Add(t, p, d, pv) }
      "edit" -> needId()?.let { rid -> fields(body) { t, p, d, pv -> ReminderRequest.Edit(rid, t, p, d, pv, base) } }
          ?: ReminderRequest.Invalid("bad_id")
      "done", "undo" -> needId()?.let { ReminderRequest.Done(it, body.opt("action") == "done", base) } ?: ReminderRequest.Invalid("bad_id")
      "delete" -> needId()?.let { ReminderRequest.Delete(it, base) } ?: ReminderRequest.Invalid("bad_id")
      else -> ReminderRequest.Invalid("bad_action")
    }
  }

  private fun fields(body: JSONObject, make: (String, String?, Long?, Boolean?) -> ReminderRequest): ReminderRequest {
    val title = ChoreText.clean(body.opt("title") as? String, ReminderLimits.TITLE_MAX) ?: return ReminderRequest.Invalid("bad_title")
    val person = when (val raw = body.opt("person")) {
      null, JSONObject.NULL -> null
      is String -> raw.ifEmpty { null }
      else -> return ReminderRequest.Invalid("bad_person")
    }
    if (person != null && !ReminderCodec.isPersonId(person)) return ReminderRequest.Invalid("bad_person")
    val dueRaw = body.opt("due")
    val due = if (dueRaw == null || dueRaw == JSONObject.NULL) null else ChoreCodec.long(dueRaw) ?: return ReminderRequest.Invalid("bad_due")
    val private = body.opt("private")?.let { it as? Boolean ?: return ReminderRequest.Invalid("bad_private") }
    return make(title, person, due?.takeIf { it != 0L }, private)
  }

  /** Applies [req] for the phone. [people] are this Portal's enrolled people. */
  fun apply(s: ReminderState, req: ReminderRequest, people: List<WhosTherePerson>, newId: String, by: String,
            nowMs: Long): ReminderResult = when (req) {
    is ReminderRequest.Invalid -> ReminderResult.Refused(req.code)
    is ReminderRequest.Add -> draft(req.title, req.person, req.dueMs, req.private, people)
        ?.let { ReminderOps.add(s, newId, it, ReminderLimits.PHONE, by, nowMs) } ?: ReminderResult.Refused("unknown_person")
    is ReminderRequest.Edit -> if (!visible(s, req.id)) ReminderResult.Refused("unknown_reminder")
        else draft(req.title, req.person, req.dueMs, req.private, people, s.reminders[req.id])
            ?.let { ReminderOps.edit(s, req.id, it, by, nowMs, req.baseSeq) } ?: ReminderResult.Refused("unknown_person")
    is ReminderRequest.Done -> if (!visible(s, req.id)) ReminderResult.Refused("unknown_reminder")
        else ReminderOps.setDone(s, req.id, req.done, by, nowMs, req.baseSeq)
    is ReminderRequest.Delete -> if (!visible(s, req.id)) ReminderResult.Refused("unknown_reminder")
        else ReminderOps.delete(s, req.id, by, nowMs, req.baseSeq)
  }

  private fun visible(s: ReminderState, id: String): Boolean = s.reminders[id]?.let { !it.deleted && !it.private } == true

  /** Owner must be enrolled here, or be the edited reminder's existing (possibly other-Portal) owner. */
  private fun draft(title: String, personId: String?, due: Long?, private: Boolean?, people: List<WhosTherePerson>,
                    existing: Reminder? = null): ReminderDraft? {
    if (personId != null && existing != null && personId == existing.person && people.none { it.id == personId }) {
      val priv = private ?: existing.private
      return ReminderDraft(title, personId, existing.personName, due, priv)
    }
    val person = personId?.let { id -> people.firstOrNull { it.id == id } ?: return null }
    val priv = (private ?: ReminderVisibility.defaultPrivate(person)) && ReminderVisibility.canBePrivate(person)
    return ReminderDraft(title, person?.id, person?.name.orEmpty(), due, priv)
  }

  /** The phone's GET body: shared reminders, private counts by name, and who can be picked. */
  fun view(s: ReminderState, people: List<WhosTherePerson>, portal: String, ready: Boolean): JSONObject {
    val shared = ReminderVisibility.shared(s, people)
    val rows = shared.reminders.map { r ->
      JSONObject().put("id", r.id).put("title", r.title).put("person", r.person.orEmpty())
          .put("for", ReminderVisibility.ownerLabel(r, people)).put("due", r.dueMs ?: 0L).put("done", r.done)
          .put("by", r.createdBy).put("seq", r.seq)
    }
    val counts = JSONObject().also { o -> shared.privateCounts.forEach { (name, n) -> o.put(name, n) } }
    val who = people.map { p -> JSONObject().put("id", p.id).put("name", p.name).put("kid", p.role.parentManaged) }
    return RemoteFeatureRoutes.ok().put("ready", ready).put("portal", portal).put("reminders", JSONArray(rows))
        .put("privateCounts", counts).put("people", JSONArray(who))
  }
}
