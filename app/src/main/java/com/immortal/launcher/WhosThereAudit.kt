/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.TimeZone

/**
 * The household audit: times and counts only. No names, no scores, no images, nothing that
 * could be used to reconstruct who a visitor was. Kept [KEEP_MS] (30 days), capped at [CAP].
 */
internal enum class AuditKind(val code: String, val label: String) {
  GLANCE_PERSONAL("p", "Showed a personal Home"),
  GLANCE_FAMILY("f", "Showed the Family view"),
  GLANCE_UNSURE("u", "Not sure, asked"),
  GLANCE_SHARED("s", "Nobody known, shared Home"),
  GLANCE_NOBODY("n", "Looked, nobody in view"),
  NOT_YOU("x", "“Not you?” correction"),
  ENROLLED("e", "Person added"),
  DELETED("d", "Person’s face data deleted"),
  PAUSED("z", "Recognition paused");

  companion object {
    fun of(code: String): AuditKind? = entries.firstOrNull { it.code == code }
  }
}

internal data class AuditEvent(val atMs: Long, val kind: AuditKind, val faces: Int = 0)

/** A day's totals for the privacy centre. */
internal data class AuditDay(val day: Long, val glances: Int, val personal: Int, val corrections: Int)

internal object WhosThereAudit {
  const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
  const val CAP = 3000
  private const val DAY_MS = 24L * 60 * 60 * 1000

  fun add(log: List<AuditEvent>, event: AuditEvent, nowMs: Long): List<AuditEvent> = prune(log + event, nowMs)

  fun prune(log: List<AuditEvent>, nowMs: Long): List<AuditEvent> =
      log.filter { nowMs - it.atMs in 0..KEEP_MS }.sortedBy { it.atMs }.takeLast(CAP)

  fun kindFor(view: WhosThereView, outcome: GlanceOutcome): AuditKind = when {
    outcome.nobody -> AuditKind.GLANCE_NOBODY
    view is WhosThereView.Family -> AuditKind.GLANCE_FAMILY
    view is WhosThereView.Personal && view.confirm -> AuditKind.GLANCE_UNSURE
    view is WhosThereView.Personal -> AuditKind.GLANCE_PERSONAL
    outcome.unsure != null -> AuditKind.GLANCE_UNSURE
    else -> AuditKind.GLANCE_SHARED
  }

  /** Newest day first. */
  fun days(log: List<AuditEvent>, zone: TimeZone): List<AuditDay> =
      log.groupBy { localDay(it.atMs, zone) }.map { (day, events) ->
        AuditDay(day,
            glances = events.count { it.kind.code in GLANCE_CODES },
            personal = events.count { it.kind == AuditKind.GLANCE_PERSONAL || it.kind == AuditKind.GLANCE_FAMILY },
            corrections = events.count { it.kind == AuditKind.NOT_YOU })
      }.sortedByDescending { it.day }

  fun localDay(ms: Long, zone: TimeZone): Long = Math.floorDiv(ms + zone.getOffset(ms), DAY_MS)

  private val GLANCE_CODES = setOf("p", "f", "u", "s", "n")

  /** One line per event: `time,code,faces`. Malformed lines are skipped, not fatal. */
  fun encode(log: List<AuditEvent>): String = log.joinToString("\n") { "${it.atMs},${it.kind.code},${it.faces}" }

  fun decode(text: String): List<AuditEvent> = text.lineSequence().mapNotNull { line ->
    val parts = line.trim().split(',')
    if (parts.size != 3) return@mapNotNull null
    val at = parts[0].toLongOrNull() ?: return@mapNotNull null
    val kind = AuditKind.of(parts[1]) ?: return@mapNotNull null
    val faces = parts[2].toIntOrNull()?.coerceIn(0, 16) ?: return@mapNotNull null
    AuditEvent(at, kind, faces)
  }.toList()
}
