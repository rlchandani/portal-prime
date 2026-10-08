/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Entirely generated fixtures: no household media, downloads or detector accuracy claims. */
internal class SlideshowDeviceFixtures(val root: File) {
  val photos = File(root, "photos")
  val pair = File(root, "pair")
  val single = File(root, "single")
  val exif = File(root, "exif")
  val video = File(root, "video")
  val videoError = File(root, "video-error")
  val licensed = File(root, "licensed")
  val huge = File(root, "huge-4000x3000.png")
  val tall = File(root, "tall-1000x2200.png")
  val corrupt = File(root, "corrupt.jpg")
  val oversized = File(root, "oversized.jpg")
  val orientations get() = (1..8).map { File(exif, "orientation-$it.jpg") }

  fun create() {
    check(root.isDirectory)
    listOf(photos, pair, single, exif, video, videoError, licensed).forEach { check(it.mkdirs()) }
    draw(File(photos, "01-portrait.jpg"), 600, 1000, false, Bitmap.CompressFormat.JPEG)
    draw(File(photos, "02-portrait.png"), 720, 1080, false, Bitmap.CompressFormat.PNG)
    draw(File(photos, "03-group.jpg"), 1500, 900, true, Bitmap.CompressFormat.JPEG)
    draw(File(photos, "04-panorama.png"), 1800, 400, true, Bitmap.CompressFormat.PNG)
    File(photos, "01-portrait.jpg").copyTo(File(pair, "01-portrait.jpg"))
    File(photos, "02-portrait.png").copyTo(File(pair, "02-portrait.png"))
    File(photos, "01-portrait.jpg").copyTo(File(single, "01-portrait.jpg"))
    writeLargePng(huge, 4000, 3000)
    writeLargePng(tall, 1000, 2200)
    corrupt.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 7, 19, 0))
    corrupt.copyTo(File(photos, "05-corrupt.jpg"))
    RandomAccessFile(oversized, "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
    val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(bitmap)
      val paint = Paint()
      val colors = intArrayOf(RED, GREEN, BLUE, YELLOW)
      val rectangles = arrayOf(floatArrayOf(0f, 0f, 300f, 200f),
          floatArrayOf(300f, 0f, 600f, 200f), floatArrayOf(300f, 200f, 600f, 400f),
          floatArrayOf(0f, 200f, 300f, 400f))
      rectangles.forEachIndexed { i, r ->
        paint.color = colors[i]
        canvas.drawRect(r[0], r[1], r[2], r[3], paint)
      }
      val base = File(root, "corners.jpg")
      base.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
      orientations.forEachIndexed { i, file ->
        base.copyTo(file)
        ExifInterface(file.path).apply {
          setAttribute(ExifInterface.TAG_ORIENTATION, (i + 1).toString())
          saveAttributes()
        }
      }
    } finally { bitmap.recycle() }
  }

  fun installVideo(testContext: Context) {
    val encoded = testContext.assets.open("slideshow/synthetic-clip.mp4").use { it.readBytes() }
    check(encoded.size in 1..512 * 1024)
    File(photos, "01-portrait.jpg").copyTo(File(video, "01-portrait.jpg"))
    File(video, "02-synthetic.mp4").writeBytes(encoded)
    File(photos, "01-portrait.jpg").copyTo(File(videoError, "01-portrait.jpg"))
    File(videoError, "02-corrupt.mp4").writeBytes(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0))
    File(photos, "02-portrait.png").copyTo(File(videoError, "03-portrait.png"))
    testContext.assets.open("slideshow/astronaut.png").use { input ->
      File(licensed, "01-nasa-astronaut.png").outputStream().use { check(input.copyTo(it) <= 2 * 1024 * 1024) }
    }
    File(licensed, "01-nasa-astronaut.png").copyTo(File(photos, "06-nasa-astronaut.png"))
  }

  private fun draw(file: File, width: Int, height: Int, group: Boolean, format: Bitmap.CompressFormat) {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(bitmap)
      canvas.drawColor(Color.rgb(35, 68, 82))
      val paint = Paint(Paint.ANTI_ALIAS_FLAG)
      paint.color = Color.rgb(210, 180, 110)
      canvas.drawRect(0f, height * .7f, width.toFloat(), height.toFloat(), paint)
      val count = if (group) 4 else 1
      repeat(count) { index ->
        val x = width * (index + 1f) / (count + 1)
        val radius = minOf(width / (count + 2f), height * .14f)
        paint.color = Color.rgb(220, 150 + index * 15, 130)
        canvas.drawCircle(x, height * .3f, radius, paint)
        paint.color = Color.rgb(70 + index * 35, 105, 145)
        canvas.drawRect(x - radius, height * .45f, x + radius, height * .8f, paint)
      }
      file.outputStream().use { check(bitmap.compress(format, 95, it)) }
    } finally { bitmap.recycle() }
  }

  /** Build a real 12 MP PNG with a single scanline buffer, without a 48 MiB fixture bitmap. */
  private fun writeLargePng(file: File, width: Int, height: Int) {
    val compressed = ByteArrayOutputStream()
    DeflaterOutputStream(compressed).use { deflater ->
      val row = ByteArray(1 + width * 4)
      for (y in 0 until height) {
        for (x in 0 until width) {
          val offset = 1 + x * 4
          row[offset] = (if (x < width / 2) 210 else 35).toByte()
          row[offset + 1] = (if (y < height / 2) 180 else 65).toByte()
          row[offset + 2] = 90
          row[offset + 3] = 0xff.toByte()
        }
        deflater.write(row)
      }
    }
    DataOutputStream(file.outputStream()).use { output ->
      output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
      fun chunk(type: String, bytes: ByteArray) {
        val label = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(bytes.size); output.write(label); output.write(bytes)
        val crc = CRC32().apply { update(label); update(bytes) }
        output.writeInt(crc.value.toInt())
      }
      val header = ByteArrayOutputStream()
      DataOutputStream(header).use {
        it.writeInt(width); it.writeInt(height); it.writeByte(8); it.writeByte(6)
        it.writeByte(0); it.writeByte(0); it.writeByte(0)
      }
      chunk("IHDR", header.toByteArray())
      chunk("IDAT", compressed.toByteArray())
      chunk("IEND", byteArrayOf())
    }
  }

  companion object {
    val RED = Color.rgb(229, 57, 53)
    val GREEN = Color.rgb(67, 160, 71)
    val BLUE = Color.rgb(30, 136, 229)
    val YELLOW = Color.rgb(253, 216, 53)
  }
}
