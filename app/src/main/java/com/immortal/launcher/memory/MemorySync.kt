/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.HouseholdListPeerAuth
import com.immortal.launcher.HouseholdSeal
import com.immortal.launcher.PeerExchangeAuth
import com.immortal.launcher.memory.MemoryFact.State
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Jarvis knows, across Portals (plan §3.8, slice 5). Facts and Recent topics sync; usage counts and chip
 * feedback stay on the Portal that made them. Pure: the wire and the merge, with no Android.
 *
 * **Merge** ([merge]) is idempotent and order-free, so any two Portals that exchange end up equal:
 * - evidence is a union keyed by (source, time, Portal), so the same conversation never counts twice;
 * - the person's own word (Keep or remember → CONFIRMED, Forget → SUPPRESSED) beats anything learned, and between two
 *   choices the later one wins; at the same moment, Forget wins. A forgotten fact is never re-learned from another
 *   Portal's mentions either: **Forget wins everywhere**, and a thread naming it goes with it;
 * - "Forget all" is a scope tombstone ([MemoryProfile.forgotAllMs]): everything learned before it is dropped on
 *   every Portal, and [wipedBy] tells the Portal to drop its usage counts for that scope as well;
 * - pause follows the latest choice; a Recent topic keeps its highest shown count, so it is followed up once.
 *
 * **Wire:** a signed bus signal `memory_rev` `{h, p, s: {scopeId: rev}}` names scopes only by keyed ids ([scopeId]),
 * never a person's token, and fits one datagram. A Portal whose copy of a shared scope differs posts its profiles for
 * those scopes, sealed ([sealedBody], AES-GCM under a key derived from the household key with
 * `hearthpane.memory.v1`, bound to the exchange nonce and direction); the reply is the merge, sealed back.
 */
internal object MemorySync {
  const val SIGNAL_TYPE = "memory_rev"
  const val REQUEST_TYPE = "memory_sync"
  const val REQUEST = HouseholdSeal.REQUEST
  const val RESPONSE = HouseholdSeal.RESPONSE

  /** The household and up to eight people (Who's there's limit). */
  const val MAX_SCOPES = 9
  const val MAX_BODY_CHARS = 4 * 1024 * 1024
  private const val ID_CHARS = 12
  private const val REV_CHARS = 16
  private val HEX_ID = Regex("[0-9a-f]{$ID_CHARS}")
  private val HEX_REV = Regex("[0-9a-f]{$REV_CHARS}")

  val auth = PeerExchangeAuth(REQUEST_TYPE, "memory_state")
  private val seal = HouseholdSeal("hearthpane.memory.v1", "hearthpane-memory", MAX_BODY_CHARS)

  /** A peer's announcement: where to reach it, and its revision of each scope it holds (by keyed id). */
  data class Signal(val host: String, val port: Int, val revs: Map<String, String>)

  // ---- merge ----

  /** [a] and [b] (the same scope, from two Portals) as one, brought up to [nowMs]. */
  fun merge(a: MemoryProfile, b: MemoryProfile, nowMs: Long): MemoryProfile {
    require(a.scope == b.scope) { "different scopes" }
    val forgotAll = maxOf(a.forgotAllMs, b.forgotAllMs)
    val x = after(a, forgotAll)
    val y = after(b, forgotAll)
    val mine = x.facts.associateBy { it.id }
    val theirs = y.facts.associateBy { it.id }
    val facts = (mine.keys + theirs.keys).sorted().map { mergeFact(mine[it], theirs[it]) }
    val forgotten = facts.filter { it.state == State.SUPPRESSED }.map { it.id }.toSet()
    val named = (x.facts + y.facts).filter { it.id in forgotten && it.label.isNotBlank() }
    val topics = MemoryLearner.topicsWithout(mergeTopics(x.recentTopics, y.recentTopics),
        named + facts.filter { it.id in forgotten })
    val paused = when {
      a.pausedMs > b.pausedMs -> a.paused
      b.pausedMs > a.pausedMs -> b.paused
      else -> a.paused || b.paused // the same moment (or never set): learning stays off
    }
    return MemoryLearner.refresh(MemoryProfile(a.scope, facts, topics, paused, maxOf(a.pausedMs, b.pausedMs),
        forgotAll), nowMs)
  }

  /** Whether syncing [before] into [after] carried a newer "Forget all" (so this Portal's counts for it go too). */
  fun wipedBy(before: MemoryProfile, after: MemoryProfile): Boolean = after.forgotAllMs > before.forgotAllMs

  /** [p] without anything from before a "Forget all" at [forgotAllMs]. */
  private fun after(p: MemoryProfile, forgotAllMs: Long): MemoryProfile {
    if (forgotAllMs <= 0) return p
    val facts = p.facts.mapNotNull { f ->
      if (f.state in CHOSEN) return@mapNotNull f.takeIf { it.stateMs > forgotAllMs }
      val evidence = f.evidence.filter { it.atMs > forgotAllMs }
      if (evidence.isEmpty()) null
      else f.copy(evidence = evidence, foldedEvidence = 0, firstSeenMs = evidence.minOf { it.atMs })
    }
    return p.copy(facts = facts, recentTopics = p.recentTopics.filter { it.atMs > forgotAllMs })
  }

  private val CHOSEN = setOf(State.CONFIRMED, State.SUPPRESSED, State.TOMBSTONE)

  /** Forget (and a tombstone) beats Keep at the same moment. */
  private fun rank(s: State) = when (s) {
    State.TOMBSTONE -> 3
    State.SUPPRESSED -> 2
    State.CONFIRMED -> 1
    else -> 0
  }

  private fun mergeFact(p: MemoryFact?, q: MemoryFact?): MemoryFact {
    if (p == null) return q!!
    if (q == null) return p
    val chosen = listOf(p, q).filter { it.state in CHOSEN }
        .maxWithOrNull(compareBy<MemoryFact>({ it.stateMs }, { rank(it.state) }))
    return when (chosen?.state) {
      State.SUPPRESSED, State.TOMBSTONE -> chosen.normalized()
      State.CONFIRMED -> union(p, q).copy(state = State.CONFIRMED, stateMs = chosen.stateMs, confidence = 1.0)
      else -> union(p, q).copy(state = State.TENTATIVE) // recomputed from the joined evidence by refresh
    }
  }

  private fun union(p: MemoryFact, q: MemoryFact): MemoryFact {
    val newer = listOf(p, q).filter { it.label.isNotBlank() }
        .maxWithOrNull(compareBy<MemoryFact>({ it.lastSeenMs }, { it.label })) ?: p
    val evidence = (p.evidence + q.evidence).distinctBy { Triple(it.source, it.atMs, it.portal) }
        .sortedWith(compareByDescending<Evidence> { it.atMs }.thenBy { it.portal }.thenBy { it.source }
            .thenBy { it.conversationId.orEmpty() })
    return newer.copy(
        evidence = evidence,
        foldedEvidence = maxOf(p.foldedEvidence, q.foldedEvidence),
        firstSeenMs = minOf(p.firstSeenMs, q.firstSeenMs),
        lastSeenMs = maxOf(p.lastSeenMs, q.lastSeenMs),
        sensitive = p.sensitive || q.sensitive,
    )
  }

  private fun mergeTopics(a: List<RecentTopic>, b: List<RecentTopic>): List<RecentTopic> =
      (a + b).groupBy { it.topic }.values.map { same ->
        same.maxWith(compareBy<RecentTopic>({ it.atMs }, { it.gist }, { it.openQuestion.orEmpty() }))
            .copy(shownCount = same.maxOf { it.shownCount })
      }.sortedBy { it.topic }

  // ---- revisions and the signal ----

  /**
   * [p]'s revision under [key]: everything that syncs, but not confidence or a learned state (both follow from the
   * evidence and the clock), so decay alone never looks like a change.
   */
  fun rev(key: ByteArray, p: MemoryProfile): String {
    val facts = p.facts.sortedBy { it.id }.joinToString(";") { f ->
      val chosen = if (f.state in CHOSEN) "${f.state}@${f.stateMs}" else "-"
      val ev = f.evidence.map { "${it.source}@${it.atMs}@${it.portal}" }.sorted().joinToString(",")
      "${f.id}|$chosen|${f.label}|$ev|${f.foldedEvidence}"
    }
    val topics = p.recentTopics.sortedBy { it.topic }
        .joinToString(";") { "${it.topic}|${it.atMs}|${it.shownCount}|${it.gist}|${it.openQuestion.orEmpty()}" }
    return hmac(key, "memory-rev|${p.scope.token}|$facts#$topics#${p.paused}@${p.pausedMs}#${p.forgotAllMs}")
        .take(REV_CHARS)
  }

  /** The keyed id a signal names [scope] by (no token, so the LAN never sees whose profiles exist). */
  fun scopeId(key: ByteArray, scope: MemoryScope): String = hmac(key, "memory-scope|${scope.token}").take(ID_CHARS)

  /** The signal announcing [profiles] (at most [MAX_SCOPES]) at [host]:[port]. */
  fun signalBody(key: ByteArray, host: String, port: Int, profiles: List<MemoryProfile>): JSONObject =
      JSONObject().put("h", host).put("p", port).put("s", JSONObject().apply {
        profiles.take(MAX_SCOPES).forEach { put(scopeId(key, it.scope), rev(key, it)) }
      })

  /** The signal if it names a private LAN address, a port and well-formed revisions; null otherwise. */
  fun parseSignal(body: JSONObject): Signal? {
    val host = (body.opt("h") as? String)?.takeIf(HouseholdListPeerAuth::isPrivateLan) ?: return null
    val port = (body.opt("p") as? Number)?.toInt()?.takeIf { it in 1..65535 } ?: return null
    val s = body.opt("s") as? JSONObject ?: return null
    if (s.length() > MAX_SCOPES) return null
    val revs = s.keys().asSequence().associateWith { s.opt(it) as? String }
    if (revs.any { (id, rev) -> !HEX_ID.matches(id) || rev == null || !HEX_REV.matches(rev) }) return null
    return Signal(host, port, revs.mapValues { it.value!! })
  }

  /** The scopes of [mine] the peer also holds, at another revision: the ones worth an exchange. */
  fun differing(key: ByteArray, signal: Signal, mine: List<MemoryProfile>): List<MemoryScope> = mine.filter { p ->
    signal.revs[scopeId(key, p.scope)]?.let { it != rev(key, p) } == true
  }.map { it.scope }

  // ---- sealed bodies ----

  /** `{sealed}`: [profiles] sealed for [direction] of exchange [nonce]. */
  fun sealedBody(key: ByteArray, nonce: String, direction: String, profiles: List<MemoryProfile>): String {
    val plain = JSONObject().put("profiles", JSONArray().apply {
      profiles.take(MAX_SCOPES).forEach { put(JSONObject(MemoryProfileCodec.encode(it))) }
    }).toString()
    return JSONObject().put("sealed", seal.seal(key, nonce, direction, plain)).toString()
  }

  /**
   * The profiles inside [body] for the scopes in [accepted] (others are dropped unread), each re-normalized (so the
   * household never takes a sensitive fact); null if the body doesn't open.
   */
  fun openBody(key: ByteArray, nonce: String, direction: String, body: String,
               accepted: Set<MemoryScope>): List<MemoryProfile>? {
    val sealed = runCatching { JSONObject(body).opt("sealed") as? String }.getOrNull() ?: return null
    val plain = seal.open(key, nonce, direction, sealed) ?: return null
    val items = runCatching { JSONObject(plain).getJSONArray("profiles") }.getOrNull() ?: return null
    return (0 until minOf(items.length(), MAX_SCOPES)).mapNotNull { i ->
      val o = items.optJSONObject(i) ?: return@mapNotNull null
      val scope = MemoryScope.fromToken(o.optString("scope"))?.takeIf { it in accepted } ?: return@mapNotNull null
      runCatching { MemoryProfileCodec.decode(o.toString(), scope) }.getOrNull()
    }.distinctBy { it.scope }
  }

  private fun hmac(key: ByteArray, text: String): String =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
          .doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
