/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Use <peer>'s connection": fetch a household Portal's sealed Home Assistant connection, prove it
 * works by calling Home Assistant's `/api/` with it, and only then save it through the normal
 * connection path. Pure: the roster, client, validation, store and announce come in as functions.
 * Shared by Settings › Home Assistant and the fleet action `ha_adopt`.
 *
 * Nothing here returns, logs or formats the token; outcomes carry only a [Result] and the peer's name.
 */
internal class HaAdoption(
    private val peers: () -> List<HaSharePeer>,
    private val client: Asker,
    /** Calls Home Assistant's `/api/` with the connection: true only for a live, authorised answer. */
    private val validate: (HaShareWire.Connection) -> Boolean,
    private val current: () -> HaShareWire.Connection?,
    /** Saves the connection and restarts everything that watches it. */
    private val save: (HaShareWire.Connection) -> Unit,
    /** Re-announces this Portal on the household bus, so a source that missed our hello learns our address. */
    private val announce: () -> Unit,
    /** Waits for that hello to land (UDP has no acknowledgement). */
    private val settle: () -> Unit,
    private val log: (String) -> Unit,
) {
  /** The two requests the adoption needs from a peer (a [HaShareClient] in production). */
  interface Asker {
    fun probe(peer: HaSharePeer): HaShareAnswer
    fun share(peer: HaSharePeer): HaShareAnswer
  }

  enum class Result(val wire: String, val ok: Boolean = false) {
    ADOPTED("adopted", ok = true),
    /** The peer's connection is exactly the one already saved here (compared in constant time). */
    UNCHANGED("unchanged", ok = true),
    NO_PEER("no_such_peer"),
    AMBIGUOUS("ambiguous_peer"),
    PEER_NOT_CONNECTED("peer_not_connected"),
    REFUSED("refused"),
    RATE_LIMITED("rate_limited"),
    UNREACHABLE("unreachable"),
    INVALID_REPLY("invalid_reply"),
    /** Home Assistant didn't accept the connection from this Portal; nothing was saved. */
    VALIDATION_FAILED("validation_failed"),
    /** This Portal has a different connection already; `replace` was not asked for. */
    ALREADY_CONNECTED("already_connected"),
  }

  data class Outcome(val result: Result, val peerName: String? = null, val validated: Boolean = false)

  /** A household peer that answered a probe as connected. */
  data class Offer(val peer: HaSharePeer, val name: String)

  /**
   * Every known household Portal that is connected to Home Assistant. Blocking, but the peers are
   * asked in parallel, so one switched-off Portal costs one timeout, not one per peer (a fleet
   * server thread or the Settings page waits on this).
   */
  fun offers(): List<Offer> {
    val list = peers()
    if (list.isEmpty()) return emptyList()
    val pool = Executors.newFixedThreadPool(minOf(list.size, MAX_PARALLEL_PROBES))
    return try {
      list.map { peer -> pool.submit<Offer?> {
        (askWithAnnounce(peer, client::probe) as? HaShareAnswer.Connected)?.let { Offer(peer, it.name) }
      } }.mapNotNull { runCatching { it.get(OFFERS_BUDGET_MS, TimeUnit.MILLISECONDS) }.getOrNull() }
    } finally {
      pool.shutdownNow()
    }
  }

  /** Resolves [from] (a device id, or a Portal name case-insensitively) and adopts its connection. Blocking. */
  fun adopt(from: String, replace: Boolean): Outcome {
    val peer = when (val found = resolve(peers(), from)) {
      is Resolution.Found -> found.peer
      Resolution.None -> return Outcome(Result.NO_PEER)
      Resolution.Many -> return Outcome(Result.AMBIGUOUS)
    }
    val shared = when (val answer = askWithAnnounce(peer, client::share)) {
      is HaShareAnswer.Shared -> answer
      else -> return Outcome(failure(answer), peer.name).also { log("adopt from ${peer.id} failed: ${it.result.wire}") }
    }
    val existing = current()
    val unchanged = existing != null && HaShareWire.same(existing, shared.connection)
    if (existing != null && !unchanged && !replace) return Outcome(Result.ALREADY_CONNECTED, shared.name)
    if (!validate(shared.connection)) {
      log("adopt from ${peer.id}: Home Assistant refused the connection; nothing saved")
      return Outcome(Result.VALIDATION_FAILED, shared.name)
    }
    if (unchanged) return Outcome(Result.UNCHANGED, shared.name, validated = true)
    save(shared.connection)
    log("adopted the Home Assistant connection from ${peer.id}")
    return Outcome(Result.ADOPTED, shared.name, validated = true)
  }

  /** One retry after re-announcing if the source didn't know where we are yet. */
  private fun askWithAnnounce(peer: HaSharePeer, ask: (HaSharePeer) -> HaShareAnswer): HaShareAnswer {
    val first = ask(peer)
    if (first !is HaShareAnswer.Refused || first.code != UNKNOWN_PEER) return first
    announce()
    settle()
    return ask(peer)
  }

  private fun failure(answer: HaShareAnswer): Result = when (answer) {
    is HaShareAnswer.Refused -> when {
      answer.code == "not_connected" -> Result.PEER_NOT_CONNECTED
      answer.status == HTTP_TOO_MANY -> Result.RATE_LIMITED
      else -> Result.REFUSED
    }
    HaShareAnswer.Unreachable -> Result.UNREACHABLE
    else -> Result.INVALID_REPLY
  }

  sealed interface Resolution {
    data class Found(val peer: HaSharePeer) : Resolution
    object None : Resolution
    object Many : Resolution
  }

  companion object {
    const val UNKNOWN_PEER = "unknown_peer"
    const val MAX_PARALLEL_PROBES = 4
    /** Two probe round trips (3 s connect + 5 s read each) plus the hello settle, with margin. */
    const val OFFERS_BUDGET_MS = 20_000L
    private const val HTTP_TOO_MANY = 429

    fun resolve(peers: List<HaSharePeer>, from: String): Resolution {
      val wanted = from.trim()
      if (wanted.isEmpty()) return Resolution.None
      peers.firstOrNull { it.id == wanted }?.let { return Resolution.Found(it) }
      val named = peers.filter { it.name.trim().lowercase(Locale.ROOT) == wanted.lowercase(Locale.ROOT) }
      return when (named.size) {
        0 -> Resolution.None
        1 -> Resolution.Found(named.single())
        else -> Resolution.Many
      }
    }

    /** The household Portals that announced a LAN address on an unprivileged port, minus ourselves. */
    fun candidates(peers: List<CallPeer>, selfId: String?): List<HaSharePeer> = peers.mapNotNull { p ->
      val host = p.host?.takeIf(HouseholdListPeerAuth::isPrivateLan) ?: return@mapNotNull null
      if (p.id == selfId || CallPhoneId.isPhone(p.id) || p.port < SettingsSyncWire.MIN_PEER_PORT) return@mapNotNull null
      HaSharePeer(p.id, p.name, host, p.port)
    }

    /** True only for HA's own `/api/` answer: `{"message": "API running."}`. */
    fun isApiRunning(body: String?): Boolean =
        body != null && runCatching { org.json.JSONObject(body).optString("message") }.getOrNull()
            ?.contains("API running", ignoreCase = true) == true
  }
}
