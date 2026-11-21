/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What a linked home may do here (mock `permsScr` / Households detail). There is deliberately no
 * drop-in permission: drop-ins never cross homes. Auto-answer is not here either: it can only be
 * turned on at the answering Portal ([CallAutoAnswerStore]).
 */
@Immutable
internal data class HomeLinkPermissions(
    /** "Call Maple House": rings [ringRooms]. */
    val canCall: Boolean,
    /** "See when we're available": Available / In a call / Quiet hours, never who's in which room. */
    val seesAvailability: Boolean,
    /** Rooms that ring when this home calls. */
    val ringRooms: Set<String>,
) {
  fun rings(room: String): Boolean = canCall && room in ringRooms

  companion object {
    /** Calls and availability on; every room rings except [quiet] ones (Kids' room and Office in the mock). */
    fun defaults(rooms: List<String>, quiet: Set<String>): HomeLinkPermissions =
        HomeLinkPermissions(canCall = true, seesAvailability = true, ringRooms = rooms.filterNot { it in quiet }.toSet())
  }
}

/**
 * A home linked after both sides said the safety pictures match. [id] is its key's fingerprint.
 * [verifiedAs] is the fingerprint of *our* identity they checked (empty for links made before it
 * was recorded): once this home's identity rotates, the link needs checking again
 * ([HomeLinkVault.reverifyIds]).
 */
@Immutable
internal data class HomeLink(val id: String, val name: String, val publicKey: String, val verifiedAt: Long,
                             val permissions: HomeLinkPermissions, val verifiedAs: String = "") {
  companion object {
    fun verified(peer: HomeLinkPeer, at: Long, permissions: HomeLinkPermissions, verifiedAs: String = ""): HomeLink =
        HomeLink(HomeLinkIdentity.fingerprint(peer.publicKey), peer.name, peer.publicKey, at, permissions, verifiedAs)
  }
}

/** Every home linked to this one. Immutable: each change returns a new book. */
@Immutable
internal data class HomeLinkBook(val links: List<HomeLink> = emptyList()) {
  val canAdd: Boolean get() = links.size < MAX_LINKS

  /** Adds [link], or replaces the one with its id in place. At the cap a new home is refused (same book). */
  fun upsert(link: HomeLink): HomeLinkBook {
    val at = links.indexOfFirst { it.id == link.id }
    if (at >= 0) return copy(links = links.toMutableList().also { it[at] = link })
    return if (canAdd) copy(links = links + link) else this
  }

  /** Removing a home also means forgetting its key (the caller wipes it from every Portal here). */
  fun remove(id: String): HomeLinkBook = if (links.none { it.id == id }) this else copy(links = links.filterNot { it.id == id })

  fun byKey(publicKey: String): HomeLink? = links.firstOrNull { it.publicKey == publicKey }

  companion object { const val MAX_LINKS = 12 }
}

/** Strict codec for the on-device book: stored data is validated like wire data. */
internal object HomeLinkBookCodec {
  private const val VERSION = 1

  fun encode(book: HomeLinkBook): String =
      JSONObject().put("v", VERSION).put("links", JSONArray().apply { for (l in book.links) put(linkJson(l)) }).toString()

  /** One link as stored (also inside [HomeLinkVaultCodec]). */
  fun linkJson(l: HomeLink): JSONObject = JSONObject().put("id", l.id).put("n", l.name).put("k", l.publicKey).put("at", l.verifiedAt)
      .put("call", l.permissions.canCall).put("avail", l.permissions.seesAvailability)
      .put("rooms", JSONArray(l.permissions.ringRooms.sorted())).apply { if (l.verifiedAs.isNotEmpty()) put("as", l.verifiedAs) }

  /** One stored link, validated like wire data, or null. */
  fun parseLink(o: JSONObject): HomeLink? = try { entry(o) } catch (e: JSONException) { null }

  /** Null for anything corrupt, so the caller can tell "no homes" from "unreadable". */
  fun parse(text: String): HomeLinkBook? = try {
    val o = JSONObject(text)
    if (o.optInt("v", -1) != VERSION) null else entries(o.getJSONArray("links"))
  } catch (e: JSONException) { null }

  private fun entries(arr: JSONArray): HomeLinkBook? {
    if (arr.length() > HomeLinkBook.MAX_LINKS) return null
    val links = (0 until arr.length()).map { entry(arr.getJSONObject(it)) ?: return null }
    return if (links.map { it.id }.toSet().size == links.size) HomeLinkBook(links) else null
  }

  private fun entry(o: JSONObject): HomeLink? {
    val key = o.getString("k").takeIf { HomeLinkIdentity.decodePublic(it) != null } ?: return null
    val id = o.getString("id").takeIf { it == HomeLinkIdentity.fingerprint(key) } ?: return null
    val name = o.getString("n").takeIf { HomeLinkWire.validName(it) } ?: return null
    val at = o.getLong("at").takeIf { it >= 0 } ?: return null
    val roomsArr = o.getJSONArray("rooms")
    val rooms = (0 until roomsArr.length()).map { roomsArr.getString(it).takeIf(HomeLinkWire::validName) ?: return null }
    val verifiedAs = if (o.has("as")) o.getString("as").takeIf { FINGERPRINT.matches(it) } ?: return null else ""
    return HomeLink(id, name, key, at, HomeLinkPermissions(o.getBoolean("call"), o.getBoolean("avail"), rooms.toSet()), verifiedAs)
  }

  private val FINGERPRINT = Regex("^[0-9a-f]{16}$")
}
