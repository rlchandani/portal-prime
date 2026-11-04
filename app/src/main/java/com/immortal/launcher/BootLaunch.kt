/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.IOException

/**
 * Apps to (re)launch after a reboot so their background work resumes without a human.
 * The motivating case is the Music Assistant / Sendspin player: it has no boot receiver
 * of its own, so after a reboot its player stays dead until someone opens the app — but
 * we own the launcher, which DOES run on boot, so we start it for them.
 *
 * The list is one package per line in the shell-writable external file [FILE]
 * (provisioning writes it; blank lines and `#` comments ignored), so it's configurable
 * per device without an app release.
 */
object BootLaunch {
  private const val FILE = "boot_apps.txt"

  private fun file(context: Context): File =
      File(context.getExternalFilesDir(null) ?: throw IOException("Startup storage unavailable"), FILE)

  /** Settings must distinguish an unreadable file from a genuinely empty selection. */
  internal fun readPackages(context: Context): List<String> {
    val file = file(context)
    val parent = file.parentFile!!
    if (!parent.isDirectory || !parent.canExecute()) throw IOException("Startup storage unavailable")
    if (!file.exists()) return emptyList()
    return file.readLines().map { it.trim() }.filter {
      it.isNotBlank() && !it.startsWith("#") && it !in Curation.retiredPackages
    }
  }

  /** A read failure must never stop the boot receiver from starting the launcher. */
  fun packages(context: Context): List<String> =
      runCatching { readPackages(context) }.getOrDefault(emptyList())

  /** Commit a complete replacement without truncating the previous selection on failure. */
  fun setPackages(context: Context, packages: List<String>): Boolean = runCatching {
    val destination = file(context)
    val parent = destination.parentFile!!
    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Startup storage unavailable")
    val temporary = File.createTempFile("boot_apps.", ".tmp", parent)
    try {
      temporary.outputStream().use { output ->
        output.write(packages.joinToString("\n").toByteArray(Charsets.UTF_8))
        output.fd.sync()
      }
      if (!temporary.renameTo(destination)) throw IOException("Startup choices could not be replaced")
    } finally {
      temporary.delete()
    }
  }.isSuccess

  /**
   * Launch each configured, installed app. Starting an activity from the background
   * needs an exemption on Android 10 (SYSTEM_ALERT_WINDOW, which provisioning grants);
   * Android 9 has no background-activity-start restriction, so it's unrestricted there.
   */
  fun launchAll(context: Context): Int {
    val pm = context.packageManager
    var launched = 0
    packages(context).forEach { pkg ->
      runCatching {
        pm.getLaunchIntentForPackage(pkg)?.let { intent ->
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          context.startActivity(intent)
          launched++
        }
      }
    }
    return launched
  }

  /** Keep the last dispatched app only on opt-in; always release the pending boot receiver. */
  internal fun finishStartup(
      stayInApp: Boolean,
      launched: Int,
      returnHome: () -> Unit,
      finish: () -> Unit,
  ) {
    try {
      if (!stayInApp || launched <= 0) returnHome()
    } finally {
      finish()
    }
  }
}
