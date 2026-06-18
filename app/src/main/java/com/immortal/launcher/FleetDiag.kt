/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.json.JSONObject

/**
 * Read-only device diagnostics + logcat for the fleet agent — a safe stand-in for
 * a raw remote shell. Commands run as the app uid (no privileged operations) and
 * come from a FIXED allow-list, so this can't be turned into arbitrary remote code
 * execution. `logcat` needs the `READ_LOGS` permission (a *development* permission
 * the provisioning kit grants via `pm grant`) to see system-wide logs; without it
 * the app sees only its own.
 */
object FleetDiag {

  /**
   * A small snapshot of device health. Commands are chosen to work for an app uid
   * on these Portals — `uptime` already carries the load average, and SELinux
   * denies an app `/proc/loadavg`. Anything that is denied degrades to an
   * "error: …" string rather than failing the whole call.
   */
  fun snapshot(): JSONObject =
      JSONObject()
          .put("ok", true)
          .put("uptime", exec(arrayOf("uptime")).trim())
          .put("storage", exec(arrayOf("df", "-h")).trim())
          .put("meminfo", exec(arrayOf("cat", "/proc/meminfo"), maxBytes = 8 * 1024).trim())

  /** Bounded number of recent logcat lines (text). Clamped so a request can't pull
   *  an unbounded dump into memory; [FleetRoutes] streams larger pulls directly. */
  fun logcat(lines: Int): String =
      exec(arrayOf("logcat", "-d", "-t", "${lines.coerceIn(1, 20_000)}"), maxBytes = 4 * 1024 * 1024)

  /**
   * Run a fixed command, capturing stdout+stderr up to [maxBytes]. Best-effort:
   * returns an "error: …" string rather than throwing, so a missing binary or a
   * permission denial degrades gracefully.
   */
  private fun exec(cmd: Array<String>, maxBytes: Int = 256 * 1024): String =
      runCatching {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.readCapped(maxBytes)
            p.waitFor()
            out
          }
          .getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }

  private fun java.io.InputStream.readCapped(maxBytes: Int): String {
    val buf = ByteArray(minOf(maxBytes, 64 * 1024))
    val acc = java.io.ByteArrayOutputStream()
    while (acc.size() < maxBytes) {
      val n = read(buf, 0, minOf(buf.size, maxBytes - acc.size()))
      if (n == -1) break
      acc.write(buf, 0, n)
    }
    return acc.toString("UTF-8")
  }
}
