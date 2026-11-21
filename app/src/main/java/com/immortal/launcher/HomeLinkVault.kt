/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * This household's identity key as stored and shared between its own Portals: the public half,
 * the PKCS#8 private half and when it was made (the tie-break if two Portals ever made one each).
 * Holds a private key: [toString] never prints it.
 */
internal class HomeLinkStoredIdentity private constructor(val publicKey: String, val privateKey: String, val createdAt: Long) {
  fun restore(): HomeLinkIdentity? = HomeLinkIdentity.restore(publicKey, privateKey)

  override fun equals(other: Any?): Boolean =
      other is HomeLinkStoredIdentity && other.publicKey == publicKey && other.privateKey == privateKey && other.createdAt == createdAt

  override fun hashCode(): Int = publicKey.hashCode()

  override fun toString(): String = "HomeLinkStoredIdentity(${HomeLinkIdentity.fingerprint(publicKey)})"

  companion object {
    fun of(identity: HomeLinkIdentity, createdAt: Long): HomeLinkStoredIdentity =
        HomeLinkStoredIdentity(identity.publicKey, identity.encodePrivate(), createdAt)

    /** Only a pair that restores (the private key really belongs to the public one). */
    fun parse(publicKey: String, privateKey: String, createdAt: Long): HomeLinkStoredIdentity? =
        HomeLinkStoredIdentity(publicKey, privateKey, createdAt).takeIf { createdAt >= 0 && it.restore() != null }

    /** Both Portals keep the same one: the older, then the smaller public key. */
    fun pick(a: HomeLinkStoredIdentity?, b: HomeLinkStoredIdentity?): HomeLinkStoredIdentity? = when {
      a == null -> b
      b == null -> a
      a.createdAt != b.createdAt -> if (a.createdAt < b.createdAt) a else b
      else -> if (a.publicKey <= b.publicKey) a else b
    }
  }
}

/** One linked home's slot at revision [rev]: the link, or a tombstone ([link] null) once removed. */
internal data class HomeLinkEntry(val id: String, val rev: Long, val link: HomeLink?) {
  val removed: Boolean get() = link == null
}

/**
 * Everything about linking homes that one Portal keeps, and every Portal in the home shares:
 * the identity and one entry per linked home. Each edit bumps the entry's revision; [merge] keeps
 * the higher revision per home (a removal wins a tie), so a removal reaches every Portal and a
 * removed home can't come back from a Portal that was off. A tombstone keeps only the id: the
 * removed home's key is gone everywhere. Immutable.
 */
internal data class HomeLinkVault(val identity: HomeLinkStoredIdentity? = null, val entries: Map<String, HomeLinkEntry> = emptyMap()) {
  val book: HomeLinkBook
    get() = HomeLinkBook(entries.values.mapNotNull { it.link }.sortedWith(compareBy({ it.verifiedAt }, { it.id })))

  /** Highest revision seen: the next edit goes above it. */
  val clock: Long get() = entries.values.maxOfOrNull { it.rev } ?: 0L

  fun withIdentity(stored: HomeLinkStoredIdentity): HomeLinkVault = copy(identity = HomeLinkStoredIdentity.pick(identity, stored))

  /** Adds or edits a linked home. Refused (same vault) at [HomeLinkBook.MAX_LINKS] for a new home. */
  fun put(link: HomeLink): HomeLinkVault {
    val existing = entries[link.id]?.link
    if (existing == link) return this
    if (existing == null && !book.canAdd) return this
    return withEntry(HomeLinkEntry(link.id, clock + 1, link))
  }

  /** Forgets a linked home: its key is dropped, a tombstone stays so other Portals drop it too. */
  fun remove(id: String): HomeLinkVault {
    if (entries[id]?.link == null) return this
    return withEntry(HomeLinkEntry(id, clock + 1, null))
  }

  /** The union of two Portals' vaults (commutative, idempotent). */
  fun merge(other: HomeLinkVault): HomeLinkVault {
    val ids = entries.keys + other.entries.keys
    val merged = ids.associateWith { id -> newer(entries[id], other.entries[id]) }
    return HomeLinkVault(HomeLinkStoredIdentity.pick(identity, other.identity), capLinks(merged)).pruned()
  }

  private fun withEntry(e: HomeLinkEntry): HomeLinkVault = copy(entries = entries + (e.id to e)).pruned()

  /** Keeps the newest [MAX_TOMBSTONES] tombstones (old ones have reached every Portal by then). */
  private fun pruned(): HomeLinkVault {
    val stones = entries.values.filter { it.removed }
    if (stones.size <= MAX_TOMBSTONES) return this
    val drop = stones.sortedByDescending { it.rev }.drop(MAX_TOMBSTONES).map { it.id }.toSet()
    return copy(entries = entries - drop)
  }

  override fun toString(): String = "HomeLinkVault(identity=$identity, links=${book.links.size}, removed=${entries.size - book.links.size})"

  companion object {
    const val MAX_TOMBSTONES = 64

    private fun newer(a: HomeLinkEntry?, b: HomeLinkEntry?): HomeLinkEntry = when {
      a == null -> b!!
      b == null -> a
      a.rev != b.rev -> if (a.rev > b.rev) a else b
      a.removed -> a
      b.removed -> b
      else -> if (HomeLinkBookCodec.linkJson(a.link!!).toString() <= HomeLinkBookCodec.linkJson(b.link!!).toString()) a else b
    }

    /** Two Portals each adding homes could exceed the cap together: keep the oldest-verified ones. */
    private fun capLinks(entries: Map<String, HomeLinkEntry>): Map<String, HomeLinkEntry> {
      val live = entries.values.filter { !it.removed }
      if (live.size <= HomeLinkBook.MAX_LINKS) return entries
      val over = live.sortedWith(compareBy({ it.link!!.verifiedAt }, { it.id })).drop(HomeLinkBook.MAX_LINKS).map { it.id }.toSet()
      return entries - over
    }
  }
}

/** Strict codec for the stored and synced vault. Anything off parses to null. */
internal object HomeLinkVaultCodec {
  private const val VERSION = 1
  private const val MAX_ENTRIES = HomeLinkBook.MAX_LINKS + HomeLinkVault.MAX_TOMBSTONES

  fun encode(v: HomeLinkVault): String {
    val o = JSONObject().put("v", VERSION)
    v.identity?.let { o.put("id", JSONObject().put("pub", it.publicKey).put("priv", it.privateKey).put("at", it.createdAt)) }
    o.put("e", JSONArray().apply {
      for (e in v.entries.values.sortedBy { it.id }) {
        put(JSONObject().put("id", e.id).put("r", e.rev).apply { e.link?.let { put("l", HomeLinkBookCodec.linkJson(it)) } })
      }
    })
    return o.toString()
  }

  fun parse(text: String?): HomeLinkVault? {
    if (text.isNullOrEmpty()) return null
    return try {
      val o = JSONObject(text)
      if (o.opt("v") != VERSION) return null
      val identity = (o.opt("id") as? JSONObject)?.let { identity(it) ?: return null }
      val arr = o.getJSONArray("e")
      if (arr.length() > MAX_ENTRIES) return null
      val entries = (0 until arr.length()).map { entry(arr.getJSONObject(it)) ?: return null }
      if (entries.map { it.id }.toSet().size != entries.size) return null
      if (entries.count { !it.removed } > HomeLinkBook.MAX_LINKS) return null
      HomeLinkVault(identity, entries.associateBy { it.id })
    } catch (e: JSONException) { null }
  }

  private fun identity(o: JSONObject): HomeLinkStoredIdentity? {
    val pub = o.opt("pub") as? String ?: return null
    val priv = o.opt("priv") as? String ?: return null
    val at = (o.opt("at") as? Number)?.toLong() ?: return null
    return HomeLinkStoredIdentity.parse(pub, priv, at)
  }

  private fun entry(o: JSONObject): HomeLinkEntry? {
    val id = (o.opt("id") as? String)?.takeIf { ID.matches(it) } ?: return null
    val rev = (o.opt("r") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    val linkJson = o.opt("l") ?: return HomeLinkEntry(id, rev, null)
    val link = (linkJson as? JSONObject)?.let(HomeLinkBookCodec::parseLink)?.takeIf { it.id == id } ?: return null
    return HomeLinkEntry(id, rev, link)
  }

  private val ID = Regex("^[0-9a-f]{16}$")
}
