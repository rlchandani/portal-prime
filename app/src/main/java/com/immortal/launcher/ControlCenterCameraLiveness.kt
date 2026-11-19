/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import java.util.concurrent.ConcurrentHashMap

/** How a camera picture is arriving right now. */
internal enum class CcCamSource { HLS, MJPEG, SNAPSHOT }

/**
 * What we have seen from one camera (pure, unit tested). Liveness is judged on the pictures
 * themselves, never on the clock: a frame that is byte-for-byte the last one is not news.
 *
 * - [imageSince]: when this exact picture was first seen (survives leaving the screen, see [CcCamMemory]).
 * - [changes]: the last two moments the picture actually changed while we watched.
 * - [captured]: when the camera took the picture, if HA says (ring-mqtt `timestamp`, `Last-Modified`).
 */
@Immutable
internal data class CcCamWatch(
    val source: CcCamSource = CcCamSource.SNAPSHOT,
    val hash: Long = 0L,
    val imageSince: Long = 0L,
    val lastFrameAt: Long = 0L,
    val lastChange: Long = 0L,
    val previousChange: Long = 0L,
    val captured: Long? = null,
) {
  val hasFrame: Boolean get() = lastFrameAt > 0

  /** A picture arrived (decoded or not); [hash] identifies its bytes. */
  fun frame(hash: Long, now: Long, captured: Long?, source: CcCamSource = this.source): CcCamWatch {
    val first = !hasFrame
    val changed = !first && hash != this.hash
    return copy(
        source = source,
        hash = hash,
        // A remembered picture (same bytes as on an earlier visit) keeps its first-seen time.
        imageSince = if (changed || first && (hash != this.hash || imageSince == 0L)) now else imageSince,
        lastFrameAt = now,
        lastChange = if (changed) now else lastChange,
        previousChange = if (changed) lastChange else previousChange,
        captured = captured ?: this.captured.takeIf { !changed },
    )
  }

  /** A video stream rendered new frames (playback moved on) at [now]. */
  fun moving(now: Long, source: CcCamSource): CcCamWatch =
      copy(source = source, lastFrameAt = now, previousChange = if (lastChange > 0) lastChange else now, lastChange = now,
          imageSince = now, captured = null)

  /** Seed from an earlier visit: the same picture keeps its first-seen time. */
  fun remembered(seen: CcCamMemory.Seen?): CcCamWatch = if (seen == null) this else copy(hash = seen.hash, imageSince = seen.since)
}

/** The tag in the corner and the stamp on the picture. [stampMs] null = no trustworthy time to show. */
@Immutable
internal data class CcCamLabel(val live: Boolean, val text: String, val stampMs: Long?, val captureKnown: Boolean)

internal object CcCamLiveness {
  /** "Live" needs the picture to have changed within this long — twice, so a 30 s snapshot interval isn't video. */
  const val LIVE_WINDOW_MS = 15_000L
  private const val S = 1000L
  private const val MIN = 60 * S
  private const val HOUR = 60 * MIN
  private const val DAY = 24 * HOUR
  private const val JUST_NOW_MS = 10 * S

  fun isLive(w: CcCamWatch, now: Long): Boolean =
      w.hasFrame && w.lastChange > 0 && w.previousChange > 0 &&
          now - w.lastChange <= LIVE_WINDOW_MS && w.lastChange - w.previousChange <= LIVE_WINDOW_MS

  fun label(w: CcCamWatch, now: Long): CcCamLabel {
    if (isLive(w, now)) return CcCamLabel(true, "LIVE", w.lastFrameAt, captureKnown = false)
    val captured = w.captured
    if (captured != null && captured > 0) return CcCamLabel(false, "Snapshot · ${ago(now - captured)}", captured, captureKnown = true)
    if (w.lastChange > 0) return CcCamLabel(false, "Snapshot · ${ago(now - w.lastChange)}", w.lastChange, captureKnown = false)
    val held = now - w.imageSince
    return if (held < LIVE_WINDOW_MS) CcCamLabel(false, "Snapshot · checking", null, false)
    else CcCamLabel(false, "Snapshot · unchanged ${duration(held)}", null, false)
  }

  /** What a screen reader says (and tests read): minute-level, so it doesn't change every second. */
  fun speech(w: CcCamWatch, now: Long): String {
    val l = label(w, now)
    return if (l.live) "live" else l.text.replace(SECONDS, "under a minute").replace(" · ", ", ").lowercase()
  }

  private val SECONDS = Regex("""\b\d+ s\b""")

  /** "just now", "40 s ago", "12 min ago", "3 h ago", "11 d ago". */
  fun ago(ms: Long): String = if (ms < JUST_NOW_MS) "just now" else "${duration(ms)} ago"

  fun duration(ms: Long): String {
    val v = ms.coerceAtLeast(0)
    return when {
      v < MIN -> "${v / S} s"
      v < HOUR -> "${v / MIN} min"
      v < DAY -> "${v / HOUR} h"
      else -> "${v / DAY} d"
    }
  }

  /** When the camera took its picture, if HA tells us: ring-mqtt's `timestamp` (epoch seconds) or similar. */
  fun capturedAt(attrs: Map<String, Any?>): Long? {
    for (key in CAPTURE_KEYS) {
      val v = attrs[key] ?: continue
      val n = (v as? Number)?.toLong() ?: (v as? String)?.toLongOrNull() ?: continue
      if (n <= 0) continue
      return if (n < EPOCH_MS_FLOOR) n * S else n
    }
    return null
  }

  private val CAPTURE_KEYS = listOf("timestamp", "last_snapshot", "snapshot_time")
  /** Anything below this is epoch seconds (it is 2001 in milliseconds). */
  private const val EPOCH_MS_FLOOR = 1_000_000_000_000L

  /** 64-bit FNV-1a of a JPEG: equal bytes, equal picture. */
  fun hash(bytes: ByteArray, length: Int = bytes.size): Long {
    var h = FNV_OFFSET
    for (i in 0 until length) { h = h xor (bytes[i].toLong() and BYTE); h *= FNV_PRIME }
    return h
  }

  private const val FNV_OFFSET = -0x340d631b7bdddcdbL
  private const val FNV_PRIME = 0x100000001b3L
  private const val BYTE = 0xFFL

  /** What the full view says about the camera, in plain words. */
  fun sourceLine(source: CcCamSource?, live: Boolean, canStream: Boolean, platform: String?): String = when {
    live && source == CcCamSource.HLS -> "Live video from Home Assistant"
    live && source == CcCamSource.MJPEG -> "Live picture stream from Home Assistant"
    live -> "Pictures are updating every few seconds"
    canStream -> "Home Assistant can stream this camera · connecting"
    else -> "${platformName(platform)?.let { "$it camera · " } ?: ""}Home Assistant only has still pictures for this camera"
  }

  /** The longer explanation under it when live video isn't possible. */
  fun whyNotLive(platform: String?): String = when (platform) {
    "mqtt" -> "This is a ring-mqtt snapshot camera: it changes only on motion or on its snapshot interval. For live " +
        "video, Home Assistant needs the camera's ring-mqtt RTSP stream added as a camera; Hearthpane then plays it live."
    "wyze_ha" -> "The Wyze integration gives Home Assistant the last event picture only. Live video needs a stream " +
        "(for example docker-wyze-bridge) added to Home Assistant."
    else -> "Home Assistant doesn't offer a stream for this camera, so the picture is the latest still it has."
  }

  fun platformName(platform: String?): String? = when (platform) {
    "mqtt" -> "Ring (MQTT)"
    "wyze_ha" -> "Wyze"
    "ring" -> "Ring"
    "bambu_lab" -> "Bambu printer"
    null, "" -> null
    else -> platform.replace('_', ' ').replaceFirstChar { it.uppercase() }
  }
}

/**
 * Process memory of the last picture per camera, so "unchanged 3 h" survives leaving the screen
 * (only a hash and a time — never the picture).
 */
internal object CcCamMemory {
  data class Seen(val hash: Long, val since: Long)

  private val seen = ConcurrentHashMap<String, Seen>()
  /** Cameras whose MJPEG stream gave nothing, and when; we don't retry them for [MJPEG_RETRY_MS]. */
  private val mjpegFailed = ConcurrentHashMap<String, Long>()
  const val MJPEG_RETRY_MS = 10 * 60_000L

  fun get(cameraId: String): Seen? = seen[cameraId]

  fun put(cameraId: String, w: CcCamWatch) { if (w.hasFrame) seen[cameraId] = Seen(w.hash, w.imageSince) }

  fun mjpegFailed(cameraId: String, now: Long) { mjpegFailed[cameraId] = now }

  fun mjpegWorthTrying(cameraId: String, now: Long): Boolean = mjpegFailed[cameraId]?.let { now - it > MJPEG_RETRY_MS } ?: true
}

/** What a camera tile needs from the HA state; equal (and skipped) until one of these changes. */
@Immutable
internal data class CcCamInfo(
    val canStream: Boolean = false,
    val captured: Long? = null,
    val platform: String? = null,
    val refreshButton: String? = null,
    val unavailable: Boolean = false,
) {
  /** Ask for a newer picture: ring-mqtt's "take snapshot" button, else `homeassistant.update_entity`. */
  fun refreshCall(cameraId: String): CcCall = refreshButton?.let { CcCall("button", "press", it, label = "Asked the camera for a new picture") }
      ?: CcCall("homeassistant", "update_entity", cameraId, label = "Asked Home Assistant for a new picture")

  companion object {
    fun of(s: CcUiState, cameraId: String): CcCamInfo {
      val cam = s.entity(cameraId)
      val reg = s.registry
      val button = reg?.snapshotButtons?.get(cameraId)?.firstOrNull { id -> s.entity(id)?.let { it.state != "unavailable" } == true }
      return CcCamInfo(cam?.let(CcCameraLogic::canStream) == true, cam?.let { CcCamLiveness.capturedAt(it.attrs) },
          reg?.cameraPlatform?.get(cameraId), button, cam?.unavailable == true)
    }
  }
}
