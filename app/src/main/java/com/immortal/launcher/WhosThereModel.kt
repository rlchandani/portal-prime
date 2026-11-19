/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.sqrt

/**
 * "Who's there?" household model. Pure and immutable: everything here is plain data the policies
 * and tests share. Face data is only ever an [Embedding] (numbers), never an image.
 */
internal enum class WhosThereRole(val label: String) {
  ADULT("Adult"),
  TEEN("Teen"),
  KID("Kid");

  /** Under-13s are parent-managed: no private items, enrolled by an adult on the Portal. */
  val parentManaged: Boolean get() = this == KID

  companion object {
    fun parse(raw: String?): WhosThereRole = entries.firstOrNull { it.name == raw } ?: ADULT
  }
}

/** One enrolled person. [calendarUrl] is an optional per-person ICS feed. */
internal data class WhosTherePerson(
    val id: String,
    val name: String,
    val role: WhosThereRole,
    val colorIndex: Int,
    val createdMs: Long,
    val pausedUntilMs: Long = 0L,
    val calendarUrl: String? = null,
) {
  val initial: String get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
  fun pausedAt(nowMs: Long): Boolean = pausedUntilMs > nowMs
  val color: WhosThereColor get() = WhosThereColors.at(colorIndex)
}

/**
 * A unit-length face embedding. The array is copied in and never exposed for writing, so an
 * [Embedding] is immutable even though it wraps a primitive array.
 */
internal class Embedding private constructor(private val values: FloatArray) {
  val size: Int get() = values.size
  operator fun get(i: Int): Float = values[i]
  fun toArray(): FloatArray = values.copyOf()

  /** Cosine similarity; both sides are already unit length, so this is the dot product. */
  fun cosine(other: Embedding): Float {
    require(other.size == size) { "embedding sizes differ" }
    var dot = 0f
    for (i in values.indices) dot += values[i] * other.values[i]
    return dot
  }

  override fun equals(other: Any?): Boolean = other is Embedding && other.values.contentEquals(values)
  override fun hashCode(): Int = values.contentHashCode()

  companion object {
    /** Returns null for an empty, non-finite or all-zero vector (a failed inference). */
    fun of(raw: FloatArray): Embedding? {
      if (raw.isEmpty() || raw.any { !it.isFinite() }) return null
      var norm = 0.0
      for (v in raw) norm += v * v
      if (norm <= 1e-12) return null
      val inv = (1.0 / sqrt(norm)).toFloat()
      return Embedding(FloatArray(raw.size) { raw[it] * inv })
    }
  }
}

/** The templates one person enrolled on this Portal (several looks). */
internal data class FaceTemplates(val personId: String, val looks: List<Embedding>)

/** A person colour: the avatar gradient pair and the Home tint, as ARGB. */
internal data class WhosThereColor(val name: String, val from: Long, val to: Long, val tint: Long)

/** The mock's person colours (Maya, Arjun, Dev, Leela, Asha) plus three more. */
internal object WhosThereColors {
  val all: List<WhosThereColor> = listOf(
      WhosThereColor("Ember", 0xFFF6B26B, 0xFFD9573A, 0xFFE8834A),
      WhosThereColor("Teal", 0xFF7FDCC4, 0xFF1F8A86, 0xFF2F9C95),
      WhosThereColor("Indigo", 0xFFBBA6FF, 0xFF5B47C9, 0xFF6B5BD6),
      WhosThereColor("Rose", 0xFFFFCF73, 0xFFF0628E, 0xFFF06292),
      WhosThereColor("Violet", 0xFFD8C4FF, 0xFF8B5CF6, 0xFF8B5CF6),
      WhosThereColor("Blue", 0xFF9FC5FF, 0xFF2F6FD6, 0xFF3F7FE0),
      WhosThereColor("Green", 0xFFA7E3A1, 0xFF2E8B57, 0xFF3A9A5E),
      WhosThereColor("Gold", 0xFFFFE08A, 0xFFD99A1A, 0xFFD9A21A),
  )

  fun at(index: Int): WhosThereColor = all[((index % all.size) + all.size) % all.size]

  /** The first colour nobody uses yet, so new people get a distinct tint by default. */
  fun firstFree(used: Collection<Int>): Int = all.indices.firstOrNull { it !in used } ?: (used.size % all.size)
}

/** Limits that keep the closed set small (accuracy) and the store bounded. */
internal object WhosThereLimits {
  const val MAX_PEOPLE = 8
  const val MAX_NAME = 20
  const val MAX_LOOKS = 12
  const val EMBEDDING_SIZE = 128

  fun cleanName(raw: String): String =
      raw.filterNot { it.isISOControl() }.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)

  fun validName(raw: String, others: Collection<String>): String? {
    val name = cleanName(raw)
    return when {
      name.isEmpty() -> "Add a name"
      others.any { it.equals(name, ignoreCase = true) } -> "Someone on this Portal already has that name"
      else -> null
    }
  }
}
