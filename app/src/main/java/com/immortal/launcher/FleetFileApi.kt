/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.FleetReply.err
import com.immortal.launcher.FleetReply.json
import com.immortal.launcher.FleetReply.ok
import java.io.EOFException
import java.io.File

/**
 * `/fs/list`, `/fs/read` and `/fs/write`. The app's private data (prefs, keys, tokens, sessions),
 * the fleet manifest and the `/proc` side doors are refused on all three ([FleetSecretPaths]);
 * private data comes back only through the sealed import (`/backup/import`), never raw. Pure: the
 * filesystem comes in through [FleetFiles].
 */
internal class FleetFileApi(private val files: FleetFiles) {

  fun list(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val path = req.queryParam("path") ?: DEFAULT_LIST_PATH
    // The app's private data can't be listed either (names and sizes are metadata too).
    return if (files.isPrivate(File(path))) json(403, err("protected_path")) else json(200, files.list(path))
  }

  fun read(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val path = req.queryParam("path") ?: return json(400, err("path_required"))
    val f = File(path)
    // Refuses the app's private data (prefs, keys, tokens, sessions) and /proc side doors.
    if (files.isPrivate(f)) return json(403, err("protected_path"))
    if (!files.isReadableFile(f)) return json(404, err("not_a_readable_file"))
    return files.read(f)
  }

  fun write(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val path = req.queryParam("path") ?: return json(400, err("path_required"))
    val f = File(path)
    // Private data comes back only through the sealed import (/backup/import), never raw.
    if (files.isPrivate(f)) return json(403, err("protected_path"))
    // Checked before reading a byte: a size cap, and enough free space left after the write.
    FsWritePolicy.refusal(req.contentLength.toLong(), files.usableSpaceFor(f))?.let {
      return json(if (it == "body_too_large") 413 else 507, err(it)
          .put("maxBytes", FsWritePolicy.MAX_BYTES).put("minFreeAfterBytes", FsWritePolicy.MIN_FREE_AFTER_BYTES))
    }
    return try {
      val written = files.write(f, req::streamBodyTo)
      json(200, ok().put("path", f.absolutePath).put("bytes", written))
    } catch (t: Throwable) {
      json(if (t is EOFException) 400 else 500, err("write_failed").put("detail", t.message ?: ""))
    }
  }

  companion object {
    const val DEFAULT_LIST_PATH = "/sdcard"
  }
}
