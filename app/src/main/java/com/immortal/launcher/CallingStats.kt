/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** One sample of call health from WebRTC's stats (logged for perf work; drives the quality row). */
@Immutable
internal data class CallStats(
    val atMs: Long,
    val sendFps: Double = 0.0,
    val sendKbps: Int = 0,
    val sendWidth: Int = 0,
    val sendHeight: Int = 0,
    val recvFps: Double = 0.0,
    val recvKbps: Int = 0,
    val rttMs: Int? = null,
    val jitterBufferMs: Int? = null,
    val encoder: String = "",
    val codec: String = "",
    val limitedBy: String = "",
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
) {
  /**
   * Rough mouth-to-ear / glass-to-glass estimate: half the round trip, the receive jitter buffer,
   * plus a fixed capture + encode + decode + render allowance measured on the Portal pipeline.
   */
  val latencyEstimateMs: Int? get() = rttMs?.let { it / 2 + (jitterBufferMs ?: 0) + PIPELINE_ALLOWANCE_MS }

  fun logLine(): String = "send ${sendWidth}x$sendHeight@${"%.1f".format(sendFps)} ${sendKbps}kbps enc=$encoder codec=$codec " +
      "limit=$limitedBy recv@${"%.1f".format(recvFps)} ${recvKbps}kbps rtt=${rttMs ?: -1}ms jb=${jitterBufferMs ?: -1}ms " +
      "e2e~${latencyEstimateMs ?: -1}ms"

  companion object {
    /** Camera (~33 ms frame) + HW encode (~10) + decode (~10) + render/vsync (~17). */
    const val PIPELINE_ALLOWANCE_MS = 70
  }
}

/** A WebRTC stats object reduced to what the parser needs, so parsing is JVM-testable. */
internal data class RawStat(val type: String, val id: String, val members: Map<String, Any?>)

internal object CallStatsParser {
  private const val BITS_PER_BYTE = 8
  private const val MS_PER_S = 1_000.0

  /** Parses one report; bitrates come from byte deltas against [previous]. */
  fun parse(stats: Collection<RawStat>, nowMs: Long, previous: CallStats?): CallStats {
    val codecs = stats.filter { it.type == "codec" }.associate { it.id to (it.members["mimeType"] as? String).orEmpty() }
    val out = stats.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" }?.members
    val inb = stats.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }?.members
    val pair = stats.firstOrNull {
      it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true
    }?.members
    val sent = num(out?.get("bytesSent"))?.toLong() ?: 0L
    val received = num(inb?.get("bytesReceived"))?.toLong() ?: 0L
    val elapsedS = previous?.let { (nowMs - it.atMs) / MS_PER_S }?.takeIf { it > 0 }
    fun kbps(now: Long, before: Long?): Int =
        if (elapsedS == null || before == null || now < before) 0 else ((now - before) * BITS_PER_BYTE / elapsedS / MS_PER_S).toInt()
    val emitted = num(inb?.get("jitterBufferEmittedCount"))?.toDouble()
    val delay = num(inb?.get("jitterBufferDelay"))?.toDouble()
    return CallStats(
        atMs = nowMs,
        sendFps = num(out?.get("framesPerSecond"))?.toDouble() ?: 0.0,
        sendKbps = kbps(sent, previous?.bytesSent),
        sendWidth = num(out?.get("frameWidth"))?.toInt() ?: 0,
        sendHeight = num(out?.get("frameHeight"))?.toInt() ?: 0,
        recvFps = num(inb?.get("framesPerSecond"))?.toDouble() ?: 0.0,
        recvKbps = kbps(received, previous?.bytesReceived),
        rttMs = num(pair?.get("currentRoundTripTime"))?.let { (it.toDouble() * MS_PER_S).toInt() },
        jitterBufferMs = if (emitted != null && emitted > 0 && delay != null) (delay / emitted * MS_PER_S).toInt() else null,
        encoder = (out?.get("encoderImplementation") as? String).orEmpty(),
        codec = (out?.get("codecId") as? String)?.let(codecs::get).orEmpty(),
        limitedBy = (out?.get("qualityLimitationReason") as? String).orEmpty(),
        bytesSent = sent,
        bytesReceived = received,
    )
  }

  private fun num(v: Any?): Number? = v as? Number
}
