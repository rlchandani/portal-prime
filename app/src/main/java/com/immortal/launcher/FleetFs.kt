/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

  private fun err(code: String) = JSONObject().put("ok", false).put("error", code)
}
