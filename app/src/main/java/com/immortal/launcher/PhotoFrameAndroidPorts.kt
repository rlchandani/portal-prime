/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/** The photo frame's ports on a real Portal: each method is one platform call. */
internal class PhotoFrameAndroidPorts(private val context: Context) :
    PhotoFrameClock, PhotoFramePrefs, PhotoFrameDecoder<Bitmap>, PhotoFramePresence {
  override fun wallMs(): Long = System.currentTimeMillis()
  override fun uptimeMs(): Long = SystemClock.uptimeMillis()

  override fun household(): ScreensaverConfig.Settings = GuestPolicy.screensaver(ScreensaverConfig.load(context))
  override fun saved(): ScreensaverConfig.Settings = ScreensaverConfig.load(context)
  override fun testSourceActive(): Boolean = ScreensaverTestSource.active(context)
  override fun testSourcePin(): ScreensaverTestSource.Pin? = ScreensaverTestSource.pin(context)
  override fun testSourceForcedNight(): Boolean = ScreensaverTestSource.forcedNight(context)

  override fun bounds(path: String): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(path, bounds) }
    return if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else bounds.outWidth to bounds.outHeight
  }

  override fun orientation(path: String): Int = runCatching {
    ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
  }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

  override fun file(path: String, maxEdge: Int): Bitmap? = SlideshowDecoder.file(path, maxEdge)
  override fun stream(input: InputStream, maxEdge: Int): Bitmap? = SlideshowDecoder.stream(input, maxEdge)
  override fun readEncoded(input: InputStream): ByteArray? = SlideshowDecoder.readEncoded(input)
  override fun bytes(bytes: ByteArray, maxEdge: Int): Bitmap? = SlideshowDecoder.bytes(bytes, maxEdge)

  override fun watching(): Boolean = PresenceHub.current.presence == Presence.PRESENT
  override fun hidesPhotos(): Boolean = GuestMode.hides(GuestContent.PHOTOS)
  override fun touched(wallMs: Long) { DreamPolicy.userExitAt = wallMs }
}
