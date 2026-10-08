/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Known licensed image only: distinguish copying, SDK mode, and platform compatibility. */
internal object SlideshowFaceDeviceProbe {
  fun run(instrumentation: Instrumentation): Bundle {
    val report = JSONObject()
    val bytes = instrumentation.context.assets.open("slideshow/astronaut.png").use { it.readBytes() }
    val original = checkNotNull(SlideshowDecoder.bytes(bytes))
    val copied = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
    Canvas(copied).drawBitmap(original, null, Rect(0, 0, copied.width, copied.height),
        Paint(Paint.FILTER_BITMAP_FLAG))
    report.put("copiedPixelsMatch", digest(original).contentEquals(digest(copied)))
    report.put("encodedSha256", MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) })
    original.recycle()
    val blank = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }
    try {
      report.put("fast", ml(copied, FaceDetectorOptions.PERFORMANCE_MODE_FAST))
      report.put("accurate", ml(copied, FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE))
      report.put("fastBlank", ml(blank, FaceDetectorOptions.PERFORMANCE_MODE_FAST))
      report.put("accurateBlank", ml(blank, FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE))
      report.put("platform", platform(copied))
      report.put("platformBlank", platform(blank))
    } finally { copied.recycle(); blank.recycle() }
    return Bundle().apply {
      putBoolean("faceProbeCompleted", true)
      putString("faceProbeReport", report.toString())
    }
  }

  private fun ml(source: Bitmap, mode: Int): JSONObject {
    val begin = SystemClock.elapsedRealtime()
    val result = JSONObject()
    val input = source.copy(Bitmap.Config.ARGB_8888, false)
    val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(mode)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(.06f).build())
    val task = detector.process(InputImage.fromBitmap(input, 0))
    try {
      val faces = Tasks.await(task, 8, TimeUnit.SECONDS)
      result.put("facesCount", faces.size)
      result.put("positiveBoxes", faces.all { it.boundingBox.width() > 0 && it.boundingBox.height() > 0 })
    } catch (error: Exception) {
      result.put("errorType", error.javaClass.simpleName)
    } finally {
      if (task.isComplete) { detector.close(); input.recycle() }
      else task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) {
        detector.close(); input.recycle()
      }
    }
    return result.put("elapsedMs", SystemClock.elapsedRealtime() - begin)
  }

  private fun platform(source: Bitmap): JSONObject {
    val begin = SystemClock.elapsedRealtime()
    val input = source.copy(Bitmap.Config.RGB_565, false)
    return try {
      val faces = arrayOfNulls<android.media.FaceDetector.Face>(8)
      val found = android.media.FaceDetector(input.width, input.height, 8).findFaces(input, faces)
      JSONObject().put("facesCount", found)
          .put("credibleCount", faces.take(found).count { (it?.confidence() ?: 0f) >= .4f })
          .put("elapsedMs", SystemClock.elapsedRealtime() - begin)
    } catch (error: Exception) {
      JSONObject().put("errorType", error.javaClass.simpleName)
    } finally { input.recycle() }
  }

  private fun digest(bitmap: Bitmap): ByteArray {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val buffer = java.nio.ByteBuffer.allocate(pixels.size * 4)
    buffer.asIntBuffer().put(pixels)
    return MessageDigest.getInstance("SHA-256").digest(buffer.array())
  }
}
