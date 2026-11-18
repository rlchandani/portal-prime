/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure camera rules for the Control Center (unit tested): snapshot URLs, decode sampling, which
 * cameras the grid shows, the burnt-in timestamp and what sits next to a camera in its room.
 */
internal object CcCameraLogic {
  const val SNAPSHOT_WIDTH = 640
  const val SNAPSHOT_INTERVAL_MS = 5_000L
  /** One large tile + four snapshots, as in the mock's grid. */
  const val GRID_MAX = 5
  /** Other cameras under the full view's side column. */
  const val STRIP_MAX = 3
  /** Shortcuts offered next to the full view (porch light, lock, garage). */
  const val SHORTCUTS_MAX = 3
  private const val MIN_TOKEN = 4
  private val PREFERRED = listOf("doorbell", "front", "door", "entry", "porch")
  private val GENERIC = setOf("camera", "cam", "stream", "main", "high", "low", "snapshot")

  /** `<base>/api/camera_proxy/<id>?width=640` — HA scales the JPEG before it crosses the network. */
  fun snapshotUrl(base: String, cameraId: String, width: Int = SNAPSHOT_WIDTH): String =
      "${DoorbellUrls.snapshot(base, cameraId)}?width=$width"

  /** Power-of-two `inSampleSize` so a [srcWidth] picture decodes at most [maxWidth] wide. */
  fun sampleSize(srcWidth: Int, maxWidth: Int = SNAPSHOT_WIDTH): Int {
    if (srcWidth <= 0 || maxWidth <= 0) return 1
    var s = 1
    while (srcWidth / s > maxWidth) s *= 2
    return s
  }

  /** "2026-11-17 19:42:30", like a camera's burnt-in clock. */
  fun timestamp(ms: Long, zone: TimeZone = TimeZone.getDefault()): String = timestampFormat(zone).format(Date(ms))

  /** One formatter per tile (SimpleDateFormat isn't thread-safe; the tile formats on the main thread). */
  fun timestampFormat(zone: TimeZone = TimeZone.getDefault()): SimpleDateFormat =
      SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }

  /** Source rectangle in pixels. */
  data class Crop(val x: Int, val y: Int, val w: Int, val h: Int)

  /** CENTER_CROP: the largest centred part of a [srcW]×[srcH] picture with the [dstW]×[dstH] aspect. */
  fun centerCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Crop {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return Crop(0, 0, srcW.coerceAtLeast(0), srcH.coerceAtLeast(0))
    val scale = maxOf(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
    val w = Math.round(dstW / scale).toInt().coerceIn(1, srcW)
    val h = Math.round(dstH / scale).toInt().coerceIn(1, srcH)
    return Crop((srcW - w) / 2, (srcH - h) / 2, w, h)
  }

  /** Cameras for the grid: the front door / doorbell first (it gets the large live tile), then by name. */
  fun gridCameras(cameras: List<CcEntity>, max: Int = GRID_MAX): List<CcEntity> {
    fun rank(e: CcEntity): Int = PREFERRED.indexOfFirst { it in e.id }.let { if (it < 0) PREFERRED.size else it }
    return cameras.sortedWith(compareBy<CcEntity>({ rank(it) }, { it.name.lowercase() })).take(max)
  }

  /** What the full view lists beside [cameraId]: motion near it and the controls a person reaches for. */
  @Immutable
  data class Context(val roomName: String?, val motion: List<CcEntity>, val shortcuts: List<CcEntity>)

  fun context(cameraId: String, plan: CcPlan?, entities: Map<String, CcEntity>): Context {
    val room = plan?.roomOf(cameraId)
    val near: List<CcEntity> = room?.devices?.mapNotNull { entities[it.entityId] }?.filter { it.id != cameraId }
        ?: byName(cameraId, entities)
    val motion = near.filter { CcKind.of(it) == CcKind.MOTION }.sortedByDescending { it.lastChanged }
    val shortcuts = near.filter { CcKind.of(it) in SHORTCUT_KINDS && !it.unavailable }
        .sortedBy { SHORTCUT_KINDS.indexOf(CcKind.of(it)) }.take(SHORTCUTS_MAX)
    return Context(room?.name, motion, shortcuts)
  }

  private val SHORTCUT_KINDS = listOf(CcKind.LIGHT, CcKind.LOCK, CcKind.GARAGE)

  /** Without a plan room: entities whose id shares a meaningful word with the camera's ("front_door"). */
  private fun byName(cameraId: String, entities: Map<String, CcEntity>): List<CcEntity> {
    val words = tokens(cameraId)
    if (words.isEmpty()) return emptyList()
    return entities.values.filter { it.id != cameraId && it.domain != "camera" && tokens(it.id).any(words::contains) }
  }

  fun tokens(id: String): Set<String> =
      id.substringAfter('.').split('_', '-', '.').filter { it.length >= MIN_TOKEN && it !in GENERIC }.toSet()

  /** The chip label for a shortcut, saying what tapping it will do. */
  fun shortcutLabel(e: CcEntity): String = when (CcKind.of(e)) {
    CcKind.LIGHT -> "${e.name} ${if (e.isOn) "off" else "on"}"
    CcKind.LOCK -> "${if (e.isOpen) "Lock" else "Unlock"} ${e.name}"
    CcKind.GARAGE -> "${if (e.isOpen) "Close" else "Open"} ${e.name}"
    else -> e.name
  }

  /** The call a shortcut chip makes (through `ui.act`, so unlock and garage still ask first). */
  fun shortcutCall(e: CcEntity): CcCall? = when (CcKind.of(e)) {
    CcKind.LIGHT -> CcCalls.lightPower(e, !e.isOn)
    CcKind.LOCK -> if (e.isOpen) CcCalls.lock(e) else CcCalls.unlock(e)
    CcKind.GARAGE -> if (e.domain != "cover") null else if (e.isOpen) CcCalls.coverClose(e) else CcCalls.coverOpen(e)
    else -> null
  }
}

/**
 * Portal-wide: at most one live MJPEG stream at a time (the Portal's decoder and Wi-Fi are
 * modest). A tile [claim]s a token; the newest claim owns the stream and earlier tiles see
 * [owner] change and fall back to snapshots. [release] only clears the owner it still holds.
 */
internal class CcLiveGuard {
  private val seq = java.util.concurrent.atomic.AtomicLong(0)
  private val current = MutableStateFlow(NONE)
  val owner: StateFlow<Long> get() = current

  fun claim(): Long = seq.incrementAndGet().also { current.value = it }

  fun owns(token: Long): Boolean = token != NONE && current.value == token

  fun release(token: Long) { current.compareAndSet(token, NONE) }

  companion object {
    const val NONE = 0L
  }
}

internal val CcLiveStream = CcLiveGuard()
