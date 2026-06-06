/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.File

/** One playable item from the user's chosen folder. */
data class MediaItem(val path: String, val isVideo: Boolean)

/** A pickable storage location (internal, SD, or a USB-C drive). */
data class StorageRoot(val label: String, val path: String)

/**
 * Enumerates photos/videos under a folder the user picked. The Portal has no
 * system document picker, so this works on plain file paths (the app opts into
 * legacy storage and holds READ_EXTERNAL_STORAGE). A folder can live on internal
 * storage, an SD card, or a USB-C drive plugged into the Portal — all of which
 * mount under /storage.
 *
 * Everything is best-effort and exception-safe: if a drive is unplugged or a file
 * is unreadable, it's skipped, and the screensaver falls back to the default feed.
 */
object LocalMedia {

  private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
  private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "ts")

  enum class Kind {
    IMAGE,
    VIDEO,
    OTHER,
  }

  /** Classify a file by its extension (pure, for testing). */
  fun classify(name: String): Kind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
      in IMAGE_EXT -> Kind.IMAGE
      in VIDEO_EXT -> Kind.VIDEO
      else -> Kind.OTHER
    }
  }

  /** A friendly name for a folder path, e.g. "/storage/emulated/0/DCIM/Trip" → "Trip". */
  fun displayName(path: String): String =
      path.trimEnd('/').substringAfterLast('/').ifBlank { path }

  /** True if the folder still exists and is readable (drive present, perm intact). */
  fun isAccessible(path: String): Boolean =
      runCatching { File(path).let { it.isDirectory && it.canRead() } }.getOrDefault(false)

  /**
   * Recursively collect images (and videos, if [includeVideo]) under [path], capped
   * at [max] so a huge drive can't stall the screensaver. Stable order by path; the
   * caller shuffles if the user asked for it.
   */
  fun enumerate(path: String, includeVideo: Boolean, max: Int = 2000): List<MediaItem> {
    val out = ArrayList<MediaItem>()
    val root = runCatching { File(path) }.getOrNull() ?: return out
    if (!root.isDirectory) return out
    val stack = ArrayDeque<File>()
    stack.addLast(root)
    while (stack.isNotEmpty() && out.size < max) {
      val dir = stack.removeLast()
      val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
      children.sortedBy { it.name.lowercase() }.forEach { f ->
        if (out.size >= max) return@forEach
        when {
          f.isDirectory -> stack.addLast(f)
          else ->
              when (classify(f.name)) {
                Kind.IMAGE -> out.add(MediaItem(f.absolutePath, false))
                Kind.VIDEO -> if (includeVideo) out.add(MediaItem(f.absolutePath, true))
                Kind.OTHER -> {}
              }
        }
      }
    }
    return out
  }

  /**
   * Storage volumes the user can browse: internal storage plus any mounted SD/USB
   * volumes under /storage (skipping the internal self/emulated bookkeeping dirs).
   */
  fun storageRoots(): List<StorageRoot> {
    val roots = ArrayList<StorageRoot>()
    val internal = "/storage/emulated/0"
    if (File(internal).canRead()) roots.add(StorageRoot("Internal storage", internal))
    runCatching {
      File("/storage").listFiles()?.forEach { v ->
        val n = v.name
        if (n == "emulated" || n == "self" || !v.isDirectory || !v.canRead()) return@forEach
        // Volume ids look like "1A2B-3C4D" (SD/USB); give them a friendly label.
        roots.add(StorageRoot("USB / SD card ($n)", v.absolutePath))
      }
    }
    return roots
  }
}
