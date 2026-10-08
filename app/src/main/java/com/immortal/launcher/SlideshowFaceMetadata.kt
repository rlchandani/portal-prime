/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.security.MessageDigest

/** Detector-independent metadata. Supplied asset keys must already include source revisions. */
internal object SlideshowFaceMetadata {
  const val SCHEMA = 1
  const val MODEL = "mlkit-bundled-16.1.7-accurate-boxes"
  const val POLICY = "upright-640-raw-boxes-v2"
  const val MAX_EDGE = 640
  const val MAX_REGIONS = 128
  const val DAY_MS = 86_400_000L

  enum class Status { FACES, NO_FACE, FAILURE, PENDING }

  data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
  data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)
  data class Entry(
      val revisionKey: String,
      val status: Status,
      val analyzedAt: Long,
      val width: Int,
      val height: Int,
      val regions: List<Bounds> = emptyList(),
      val schema: Int = SCHEMA,
      val model: String = MODEL,
      val policy: String = POLICY,
  )

  // Hash the ENTIRE supplied key. Do not normalize URLs or remove authentication queries.
  // Persisting only this digest also keeps source URLs/credentials out of the cache.
  fun key(assetKey: String): String =
      MessageDigest.getInstance("SHA-256").digest(assetKey.toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it.toInt() and 255) }

  fun targetSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val factor = minOf(1.0, MAX_EDGE.toDouble() / maxOf(width, height))
    return maxOf(1, (width * factor).toInt()) to maxOf(1, (height * factor).toInt())
  }

  /** Geometry.plan alone owns padding. Cache raw boxes so composition policy cannot double-pad. */
  fun normalized(box: Box, width: Int, height: Int): Bounds? {
    if (width <= 0 || height <= 0 || box.left >= box.right || box.top >= box.bottom) return null
    val left = box.left.coerceIn(0, width).toDouble()
    val top = box.top.coerceIn(0, height).toDouble()
    val right = box.right.coerceIn(0, width).toDouble()
    val bottom = box.bottom.coerceIn(0, height).toDouble()
    if (left >= right || top >= bottom) return null
    return Bounds(
        (left / width).toFloat(), (top / height).toFloat(),
        (right / width).toFloat(), (bottom / height).toFloat())
  }

  fun fresh(entry: Entry, revisionKey: String, now: Long): Boolean {
    if (!valid(entry) || entry.revisionKey != revisionKey || now < entry.analyzedAt) return false
    val ttl = when (entry.status) {
      Status.FACES -> 30 * DAY_MS
      Status.NO_FACE -> 7 * DAY_MS
      Status.FAILURE -> 600_000L
      Status.PENDING -> 120_000L
    }
    return now - entry.analyzedAt < ttl
  }

  fun valid(entry: Entry): Boolean =
      entry.schema == SCHEMA && entry.model == MODEL && entry.policy == POLICY &&
          entry.revisionKey.matches(Regex("[a-f0-9]{64}")) && entry.analyzedAt >= 0 &&
          entry.width in 1..MAX_EDGE && entry.height in 1..MAX_EDGE &&
          entry.regions.size <= MAX_REGIONS &&
          (if (entry.status == Status.FACES) entry.regions.isNotEmpty() else entry.regions.isEmpty()) &&
          entry.regions.all {
            it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
                it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f &&
                it.left < it.right && it.top < it.bottom
          }
}

/** Monotonic process-wide admission: an overdue SDK call never permits a replacement worker. */
internal class SlideshowFaceRunGate(private val timeoutNanos: Long) {
  init { require(timeoutNanos > 0) }
  private var startedAt: Long? = null
  var disabled = false
    private set

  fun start(now: Long): Boolean {
    if (!canAdmit(now) || startedAt != null) return false
    startedAt = now
    return true
  }
  fun canAdmit(now: Long): Boolean {
    startedAt?.let { if (now - it >= timeoutNanos) disabled = true }
    return !disabled
  }
  fun disable() { disabled = true }
  fun complete() { startedAt = null }
}

/** A timed-out ML Task still owns its pixels. Only actual completion relinquishes the lease. */
internal class SlideshowFaceLease<T>(val value: T, private val release: (T) -> Unit) {
  private var released = false
  @Synchronized fun finish(taskComplete: Boolean): Boolean {
    if (!taskComplete) return false
    if (!released) {
      released = true
      release(value)
    }
    return true
  }
}
