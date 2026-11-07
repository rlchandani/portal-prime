/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject

/** Commands the bridge script understands; scripts are built from fixed strings only. */
enum class YtMusicCommand(val wire: String) {
  PLAY("play"),
  PAUSE("pause"),
  NEXT("next"),
  PREVIOUS("previous"),
  SEEK("seek"),
}

/**
 * Pure parser for messages posted by `assets/ytmusic/bridge.js`. Page data is untrusted: sizes,
 * types, numbers and art URLs are validated, and anything doubtful is dropped rather than shown.
 *
 * Wire format (v1): `{"v":1,"t":title,"a":artist,"al":album,"art":url,"d":durationSec,
 * "p":positionSec,"s":"playing"|"paused"|"ended"}`.
 */
object YtMusicBridgeParser {
  const val MAX_MESSAGE_CHARS = 8 * 1024
  const val MAX_TEXT_CHARS = 300
  private const val MAX_ART_CHARS = 2048
  private const val MAX_MEDIA_SECONDS = 24.0 * 60 * 60
  private const val WIRE_VERSION = 1
  /** Drift beyond extrapolation that counts as a seek / skip worth republishing. */
  private const val PLAYING_DRIFT_MS = 2_000L
  private const val PAUSED_DRIFT_MS = 1_000L
  private val artHostSuffixes = listOf("googleusercontent.com", "ytimg.com", "ggpht.com")
  private val whitespace = Regex("\\s+")

  /** Parses one bridge message into a now-playing snapshot, or null when nothing valid is playing. */
  fun parse(json: String?, elapsedRealtimeMs: Long): NowPlayingState? {
    if (json.isNullOrBlank() || json.length > MAX_MESSAGE_CHARS) return null
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if ((obj.opt("v") as? Number)?.toInt() != WIRE_VERSION) return null
    val state = when (obj.opt("s")) {
      "playing" -> PlaybackState.PLAYING
      "paused" -> PlaybackState.PAUSED
      "ended" -> PlaybackState.STOPPED
      else -> return null
    }
    val title = text(obj, "t")
    if (title.isEmpty()) return null
    val durationMs = seconds(obj, "d")
    val rawPosition = seconds(obj, "p")
    val positionMs = if (durationMs > 0) rawPosition.coerceAtMost(durationMs) else rawPosition
    return NowPlayingState(
        state = state,
        title = title,
        artist = text(obj, "a"),
        album = text(obj, "al"),
        artUrl = artUrl(obj.opt("art")),
        durationMs = durationMs,
        positionMs = positionMs,
        source = SOURCE_EMBEDDED_YT_MUSIC,
        canSeek = durationMs > 0,
        positionUpdatedElapsedMs = elapsedRealtimeMs,
    )
  }

  /** Whether [next] differs from [prev] enough to publish (metadata, state, or a real jump). */
  fun shouldPublish(prev: NowPlayingState?, next: NowPlayingState, nowElapsedMs: Long): Boolean {
    if (prev == null) return true
    if (prev.state != next.state || prev.title != next.title || prev.artist != next.artist ||
        prev.album != next.album || prev.artUrl != next.artUrl ||
        prev.durationMs != next.durationMs || prev.canSeek != next.canSeek) return true
    return if (next.state == PlaybackState.PLAYING) {
      val expected = prev.positionMs + (nowElapsedMs - prev.positionUpdatedElapsedMs)
      abs(next.positionMs - expected) > PLAYING_DRIFT_MS
    } else {
      abs(next.positionMs - prev.positionMs) > PAUSED_DRIFT_MS
    }
  }

  /** JavaScript for a transport command. Only the enum name and a clamped number are embedded. */
  fun commandScript(command: YtMusicCommand, positionMs: Long = 0L): String {
    val args = if (command == YtMusicCommand.SEEK) {
      val clamped = positionMs.coerceIn(0L, (MAX_MEDIA_SECONDS * 1000).toLong())
      "'${command.wire}',${clamped / 1000.0}"
    } else "'${command.wire}'"
    return "window.__portalYtm&&window.__portalYtm.cmd($args);"
  }

  private fun text(obj: JSONObject, key: String): String = cleanText(obj.opt(key), MAX_TEXT_CHARS)

  /** Untrusted page text: strings only, control/format characters removed, whitespace collapsed. */
  internal fun cleanText(value: Any?, max: Int): String {
    val raw = value as? String ?: return ""
    val cleaned = buildString(minOf(raw.length, max * 2)) {
      for (ch in raw) {
        when {
          ch == '\n' || ch == '\r' || ch == '\t' -> append(' ')
          Character.isISOControl(ch) -> Unit
          Character.getType(ch) == Character.FORMAT.toInt() -> Unit
          else -> append(ch)
        }
        if (length > max * 2) break
      }
    }
    return cleaned.replace(whitespace, " ").trim().take(max).trim()
  }

  private fun seconds(obj: JSONObject, key: String): Long {
    val value = (obj.opt(key) as? Number)?.toDouble() ?: return 0L
    if (value.isNaN() || value.isInfinite() || value <= 0.0 || value > MAX_MEDIA_SECONDS) return 0L
    return (value * 1000).toLong()
  }

  /** An https art URL on a Google image host, or "" (untrusted input). */
  internal fun artUrl(value: Any?): String {
    val url = value as? String ?: return ""
    if (url.length > MAX_ART_CHARS) return ""
    val uri = YtMusicUrls.httpsUri(url) ?: return ""
    val host = uri.host.lowercase(Locale.ROOT)
    val allowed = artHostSuffixes.any { host == it || host.endsWith(".$it") }
    return if (allowed) uri.toString() else ""
  }
}
