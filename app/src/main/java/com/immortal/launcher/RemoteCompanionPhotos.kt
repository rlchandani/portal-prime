/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.OutputStream
import java.util.Locale
import org.json.JSONObject

/** Pure filesystem policy, injectable root for JVM tests; /sdcard is the only wire root. */
internal object RemoteCompanionPhotoPolicy {
  const val MAX_BYTES = 20 * 1024 * 1024
  private val extensions = setOf("jpg", "jpeg", "png", "webp", "gif")
  fun validName(name: String): Boolean = name.length in 1..128 && !name.startsWith('.') && name.none { it.code < 32 || it in "/\\:" } && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions
  fun resolve(root: File, path: String, directory: Boolean): File? = runCatching {
    if (path.length > 1024 || !(path == "/sdcard" || path.startsWith("/sdcard/"))) return null
    val segments = path.removePrefix("/sdcard").removePrefix("/").let { if (it.isEmpty()) emptyList() else it.split('/') }
    if (segments.any { it.isBlank() || it.startsWith('.') || it.any { c -> c.code < 32 || c == '\\' || c == ':' } } || segments.any { it.equals("Android", true) }) return null
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

  private val publicationLock = Any()

  /** Receive and verify privately, then reserve and publish under the short mutation lock. */
  fun store(folder: File, name: String, expected: Int, write: (OutputStream) -> Long,
      verify: (File) -> Boolean, publishLock: Any = publicationLock,
      canPublish: () -> Boolean = { true }): String {
    if (!validName(name) || expected !in 1..MAX_BYTES) return "invalid_upload"
    val destination = File(folder, name)
    if (destination.exists()) return "collision"
    var temp: File? = null
    var stagingParent: String? = null
    return try {
      stagingParent = folder.canonicalPath
      val staged = File.createTempFile(".immortal-upload-", ".tmp", folder)
      temp = staged
      val bytes = FileOutputStream(staged).use { out -> val count = write(out); out.fd.sync(); count }
      if (bytes != expected.toLong() || staged.length() != expected.toLong()) return "incomplete_upload"
      if (!verify(staged)) return "invalid_image"
      synchronized(publishLock) {
        // Recheck the canonical folder after the network transfer, before touching the destination.
        if (!canPublish()) return@synchronized "folder_unavailable"
        // O_EXCL prevents replacing another upload; readers cannot observe this reservation.
        if (!destination.createNewFile()) return@synchronized "collision"
        var committed = false
        try {
          if (!staged.renameTo(destination)) return@synchronized "commit_failed"
          committed = true
          "stored"
        } finally {
          if (!committed && destination.length() == 0L) destination.delete()
        }
      }
    } catch (_: Exception) { "upload_failed" }
    finally { temp?.let { runCatching { if (it.parentFile?.canonicalPath == stagingParent) it.delete() } } }
  }

}

/** Metadata comes from the captured inode; the bitmap decoder retains stream ownership. */
internal fun thumbnailOrientation(input: FileInputStream,
    read: (FileInputStream) -> Int = { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }): Int {
  return try {
    input.channel.position(0)
    read(input).takeIf { it in 1..8 } ?: 1
  } catch (_: java.io.IOException) { 1 }
  finally { input.channel.position(0) }
}

/** Open under the photo mutation lock; unlink/reupload cannot change this response's inode. */
internal class RemoteCompanionPhotoStream(file: File) : Closeable {
  private val input = FileInputStream(file)
  private val length = try {
    input.channel.size().also { require(it in 1..RemoteCompanionPhotoPolicy.MAX_BYTES.toLong()) { "photo_size_limit" } }
  } catch (e: Exception) { input.close(); throw e }
  val isOpen: Boolean get() = input.channel.isOpen
  /** Decode from this already opened inode; the caller still owns and closes the stream. */
  fun <T> image(read: (FileInputStream) -> T): T = read(input)
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
  private val thumbnails = RemotePhotoThumbnailCache()
  fun available(): Boolean = root.isDirectory && root.canRead() && root.canWrite()
  fun allowedFolder(path: String): Boolean = RemoteCompanionPhotoPolicy.resolve(root, path, true) != null

  fun handle(req: FleetHttpServer.Request, storageRoot: File = root,
      beforeThumbnailDecode: () -> Unit = {}): FleetHttpServer.Response {
    // Discovery owns no mutation/thumbnail state: slow storage must not hold their lock.
    if (req.path == "/remote/photos") return RemoteCompanionPhotoIndex(storageRoot).handle(req)
    if (req.path == "/remote/photo-upload") return upload(req, storageRoot)
    if (req.path == "/remote/photo" && req.method == "GET" && req.queryParam("thumbnail") == "1")
      return handleThumbnail(req, storageRoot, beforeThumbnailDecode)
    return handlePhoto(req, storageRoot)
  }
  private fun handlePhoto(req: FleetHttpServer.Request, storageRoot: File): FleetHttpServer.Response = synchronized(lock) {
    if (req.method !in setOf("GET", "DELETE")) return@synchronized error(405, "method_not_allowed")
    val path = req.queryParam("path") ?: return@synchronized error(400, "path_required")
    val directory = req.path != "/remote/photo"
    val file = RemoteCompanionPhotoPolicy.resolve(storageRoot, path, directory) ?: return@synchronized error(400, "invalid_path")
    if (!file.isFile || file.length() !in 1..RemoteCompanionPhotoPolicy.MAX_BYTES.toLong()) return@synchronized error(404, "photo_not_found")
    if (!validImage(file, file.name)) return@synchronized error(415, "invalid_image")
    if (req.method == "DELETE") {
      if (!file.delete()) return@synchronized error(403, "delete_failed")
      clearCache()
      json(200, JSONObject().put("ok", true).put("path", path))
    } else {
      val mime = when (file.extension.lowercase(Locale.ROOT)) { "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg" }
      RemoteCompanionPhotoStream(file).response(mime)
    }
  }
  // The potentially slow body and image validation own only a hidden temporary file.
  // Publication shares the read/delete lock, so final files remain complete and immutable.
  private fun upload(req: FleetHttpServer.Request, storageRoot: File): FleetHttpServer.Response {
    if (req.method != "POST") return error(405, "method_not_allowed")
    val path = req.queryParam("path") ?: return error(400, "path_required")
    val file = RemoteCompanionPhotoPolicy.resolve(storageRoot, path, true) ?: return error(400, "invalid_path")
    val name = req.queryParam("name") ?: return error(400, "name_required")
    if (!RemoteCompanionPhotoPolicy.validName(name)) return error(400, "invalid_name")
    if (req.contentLength !in 1..RemoteCompanionPhotoPolicy.MAX_BYTES) return error(413, "upload_size_limit")
    val mime = req.header("content-type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    if (mime != null && mime !in setOf("image/jpeg", "image/png", "image/webp", "image/gif", "application/octet-stream")) return error(415, "unsupported_media_type")
    if (!file.isDirectory) file.mkdirs()
    // Another upload may have created it between the two checks.
    if (!file.isDirectory || RemoteCompanionPhotoPolicy.resolve(storageRoot, path, true) != file) return error(403, "folder_unavailable")
    if (file.usableSpace < req.contentLength + 1024 * 1024L) return error(507, "insufficient_space")
    val result = RemoteCompanionPhotoPolicy.store(file, name, req.contentLength,
        write = req::streamBodyTo, verify = { image -> validImage(image, name) }, publishLock = lock,
        canPublish = { file.isDirectory && RemoteCompanionPhotoPolicy.resolve(storageRoot, path, true) == file })
    if (result != "stored") return error(if (result == "collision") 409 else 400, result)
    return json(200, JSONObject().put("ok", true).put("path", path.trimEnd('/') + "/" + name).put("bytes", req.contentLength))
  }
  private fun validImage(file: File, name: String): Boolean {
    val prefix = ByteArray(12)
    val count = file.inputStream().use { it.read(prefix) }
    if (count <= 0 || !RemoteCompanionPhotoPolicy.magicMatches(name, prefix.copyOf(count))) return false
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    return bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L
  }
  private data class ThumbnailRead(val name: String, val stream: RemoteCompanionPhotoStream,
      val ticket: RemotePhotoThumbnailCache.Ticket)
  private fun thumbnailKey(file: File) = "${file.path}:${file.length()}:${file.lastModified()}"
  private fun handleThumbnail(req: FleetHttpServer.Request, storageRoot: File,
      beforeDecode: () -> Unit): FleetHttpServer.Response {
    val read = synchronized(lock) {
      val path = req.queryParam("path") ?: return error(400, "path_required")
      val file = RemoteCompanionPhotoPolicy.resolve(storageRoot, path, false) ?: return error(400, "invalid_path")
      if (!file.isFile || file.length() !in 1..RemoteCompanionPhotoPolicy.MAX_BYTES.toLong()) return error(404, "photo_not_found")
      // Capture both the descriptor and cache generation before releasing the mutation lock.
      val stream = runCatching { RemoteCompanionPhotoStream(file) }.getOrNull() ?: return error(404, "photo_not_found")
      ThumbnailRead(file.name, stream, thumbnails.snapshot(thumbnailKey(file)))
    }
    var invalidImage = false
    val bytes = read.stream.use { stream -> runCatching { stream.image { input ->
      val bounds = validatedImageBounds(input, read.name) ?: run { invalidImage = true; return@image null }
      thumbnails.load(read.ticket) { beforeDecode(); thumbnail(input, bounds) }
    } }.getOrNull() } ?: return error(415, if (invalidImage) "invalid_image" else "thumbnail_unavailable")
    return FleetHttpServer.Response.stream(200, "image/jpeg", bytes.size.toLong()) { it.write(bytes) }
  }
  private fun validatedImageBounds(input: FileInputStream, name: String): BitmapFactory.Options? {
    input.channel.position(0)
    val prefix = ByteArray(12)
    val count = input.read(prefix)
    if (count <= 0 || !RemoteCompanionPhotoPolicy.magicMatches(name, prefix.copyOf(count))) return null
    input.channel.position(0)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFileDescriptor(input.fd, null, bounds)
    return bounds.takeIf { it.outWidth in 1..30000 && it.outHeight in 1..30000 && it.outWidth.toLong() * it.outHeight <= 100_000_000L }
  }
  private fun thumbnail(input: FileInputStream, options: BitmapFactory.Options): ByteArray? {
    val plan = SlideshowDecodeBudget.plan(options.outWidth, options.outHeight, 512, 512L * 512) ?: return null
    val orientation = thumbnailOrientation(input)
    options.inJustDecodeBounds = false; options.inSampleSize = plan.sampleSize
    options.inPreferredConfig = Bitmap.Config.RGB_565; options.inScaled = false
    input.channel.position(0)
    val sampled = BitmapFactory.decodeFileDescriptor(input.fd, null, options) ?: return null
    var upright: Bitmap? = null
    return try {
      if (!SlideshowDecodeBudget.fits(sampled.width, sampled.height, 512, 512L * 512)) return null
      // Transform only the bounded decode, using the same EXIF mapping as the slideshow.
      val output = if (orientation == 1) sampled else {
        val transform = SlideshowDecodeBudget.exifTransform(orientation)
        val matrix = android.graphics.Matrix().apply { setValues(floatArrayOf(
            transform.xx.toFloat(), transform.xy.toFloat(), 0f,
            transform.yx.toFloat(), transform.yy.toFloat(), 0f, 0f, 0f, 1f)) }
        Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, false)
      }
      upright = output
      ByteArrayOutputStream().use { out ->
        if (!output.compress(Bitmap.CompressFormat.JPEG, 75, out)) null else out.toByteArray()
      }
    } finally {
      upright?.takeIf { it !== sampled }?.recycle()
      sampled.recycle()
    }
  }
  /** Only for an inactive caller-owned generated fixture; normal deletes invalidate all tickets. */
  fun discardThumbnail(file: File): Boolean = thumbnails.discard(thumbnailKey(file.canonicalFile))
  private fun clearCache() { thumbnails.clear() }
  private fun error(status: Int, code: String) = json(status, JSONObject().put("ok", false).put("error", code))
  private fun json(status: Int, body: JSONObject) = FleetHttpServer.Response(status, body.toString())
}
