/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject

/** Request-local metadata discovery on Portal (API 28+); never opens or decodes image bytes. */
internal class RemoteCompanionPhotoIndex(
    private val root: File,
    private val limits: Limits = Limits(),
    private val nowNanos: () -> Long = System::nanoTime,
    private val openDirectory: (File) -> DirectoryStream<Path> = { Files.newDirectoryStream(it.toPath()) },
) {
  data class Limits(
      val maxPhotos: Int = 1000,
      val maxDirectories: Int = 512,
      val maxEntries: Int = 20000,
      val maxDepth: Int = 8,
      val maxMillis: Long = 500,
  ) {
    init { require(maxPhotos > 0 && maxDirectories > 0 && maxEntries > 0 && maxDepth >= 0 && maxMillis in 1..60000) }
  }
  private data class Folder(val file: File, val path: String, val depth: Int)
  private data class Photo(val name: String, val path: String, val size: Long, val modified: Long) {
    fun json() = JSONObject().put("name", name).put("path", path).put("size", size).put("modified", modified)
  }

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "GET") return error(405, "method_not_allowed")
    val flags = req.query.split('&').filter { it.substringBefore('=') == "recursive" }
    if (flags.isNotEmpty() && (flags.size != 1 || req.queryParam("recursive") != "1")) return error(400, "invalid_recursive")
    val path = req.queryParam("path") ?: return error(400, "path_required")
    val folder = RemoteCompanionPhotoPolicy.resolve(root, path, true) ?: return error(400, "invalid_path")
    if (!folder.isDirectory) return error(404, "folder_not_found")
    return if (flags.isEmpty()) legacy(folder, path) else recursive(folder, path)
  }

  /** Preserve the original shallow response and its 500-photo cap. */
  private fun legacy(folder: File, path: String): FleetHttpServer.Response {
    val entries = folder.listFiles() ?: return error(403, "not_readable")
    val selected = entries.sortedBy { it.name }.asSequence().filter {
      it.isFile && RemoteCompanionPhotoPolicy.validName(it.name) &&
          RemoteCompanionPhotoPolicy.resolve(root, "$path/${it.name}", false) != null
    }.take(501).toList()
    val files = JSONArray()
    selected.take(500).forEach { files.put(Photo(it.name, "$path/${it.name}", it.length(), it.lastModified()).json()) }
    return json(JSONObject().put("ok", true).put("path", path).put("files", files)
        .put("freeBytes", folder.usableSpace).put("truncated", selected.size > 500))
  }

  private fun recursive(folder: File, path: String): FleetHttpServer.Response {
    val started = nowNanos()
    fun expired() = nowNanos() - started >= limits.maxMillis * 1_000_000L
    val pending = ArrayDeque<Folder>().apply { add(Folder(folder, path, 0)) }
    val photos = ArrayList<Photo>()
    var directories = 0
    var examined = 0
    var truncated = false

    scan@ while (pending.isNotEmpty()) {
      if (expired() || directories >= limits.maxDirectories) { truncated = true; break }
      val current = pending.removeFirst()
      // Recheck ancestors before opening: a directory may have changed since enumeration.
      if (RemoteCompanionPhotoPolicy.resolve(root, current.path, true) != current.file || !current.file.isDirectory) {
        truncated = true
        continue
      }
      directories++
      val entries = ArrayList<Path>()
      try {
        // Streaming enumeration bounds even huge folders, including non-photo/hidden entries.
        openDirectory(current.file).use { stream ->
          val iterator = stream.iterator()
          while (true) {
            if (expired()) { truncated = true; break }
            if (!iterator.hasNext()) break
            if (expired() || examined >= limits.maxEntries) { truncated = true; break }
            entries.add(iterator.next())
            examined++
          }
        }
      } catch (e: IOException) {
        if (directories == 1) return error(403, "not_readable")
        truncated = true
      } catch (e: java.nio.file.DirectoryIteratorException) {
        if (directories == 1) return error(403, "not_readable")
        truncated = true
      } catch (e: SecurityException) {
        if (directories == 1) return error(403, "not_readable")
        truncated = true
      }
      // Sort within each folder before applying photo/directory caps; sort the wire result too.
      entries.sortBy { it.fileName.toString() }
      for (entry in entries) {
        if (expired()) { truncated = true; break@scan }
        val name = entry.fileName.toString()
        val wirePath = "${current.path}/$name"
        val file = RemoteCompanionPhotoPolicy.resolve(root, wirePath, true) ?: continue
        val attributes = try {
          Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (e: IOException) { truncated = true; continue }
        catch (e: SecurityException) { truncated = true; continue }
        if (expired()) { truncated = true; break@scan }
        if (attributes.isDirectory) {
          if (current.depth >= limits.maxDepth) truncated = true
          else pending.add(Folder(file, wirePath, current.depth + 1))
        } else if (attributes.isRegularFile && RemoteCompanionPhotoPolicy.validName(name)) {
          if (photos.size >= limits.maxPhotos) { truncated = true; break@scan }
          photos.add(Photo(name, wirePath, attributes.size(), attributes.lastModifiedTime().toMillis()))
        }
      }
    }
    val files = JSONArray()
    photos.sortedBy { it.path }.forEach { files.put(it.json()) }
    val folders = JSONArray()
    // Counts describe returned photos directly in each folder, including the requested root.
    photos.groupingBy { it.path.substringBeforeLast('/') }.eachCount().toSortedMap().forEach { (folderPath, count) ->
      folders.put(JSONObject().put("path", folderPath).put("count", count))
    }
    return json(JSONObject().put("ok", true).put("path", path).put("recursive", true)
        .put("files", files).put("folders", folders).put("freeBytes", folder.usableSpace).put("truncated", truncated))
  }

  private fun error(status: Int, code: String) = FleetHttpServer.Response(status, JSONObject().put("ok", false).put("error", code).toString())
  private fun json(body: JSONObject) = FleetHttpServer.Response(200, body.toString())
}
