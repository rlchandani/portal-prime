/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.OutputStream
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Pure filesystem policy, injectable root for JVM tests; /sdcard is the only wire root. */
internal object RemoteCompanionPhotoPolicy {
  const val MAX_BYTES = 20 * 1024 * 1024
  private val extensions = setOf("jpg", "jpeg", "png", "webp", "gif")
  fun validName(name: String): Boolean = name.length in 1..128 && !name.startsWith('.') && name.none { it.code < 32 || it in "/\\:" } && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions
  fun resolve(root: File, path: String, directory: Boolean): File? = runCatching {
    if (path.length > 1024 || !(path == "/sdcard" || path.startsWith("/sdcard/"))) return null
    val segments = path.removePrefix("/sdcard").removePrefix("/").let { if (it.isEmpty()) emptyList() else it.split('/') }
    if (segments.any { it.isBlank() || it.startsWith('.') || it.any { c -> c.code < 32 || c == '\\' || c == ':' } } || segments.firstOrNull()?.equals("Android", true) == true) return null
    val canonicalRoot = root.canonicalFile
    var file = canonicalRoot
    for (segment in segments) {
      file = File(file, segment)
      // Reject any child symlink, including links to another directory inside /sdcard.
      if (file.canonicalPath != file.absolutePath) return null
    }
    if (!directory && (!validName(file.name) || segments.isEmpty())) return null
    file
  }.getOrNull()
  fun magicMatches(name: String, bytes: ByteArray): Boolean {
    fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(255)
    return when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
      "jpg", "jpeg" -> at(0) == 255 && at(1) == 216 && at(2) == 255
      "png" -> bytes.size >= 8 && bytes.take(8) == listOf(137,80,78,71,13,10,26,10).map { it.toByte() }
      "gif" -> bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
      "webp" -> bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
      else -> false
    }
  }

  /** Reserve without replacement, verify a temporary file, then publish it by atomic rename. */
  @Synchronized fun store(folder: File, name: String, expected: Int, write: (OutputStream) -> Long, verify: (File) -> Boolean): String {
    if (!validName(name) || expected !in 1..MAX_BYTES) return "invalid_upload"
    val destination = File(folder, name)
    if (destination.exists()) return "collision"
    val temp = File.createTempFile(".immortal-upload-", ".tmp", folder)
    var reserved = false
    return try {
      val bytes = FileOutputStream(temp).use { out -> val count = write(out); out.fd.sync(); count }
      if (bytes != expected.toLong() || temp.length() != expected.toLong()) return "incomplete_upload"
      if (!verify(temp)) return "invalid_image"
      // createNewFile is O_EXCL: concurrent uploads can never replace an existing photo.
      if (!destination.createNewFile()) return "collision"
      reserved = true
      if (!temp.renameTo(destination)) return "commit_failed"
      reserved = false
      "stored"
    } catch (e: Exception) { "upload_failed" }
    finally { temp.delete(); if (reserved && destination.length() == 0L) destination.delete() }
  }
}

/** Open under the photo mutation lock; unlink/reupload cannot change this response's inode. */
internal class RemoteCompanionPhotoStream(file: File) : Closeable {
  private val input = FileInputStream(file)
  private val length = try {
    input.channel.size().also { require(it in 1..RemoteCompanionPhotoPolicy.MAX_BYTES.toLong()) { "photo_size_limit" } }
  } catch (e: Exception) { input.close(); throw e }
  val isOpen: Boolean get() = input.channel.isOpen
  override fun close() = input.close()
  fun response(mime: String): FleetHttpServer.Response = FleetHttpServer.Response.stream(200, mime, length, onClose = ::close) { out ->
    input.use {
      var remaining = length
      val buffer = ByteArray(64 * 1024)
      while (remaining > 0) {
        val n = it.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (n < 0) throw EOFException("photo shortened")
        out.write(buffer, 0, n)
        remaining -= n
      }
    }
  }
}

/** Paired photo routes: streamed writes, sampled decode and a 4 MiB / 32 entry thumbnail cache. */
internal object RemoteCompanionPhotos {
  private val root get() = File("/sdcard")
  private val lock = Any()
  private val thumbnails = LinkedHashMap<String, ByteArray>(32, .75f, true)
  private var cachedBytes = 0
  fun available(): Boolean = root.isDirectory && root.canRead() && root.canWrite()
  fun allowedFolder(path: String): Boolean = RemoteCompanionPhotoPolicy.resolve(root, path, true) != null

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response = synchronized(lock) {
    val expected = when (req.path) { "/remote/photos" -> setOf("GET"); "/remote/photo-upload" -> setOf("POST"); else -> setOf("GET", "DELETE") }
    if (req.method !in expected) return@synchronized error(405, "method_not_allowed")
    val path = req.queryParam("path") ?: return@synchronized error(400, "path_required")
    val directory = req.path != "/remote/photo"
    val file = RemoteCompanionPhotoPolicy.resolve(root, path, directory) ?: return@synchronized error(400, "invalid_path")
    when (req.path) {
      "/remote/photos" -> {
        if (!file.isDirectory) return@synchronized error(404, "folder_not_found")
        val entries = file.listFiles() ?: return@synchronized error(403, "not_readable")
        val selected = entries.asSequence().filter { it.isFile && RemoteCompanionPhotoPolicy.validName(it.name) && RemoteCompanionPhotoPolicy.resolve(root, "$path/${it.name}".replace("//", "/"), false) != null }.take(501).toList()
        val files = JSONArray()
        selected.take(500).forEach { f -> files.put(JSONObject().put("name", f.name).put("path", path.trimEnd('/') + "/" + f.name).put("size", f.length()).put("modified", f.lastModified())) }
        json(200, JSONObject().put("ok", true).put("path", path).put("files", files).put("freeBytes", file.usableSpace).put("truncated", selected.size > 500))
      }
      "/remote/photo-upload" -> {
        val name = req.queryParam("name") ?: return@synchronized error(400, "name_required")
        if (!RemoteCompanionPhotoPolicy.validName(name)) return@synchronized error(400, "invalid_name")
        if (req.contentLength !in 1..RemoteCompanionPhotoPolicy.MAX_BYTES) return@synchronized error(413, "upload_size_limit")
        val mime = req.header("content-type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (mime != null && mime !in setOf("image/jpeg", "image/png", "image/webp", "image/gif", "application/octet-stream")) return@synchronized error(415, "unsupported_media_type")
        if (!file.exists() && !file.mkdirs() || !file.isDirectory || RemoteCompanionPhotoPolicy.resolve(root, path, true) != file) return@synchronized error(403, "folder_unavailable")
        if (file.usableSpace < req.contentLength + 1024 * 1024L) return@synchronized error(507, "insufficient_space")
        val result = RemoteCompanionPhotoPolicy.store(file, name, req.contentLength, req::streamBodyTo) { image -> validImage(image, name) }
        if (result != "stored") return@synchronized error(if (result == "collision") 409 else 400, result)
        json(200, JSONObject().put("ok", true).put("path", path.trimEnd('/') + "/" + name).put("bytes", req.contentLength))
      }
      else -> {
        if (!file.isFile || file.length() !in 1..RemoteCompanionPhotoPolicy.MAX_BYTES.toLong()) return@synchronized error(404, "photo_not_found")
        if (!validImage(file, file.name)) return@synchronized error(415, "invalid_image")
        if (req.method == "DELETE") {
          if (!file.delete()) return@synchronized error(403, "delete_failed")
          clearCache()
          json(200, JSONObject().put("ok", true).put("path", path))
        } else if (req.queryParam("thumbnail") == "1") {
          val thumb = thumbnail(file) ?: return@synchronized error(415, "thumbnail_unavailable")
          FleetHttpServer.Response.stream(200, "image/jpeg", thumb.size.toLong()) { it.write(thumb) }
        } else {
          val mime = when (file.extension.lowercase(Locale.ROOT)) { "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg" }
          RemoteCompanionPhotoStream(file).response(mime)
        }
      }
    }
  }
  private fun validImage(file: File, name: String): Boolean {
    val prefix = ByteArray(12)
    val count = file.inputStream().use { it.read(prefix) }
    if (count <= 0 || !RemoteCompanionPhotoPolicy.magicMatches(name, prefix.copyOf(count))) return false
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    return bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L
  }
  private fun thumbnail(file: File): ByteArray? {
    val key = "${file.path}:${file.length()}:${file.lastModified()}"
    thumbnails[key]?.let { return it }
    return runCatching {
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.path, options)
      var sample = 1
      while (options.outWidth / sample > 512 || options.outHeight / sample > 512) sample *= 2
      options.inJustDecodeBounds = false; options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.RGB_565
      val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
      val bytes = try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 75, it); it.toByteArray() } } finally { bitmap.recycle() }
      if (bytes.size > 512 * 1024) return null
      thumbnails[key] = bytes; cachedBytes += bytes.size
      while (cachedBytes > 4 * 1024 * 1024 || thumbnails.size > 32) {
        val old = thumbnails.entries.iterator(); val entry = old.next(); cachedBytes -= entry.value.size; old.remove()
      }
      bytes
    }.getOrNull()
  }
  private fun clearCache() { thumbnails.clear(); cachedBytes = 0 }
  private fun error(status: Int, code: String) = json(status, JSONObject().put("ok", false).put("error", code))
  private fun json(status: Int, body: JSONObject) = FleetHttpServer.Response(status, body.toString())
}
