/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Live entity state plus in-flight optimistic overrides (pure, immutable, unit tested). The UI
 * moves at once; HA's echo clears the override. With no echo by [CcLive.ECHO_MS] the override is
 * reverted and the entity reported as not responding — actions are never queued or replayed.
 */
internal data class CcPendingCall(
    val entityId: String,
    val optimistic: CcOptimistic,
    val sentAt: Long,
    /** Set once HA answered `call_service` successfully. */
    val acknowledged: Boolean = false,
    /** HA sent any update for the entity after we asked. */
    val updated: Boolean = false,
)

internal data class CcLive(
    val raw: Map<String, CcEntity> = emptyMap(),
    val pending: Map<String, CcPendingCall> = emptyMap(),
    /** Appliances that just finished: entity id → when. */
    val done: Map<String, Long> = emptyMap(),
) {
  /** What the screens show: HA's state with the overrides on top. */
  fun view(): Map<String, CcEntity> {
    if (pending.isEmpty()) return raw
    val out = HashMap(raw)
    pending.values.forEach { p -> raw[p.entityId]?.let { out[p.entityId] = p.optimistic.applyTo(it) } }
    return out
  }

  /** Replace everything from a fresh snapshot (keeps overrides whose entity still exists). */
  fun snapshot(entities: Collection<CcEntity>): CcLive {
    val map = entities.associateBy { it.id }
    return copy(raw = map, pending = pending.filterKeys { it in map })
  }

  /** Merge updated entities (from a diff or `state_changed`); clears overrides HA confirmed. */
  fun update(changed: Map<String, CcEntity?>, now: Long): CcLive {
    var raw = this.raw
    var pending = this.pending
    var done = this.done
    changed.forEach { (id, e) ->
      val before = raw[id]
      if (e == null) { raw = raw - id; pending = pending - id; return@forEach }
      // An unchanged entity (e.g. a full answer confirming a last-known state) keeps its instance.
      if (e == before && id !in pending) return@forEach
      raw = raw + (id to e)
      if (CcAlerts.finished(before, e)) done = done + (id to now)
      if (e.isOn && done.containsKey(id)) done = done - id
      pending[id]?.let { p -> pending = if (p.optimistic.matches(e)) pending - id else pending + (id to p.copy(updated = true)) }
    }
    if (raw === this.raw && pending === this.pending && done === this.done) return this
    return CcLive(raw, pending, done)
  }

  /** Same as [update] for a compressed `subscribe_entities` event. */
  fun applyCompressed(event: org.json.JSONObject, now: Long): CcLive {
    val (next, touched) = CcEntityParser.applyEvent(raw, event)
    return update(touched.associateWith { next[it] }, now)
  }

  fun send(call: CcCall, now: Long): CcLive {
    val id = call.entityId ?: return this
    val o = call.optimistic ?: return this
    return copy(pending = pending + (id to CcPendingCall(id, o, now)))
  }

  fun acknowledged(entityId: String): CcLive =
      pending[entityId]?.let { copy(pending = pending + (entityId to it.copy(acknowledged = true))) } ?: this

  /** HA refused the call: put the entity back as HA has it. */
  fun failed(entityId: String): CcLive = copy(pending = pending - entityId)

  /**
   * Drops overrides older than [ECHO_MS]. Returns the entities that never answered at all (no
   * update since we asked) so the UI can say "didn't respond".
   */
  fun expire(now: Long): Pair<CcLive, List<String>> {
    val old = pending.values.filter { now - it.sentAt >= ECHO_MS }
    if (old.isEmpty()) return this to emptyList()
    val silent = old.filter { !it.updated }.map { it.entityId }
    return copy(pending = pending - old.map { it.entityId }.toSet()) to silent
  }

  fun dismissDone(entityId: String): CcLive = copy(done = done - entityId)

  companion object {
    const val ECHO_MS = 5_000L
  }
}

/** Reconnect delays from the mock: 8 s doubling to a 60 s ceiling; the first retry is quick. Pure. */
internal object CcBackoff {
  const val FIRST_MS = 1_000L
  const val BASE_MS = 8_000L
  const val MAX_MS = 60_000L
  /** A refused token won't fix itself: retry rarely (a new token from the phone restarts it). */
  const val AUTH_MS = 300_000L
  private const val MAX_SHIFT = 8

  fun delayMs(attempt: Int): Long =
      if (attempt <= 0) FIRST_MS else (BASE_MS shl (attempt - 1).coerceIn(0, MAX_SHIFT)).coerceAtMost(MAX_MS)
}
