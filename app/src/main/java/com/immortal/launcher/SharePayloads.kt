/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/** A validated phone → Portal share. Everything else in a `/remote/share` body is ignored. */
internal sealed interface ShareRequest {
  data class Note(val id: String, val from: String, val text: String) : ShareRequest
  data class Link(val id: String, val from: String, val url: String, val title: String?) : ShareRequest
  /** "This saved photo came from [from]": marks an already-acknowledged upload as fresh. */
  data class Photo(val id: String, val from: String, val path: String, val bytes: Long) : ShareRequest
  data class Retract(val id: String) : ShareRequest
}

/** Pure validation of untrusted phone payloads (the paired session is already authenticated). */
internal object SharePayloads {
  const val MAX_TEXT = 1_000
  const val MAX_NAME = 40
  const val MAX_URL = 2_048
  const val MAX_TITLE = 120
  const val MAX_PHOTO_BYTES = 20L * 1024 * 1024
  private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
  private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif")

  fun parse(body: JSONObject): ShareRequest? {
    val id = (body.opt("id") as? String)?.takeIf { it.matches(ID) } ?: return null
    return when (body.opt("type")) {
      "note" -> {
        val from = cleanName(body.opt("from")) ?: return null
        val text = (body.opt("text") as? String)?.replace("\u0000", "")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT } ?: return null
        ShareRequest.Note(id, from, text)
      }
      "link" -> {
        val from = cleanName(body.opt("from")) ?: return null
        val url = (body.opt("url") as? String)?.trim()?.takeIf(::validUrl) ?: return null
        val title = (body.opt("title") as? String)?.let(::cleanLine)?.take(MAX_TITLE)?.ifEmpty { null }
        ShareRequest.Link(id, from, url, title)
      }
      "photo" -> {
        val from = cleanName(body.opt("from")) ?: return null
        val path = (body.opt("path") as? String)?.takeIf(::validPhotoPath) ?: return null
        val bytes = (body.opt("bytes") as? Number)?.toLong()?.takeIf { it in 1..MAX_PHOTO_BYTES } ?: return null
        ShareRequest.Photo(id, from, path, bytes)
      }
      "retract" -> ShareRequest.Retract(id)
      else -> null
    }
  }

  fun cleanName(raw: Any?): String? =
      (raw as? String)?.let(::cleanLine)?.take(MAX_NAME)?.trim()?.ifEmpty { null }

  private fun cleanLine(raw: String): String =
      raw.filterNot { it.isISOControl() }.replace(Regex("\\s+"), " ").trim()

  fun validUrl(url: String): Boolean {
    if (url.length > MAX_URL || url.any { it.isWhitespace() || it.isISOControl() }) return false
    val lower = url.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.isNotEmpty() && '@' !in authority
  }

  fun validPhotoPath(path: String): Boolean =
      (path.startsWith("/sdcard/") || path.startsWith("/storage/emulated/0/")) &&
          path.split('/').none { it == ".." || it == "." } && path.none { it == '\\' || it.isISOControl() } &&
          path.substringAfterLast('.', "").lowercase() in IMAGE && !path.substringAfterLast('/').startsWith(".")
}

/**
 * Quiet hours for shares: the household's existing quiet window (Sounds → quiet hours) or the
 * screensaver's overnight night-clock window. Pure core; [isQuietNow] reads the live settings.
 */
internal object ShareQuiet {
  data class Window(val on: Boolean, val startMin: Int, val endMin: Int)

  fun isQuiet(minuteOfDay: Int, chime: Window, overnight: Window): Boolean =
      chime.on && ChimeConfig.inQuietWindow(minuteOfDay, chime.startMin, chime.endMin) ||
          overnight.on && SleepScheduler.inWindow(minuteOfDay, overnight.startMin, overnight.endMin)

  fun isQuietNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val chime = ChimeConfig.load(context)
    val frame = ScreensaverConfig.load(context)
    return isQuiet(minute, Window(chime.quietHoursOn, chime.quietStartMin, chime.quietEndMin),
        Window(frame.overnightEnabled, frame.overnightStartMin, frame.overnightEndMin))
  }
}
