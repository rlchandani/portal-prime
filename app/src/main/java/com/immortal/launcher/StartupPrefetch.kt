/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Cold-start page-cache warm-up for the launcher's own compiled code.
 *
 * After a reboot the page cache is empty, and the main thread pays for every class it links
 * with random 4 KB page faults into `base.odex` / `base.vdex` on the Portal's eMMC (perfetto:
 * ~1.1–1.9 s of uninterruptible I/O wait inside `bindApplication`, with ART's `LinkMethod` and
 * `CodeInfo::Decode` as the faulting frames). One background thread reads those two files
 * front to back as soon as the process starts. Sequential reads are far cheaper on this storage,
 * and the main thread's later faults then hit the page cache.
 *
 * Reads only the app's own dexopt output, never user data. Best-effort: a missing or unreadable
 * file (no dexopt yet, another ISA) is skipped, and a warm process re-reads from the page cache.
 */
internal object StartupPrefetch {
  private const val TAG = "StartupPrefetch"
  private const val BUFFER_BYTES = 256 * 1024
  private const val THREAD_NAME = "hearthpane-prefetch"
  /** Code first: ART faults into the compiled code and its metadata before the dex bytes. */
  private val ARTIFACTS = listOf("base.odex", "base.vdex")

  @Volatile private var started = false

  /** ART's instruction-set directory for an ABI name, or null for an unknown ABI. */
  fun isaFor(abi: String): String? = when (abi) {
    "arm64-v8a" -> "arm64"
    "armeabi-v7a", "armeabi" -> "arm"
    "x86_64" -> "x86_64"
    "x86" -> "x86"
    else -> null
  }

  /** The dexopt files next to [apkPath] for [isa], in read order. */
  fun artifacts(apkPath: String, isa: String): List<File> {
    val oatDir = File(File(apkPath).parentFile ?: return emptyList(), "oat/$isa")
    return ARTIFACTS.map { File(oatDir, it) }
  }

  /** Once per process; returns immediately. Call as early as possible (attachBaseContext). */
  fun start(context: Context) {
    if (started) return
    started = true
    val apkPath = context.applicationInfo?.sourceDir ?: return
    val isa = Build.SUPPORTED_ABIS.firstOrNull()?.let(::isaFor) ?: return
    val files = artifacts(apkPath, isa)
    runCatching {
      Thread({ files.forEach(::readThrough) }, THREAD_NAME).apply { isDaemon = true }.start()
    }.onFailure { Log.w(TAG, "prefetch thread failed to start", it) }
  }

  private fun readThrough(file: File) {
    try {
      if (!file.canRead()) return
      val buffer = ByteArray(BUFFER_BYTES)
      FileInputStream(file).use { input -> while (input.read(buffer) >= 0) Unit }
    } catch (e: IOException) {
      Log.d(TAG, "skipped ${file.name}: ${e.message}")
    } catch (e: SecurityException) {
      Log.d(TAG, "skipped ${file.name}: ${e.message}")
    }
  }
}
