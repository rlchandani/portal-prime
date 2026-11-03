/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * File browsing for the fleet agent. Runs as the app uid, so it sees what the app
 * can: `/sdcard` (legacy external storage is on for these Android-10 Portals) plus
 * world-readable system paths — roughly adb shell's surface, minus other apps'
 * private `/data`. The token + LAN guard ([FleetHttpServer]) are the access
 * boundary; the OS file permissions are the backstop. Read/write streaming is done
 * by [FleetRoutes] against [File]; this object just maps a directory to JSON.
 */
object FleetFs {

  fun list(path: String): JSONObject {
    val dir = File(path)
    if (!dir.exists()) return err("not_found")
    if (!dir.isDirectory) return err("not_a_directory")
    val files = dir.listFiles() ?: return err("not_readable")
    val entries = JSONArray()
    for (f in files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
      entries.put(
          JSONObject()
              .put("name", f.name)
              .put("type", entryType(f.isDirectory, f.isFile))
              .put("size", if (f.isFile) f.length() else 0L)
              .put("mtime", f.lastModified())
              .put("readable", f.canRead()))
    }
    return JSONObject()
        .put("ok", true)
        .put("path", dir.absolutePath)
        .put("entries", entries)
  }

  /** Pure entry-type label (unit-testable without a filesystem). */
  internal fun entryType(isDir: Boolean, isFile: Boolean): String =
      when {
        isDir -> "dir"
        isFile -> "file"
        else -> "other"
      }

  /** ASCII HTTP syntax with a legacy fallback and RFC8187 UTF-8 filename. */
  internal fun attachmentDisposition(name: String): String {
    val safe = name.map { if (it.isISOControl()) '_' else it }.joinToString("")
        .ifEmpty { "download" }
    // Some legacy clients mishandle quoted-pair escapes; keep their fallback simple.
    val fallback = safe.map {
      if (it.code in 32..126 && it != '"' && it != '\\') it else '_'
    }.joinToString("")
    val encoded = buildString {
      for (byte in safe.toByteArray(Charsets.UTF_8)) {
        val value = byte.toInt() and 255
        val char = value.toChar()
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
            char in "!#$&+-.^_`|~") {
          append(char)
        } else {
          append('%')
          append("0123456789ABCDEF"[value ushr 4])
          append("0123456789ABCDEF"[value and 15])
        }
      }
    }
    return "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded"
  }

  private fun err(code: String) = JSONObject().put("ok", false).put("error", code)
}
