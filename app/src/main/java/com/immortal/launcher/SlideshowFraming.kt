/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject

internal data class SlideshowAdjustment(
    val zoom: Float = 1f, val focusX: Float = .5f, val focusY: Float = .5f,
) {
  fun transform(w: Int, h: Int, vw: Int, vh: Int): SlideshowGeometry.Transform {
    val safe = validated(zoom, focusX, focusY) ?: SlideshowAdjustment()
    val scale = minOf(vw.toFloat() / w.coerceAtLeast(1), vh.toFloat() / h.coerceAtLeast(1)) * safe.zoom
    fun position(view: Int, image: Float, focus: Float): Float =
        if (image <= view) (view - image) / 2 else (view / 2f - focus * image).coerceIn(view - image, 0f)
    return SlideshowGeometry.Transform(scale, position(vw, w * scale, safe.focusX),
        position(vh, h * scale, safe.focusY))
  }

  companion object {
    fun validated(zoom: Float, x: Float, y: Float): SlideshowAdjustment? =
        if (zoom.isFinite() && x.isFinite() && y.isFinite() &&
            zoom in 1f..3f && x in 0f..1f && y in 0f..1f) SlideshowAdjustment(zoom, x, y) else null
  }
}

/** Per-photo positions are a bounded metadata group, independent of global settings. */
internal class SlideshowFramingStore(directory: File) {
  private val file = File(directory, "slideshow_positions.json")

  fun get(assetKey: String): SlideshowAdjustment? = synchronized(lock) {
    val value = read().optJSONObject(hash(assetKey)) ?: return@synchronized null
    SlideshowAdjustment.validated(value.optDouble("zoom").toFloat(),
        value.optDouble("x").toFloat(), value.optDouble("y").toFloat())
  }

  fun save(assetKey: String, adjustment: SlideshowAdjustment?) = synchronized(lock) {
    val data = read()
    val key = hash(assetKey)
    if (adjustment == null) data.remove(key)
    else {
      require(SlideshowAdjustment.validated(adjustment.zoom, adjustment.focusX, adjustment.focusY) != null)
      data.put(key, JSONObject().put("zoom", adjustment.zoom).put("x", adjustment.focusX)
          .put("y", adjustment.focusY).put("saved", System.currentTimeMillis()))
    }
    while (data.length() > 256) {
      val oldest = data.keys().asSequence().minByOrNull {
        data.optJSONObject(it)?.optLong("saved") ?: 0L
      } ?: break
      data.remove(oldest)
    }
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    FileOutputStream(temp).use {
      it.write(data.toString().toByteArray(Charsets.UTF_8))
      it.flush()
      it.fd.sync()
    }
    check(temp.renameTo(file)) { "Could not save photo position" }
  }

  private fun read(): JSONObject = runCatching {
    if (!file.isFile || file.length() > 128 * 1024) JSONObject() else JSONObject(file.readText())
  }.getOrElse { JSONObject() }

  private fun hash(key: String): String =
      MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it.toInt() and 255) }

  companion object { private val lock = Any() }
}
