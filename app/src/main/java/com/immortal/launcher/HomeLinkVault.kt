/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * This household's identity key as stored and shared between its own Portals: the public half,
 * the PKCS#8 private half and when it was made (the tie-break if two Portals ever made one each).
 * Holds a private key: [toString] never prints it.
 */
internal class HomeLinkStoredIdentity private constructor(val publicKey: String, val privateKey: String, val createdAt: Long,
                                                          /** Bumped each time the identity is rotated (a Portal was removed). */
                                                          val generation: Int) {
  /** The identity's fingerprint: what a linked home's [HomeLink.verifiedAs] records. */
  val kid: String get() = HomeLinkIdentity.fingerprint(publicKey)

  fun restore(): HomeLinkIdentity? = HomeLinkIdentity.restore(publicKey, privateKey)

  override fun equals(other: Any?): Boolean =
      other is HomeLinkStoredIdentity && other.publicKey == publicKey && other.privateKey == privateKey && other.createdAt == createdAt &&
          other.generation == generation

  override fun hashCode(): Int = publicKey.hashCode()

  override fun toString(): String = "HomeLinkStoredIdentity($kid, g$generation)"

  companion object {
    fun of(identity: HomeLinkIdentity, createdAt: Long, generation: Int = 0): HomeLinkStoredIdentity =
        HomeLinkStoredIdentity(identity.publicKey, identity.encodePrivate(), createdAt, generation)

    /** Only a pair that restores (the private key really belongs to the public one). */
    fun parse(publicKey: String, privateKey: String, createdAt: Long, generation: Int = 0): HomeLinkStoredIdentity? =
        HomeLinkStoredIdentity(publicKey, privateKey, createdAt, generation)
            .takeIf { createdAt >= 0 && generation >= 0 && it.restore() != null }

    /** Every Portal keeps the same one: the newest generation (a rotation wins), then the older, then the smaller public key. */
    fun pick(a: HomeLinkStoredIdentity?, b: HomeLinkStoredIdentity?): HomeLinkStoredIdentity? = when {
      a == null -> b
      b == null -> a
      a.generation != b.generation -> if (a.generation > b.generation) a else b
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
 * the identity, one entry per linked home, and one signed marker per Portal removed from the
 * home ([removals], [PortalRemoval]). Each edit bumps the revision; [merge] keeps the higher
 * revision per home (a removal wins a tie), so a removal reaches every Portal and a removed home
 * can't come back from a Portal that was off. A tombstone keeps only the id: the removed home's
 * key is gone everywhere. Removing a Portal rotates the identity ([rotated]); links verified
 * under an older identity are [reverifyIds]. Immutable.
 */
internal data class HomeLinkVault(val identity: HomeLinkStoredIdentity? = null, val entries: Map<String, HomeLinkEntry> = emptyMap(),
                                  val removals: Map<String, PortalRemoval> = emptyMap()) {
  val book: HomeLinkBook
    get() = HomeLinkBook(entries.values.mapNotNull { it.link }.sortedWith(compareBy({ it.verifiedAt }, { it.id })))

  /** Highest revision seen (linked homes and removed Portals): the next edit goes above it. */
  val clock: Long get() = maxOf(entries.values.maxOfOrNull { it.rev } ?: 0L, removals.values.maxOfOrNull { it.rev } ?: 0L)

  /**
   * Linked homes that verified this home under an identity it no longer has (rotated after a Portal
   * was removed): they must check the pictures again before calls between the homes verify. A link
   * from before [HomeLink.verifiedAs] existed counts as verified under generation 0.
   */
  val reverifyIds: Set<String>
    get() {
      val id = identity ?: return emptySet()
      return book.links.filter { if (it.verifiedAs.isEmpty()) id.generation > 0 else it.verifiedAs != id.kid }.map { it.id }.toSet()
    }

  /**
   * True when a removal newer than the identity is known (two Portals removed at once, and the
   * identity that won was made before one of them): the removed Portal may hold it, so rotate
   * again. Markers dated beyond [now] + [MAX_SKEW_MS] are ignored so a bad clock can't force a
   * rotation on every merge.
   */
  fun identityStale(now: Long): Boolean {
    val id = identity ?: return false
    return removals.values.any { it.at > id.createdAt && it.at <= now + MAX_SKEW_MS }
  }

  /**
   * When a rotation at [now] should say it was made: no earlier than any removal counted by
   * [identityStale], so a Portal whose clock is behind the remover's doesn't rotate again on every merge.
   */
  fun rotationTime(now: Long): Long = maxOf(now, removals.values.filter { it.at <= now + MAX_SKEW_MS }.maxOfOrNull { it.at } ?: now)

  /** Records a Portal's removal marker (sync carries it to every pinned Portal). */
  fun withRemoval(r: PortalRemoval): HomeLinkVault = copy(removals = PortalRemovals.merge(removals, mapOf(r.kid to r)))

  /** Replaces the identity with a newer generation (rotation): the old private key is dropped from this vault. */
  fun rotated(next: HomeLinkStoredIdentity): HomeLinkVault = copy(identity = next)

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
    return HomeLinkVault(HomeLinkStoredIdentity.pick(identity, other.identity), capLinks(merged),
        PortalRemovals.merge(removals, other.removals)).pruned()
  }

  private fun withEntry(e: HomeLinkEntry): HomeLinkVault = copy(entries = entries + (e.id to e)).pruned()

  /** Keeps the newest [MAX_TOMBSTONES] tombstones (old ones have reached every Portal by then). */
  private fun pruned(): HomeLinkVault {
    val stones = entries.values.filter { it.removed }
    if (stones.size <= MAX_TOMBSTONES) return this
    val drop = stones.sortedByDescending { it.rev }.drop(MAX_TOMBSTONES).map { it.id }.toSet()
    return copy(entries = entries - drop)
  }

  override fun toString(): String =
      "HomeLinkVault(identity=$identity, links=${book.links.size}, removed=${entries.size - book.links.size}, portalsRemoved=${removals.size})"

  companion object {
    const val MAX_TOMBSTONES = 64
    /** How far ahead of our clock a removal marker may be dated and still count for [identityStale]. */
    const val MAX_SKEW_MS = 24 * 3_600_000L

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
