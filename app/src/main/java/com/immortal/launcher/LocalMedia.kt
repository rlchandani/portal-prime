/*
 * Copyright (c) 2026 Starbright Lab.
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

/** A quick count + a few example items for previewing a folder in the picker. */
data class MediaSummary(
    val photos: Int,
    val videos: Int,
    val capped: Boolean,
    val samples: List<MediaItem>,
) {
  val total: Int
    get() = photos + videos
}

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

  /**
   * Directories we never descend into for a photo source. Hidden/dot dirs are OS/app caches
   * (.thumbnails, .trashed); "Android" is the app-data/obb/media tree (app caches, not the user's
   * pictures); "cache" is a generic cache dir; nested "Screenshots" folders are skipped so a broad
   * scan doesn't surface screenshots. An explicitly-picked folder is always scanned regardless —
   * only nested subdirs are filtered — so choosing a Screenshots folder on purpose still works.
   */
  fun isJunkDir(name: String): Boolean =
      name.startsWith(".") ||
          name.equals("Android", ignoreCase = true) ||
          name.equals("cache", ignoreCase = true) ||
          name.equals("Screenshots", ignoreCase = true)

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
   * at [max] media items and [maxScan] directory entries. Each physical directory
   * is visited once, including when aliases or parent links appear in the tree.
   * Stable order by path; the caller shuffles if the user asked for it.
   */
  fun enumerate(
      path: String,
      includeVideo: Boolean,
      max: Int = 2000,
      maxScan: Int = 20_000,
  ): List<MediaItem> {
    val out = ArrayList<MediaItem>()
    if (max <= 0 || maxScan <= 0) return out
    val root = runCatching { File(path) }.getOrNull() ?: return out
    if (!root.isDirectory) return out
    val stack = ArrayDeque<File>()
    val visited = HashSet<String>()
    var scanned = 0
    stack.addLast(root)
    while (stack.isNotEmpty() && out.size < max && scanned < maxScan) {
      PhotoSourceWork.checkpoint()
      val dir = stack.removeLast()
      if (!visitDirectory(dir, visited)) continue
      val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
      for (f in children.sortedBy { it.name.lowercase() }) {
        PhotoSourceWork.checkpoint()
        if (out.size >= max || scanned >= maxScan) break
        scanned++
        when {
          f.isDirectory -> if (!isJunkDir(f.name)) stack.addLast(f)
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
   * Recursively count photos/videos under [path] and grab the first few as preview
   * samples. Bounded by [maxCount] and [maxScan], including directory entries;
   * when hit, [MediaSummary.capped] is true (show "N+"). Aliases do not inflate counts.
   */
  fun summarize(
      path: String,
      maxCount: Int = 600,
      maxScan: Int = 4000,
      sampleCount: Int = 3,
  ): MediaSummary {
    var photos = 0
    var videos = 0
    var capped = false
    var scanned = 0
    val samples = ArrayList<MediaItem>()
    val root = runCatching { File(path) }.getOrNull()
    if (root == null || !root.isDirectory) return MediaSummary(0, 0, false, emptyList())
    if (maxCount <= 0 || maxScan <= 0) return MediaSummary(0, 0, true, emptyList())
    val stack = ArrayDeque<File>()
    val visited = HashSet<String>()
    stack.addLast(root)
    loop@ while (stack.isNotEmpty()) {
      PhotoSourceWork.checkpoint()
      val dir = stack.removeLast()
      if (!visitDirectory(dir, visited)) continue
      val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
      for (f in children) {
        PhotoSourceWork.checkpoint()
        scanned++
        if (f.isDirectory) {
          if (!isJunkDir(f.name)) stack.addLast(f)
        } else {
          when (classify(f.name)) {
            Kind.IMAGE -> {
              photos++
              if (samples.size < sampleCount) samples.add(MediaItem(f.absolutePath, false))
            }
            Kind.VIDEO -> {
              videos++
              if (samples.size < sampleCount) samples.add(MediaItem(f.absolutePath, true))
            }
            Kind.OTHER -> {}
          }
        }
        if (photos + videos >= maxCount || scanned >= maxScan) {
          capped = true
          break@loop
        }
      }
    }
    return MediaSummary(photos, videos, capped, samples)
  }

  private fun visitDirectory(dir: File, visited: MutableSet<String>): Boolean =
      runCatching { visited.add(dir.canonicalPath) }.getOrDefault(false)

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
