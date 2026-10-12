/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.OnResume
import com.immortal.launcher.ui.components.launchSafely
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val HOME_PHOTO_CACHE_MS = 30 * 60_000L

/** Three short recovery attempts, then one check per cache period until success or resume. */
private fun homePhotoRetryDelay(failures: Int): Long = when (failures) {
  1 -> 60_000L
  2 -> 2 * 60_000L
  3 -> 5 * 60_000L
  else -> HOME_PHOTO_CACHE_MS
}

/** Small process cache prevents decoding the hero again when navigating between Home and Apps. */
private object ApprovedHomePhotoCache {
  data class Snapshot(val bitmap: Bitmap, val refreshAfterMs: Long)
  private var config: ScreensaverConfig.Settings? = null
  private var image: Bitmap? = null
  private var loadedAt = 0L

  @Synchronized fun get(settings: ScreensaverConfig.Settings): Snapshot? {
    val bitmap = image ?: return null
    val remaining = HOME_PHOTO_CACHE_MS - (SystemClock.elapsedRealtime() - loadedAt)
    return if (config == settings && remaining > 0) Snapshot(bitmap, remaining) else null
  }

  @Synchronized fun put(settings: ScreensaverConfig.Settings, bitmap: Bitmap) {
    config = settings
    image = bitmap
    loadedAt = SystemClock.elapsedRealtime()
  }
}

@Composable
internal fun ApprovedHomePhoto(modifier: Modifier = Modifier, onEnterEdit: (() -> Unit)? = null) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  var settings by remember { mutableStateOf(ScreensaverConfig.load(context)) }
  OnResume { settings = ScreensaverConfig.load(context) }
  val sourceSettings = settings
  val loadingContext = context.applicationContext
  // A source change gets its own image state; failed refreshes of that source retain its image.
  val photo by key(sourceSettings, owner) {
    produceState<Bitmap?>(ApprovedHomePhotoCache.get(sourceSettings)?.bitmap) {
      owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        if (sourceSettings.usesWebUrl) return@repeatOnLifecycle
        var failures = 0
        while (currentCoroutineContext().isActive) {
          // Cancellation is checked before the shared cache write as well as UI publication.
          val result = PhotoSourceWork.cachedOrLoad({ ApprovedHomePhotoCache.get(sourceSettings) }) {
            PhotoSourceWork.checkpoint()
            ApprovedHomePhotoCache.get(sourceSettings) ?: loadApprovedHomePhoto(loadingContext, sourceSettings)?.let { bitmap ->
              PhotoSourceWork.checkpoint()
              ApprovedHomePhotoCache.put(sourceSettings, bitmap)
              ApprovedHomePhotoCache.Snapshot(bitmap, HOME_PHOTO_CACHE_MS)
            }
          }
          if (result != null) {
            value = result.bitmap
            failures = 0
          } else failures = (failures + 1).coerceAtMost(4)
          // One deadline timer, no animation/frame polling. Stop cancels it; resume checks now.
          delay(result?.refreshAfterMs?.coerceAtLeast(1_000L) ?: homePhotoRetryDelay(failures))
        }
      }
    }
  }
  val bitmap = remember(photo) { photo?.asImageBitmap() }
  Box(modifier.tvFocusable(focusScale = 1f, onLongClick = onEnterEdit) {
    context.launchSafely(PhotoFramePreviewActivity::class.java)
  }) {
    bitmap?.let {
      Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Transparent, .4f to Color.Transparent, 1f to Color(0xF2061B1C))))
    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 36.dp, vertical = 34.dp)) {
      Text("Photo memory", color = Color(0xFFF4F7F9), fontSize = 36.sp, lineHeight = 46.sp,
          fontWeight = FontWeight.Medium)
      Text(
          when {
            settings.usesWebUrl -> "Open your selected web page"
            bitmap == null -> "Open your photos"
            else -> "Your selected photo source"
          },
          color = Color(0xFFE2EBEB), fontSize = 22.sp, lineHeight = 30.sp,
          modifier = Modifier.padding(top = 12.dp))
    }
  }
}

private fun loadApprovedHomePhoto(context: Context, settings: ScreensaverConfig.Settings): Bitmap? =
    runCatching {
      when {
        settings.usesFolder -> settings.folderPath?.let { folder ->
          if (!LocalMedia.isAccessible(folder)) null else
            LocalMedia.enumerate(folder, includeVideo = false, max = 60).firstOrNull { !it.isVideo }?.let {
              decodeHomePhoto { File(it.path).inputStream() }
            }
        }
        settings.usesSmb -> {
          val smb = SmbSource(settings.smbHost.orEmpty(), settings.smbShare.orEmpty(),
              settings.smbPath.orEmpty(), settings.smbUser.orEmpty(), settings.smbPass.orEmpty())
          try {
            PhotoSourceWork.onClose { smb.close() }
            if (!smb.connect()) null else smb.listImages(cap = 60).firstOrNull()?.let { path ->
              decodeHomePhoto { smb.openStream(path) }
            }
          } finally { smb.close() }
        }
        settings.usesImmich -> {
          val url = ImmichSource.listMedia(settings.immichUrl.orEmpty(), settings.immichKey.orEmpty(),
              settings.immichAlbumId, includeVideo = false, cap = 60)?.firstOrNull()?.url
          url?.let { remoteHomePhoto(context, it, ImmichSource.authHeaders(settings.immichKey.orEmpty())) }
        }
        settings.usesDav -> {
          val url = DavSource.listImageUrls(settings.davUrl.orEmpty(), settings.davUser, settings.davPass, cap = 60)
              ?.firstOrNull()
          url?.let { remoteHomePhoto(context, it, DavSource.authHeaders(settings.davUser, settings.davPass)) }
        }
        settings.usesUrl -> RemoteAlbum.fetch(settings.albumUrl.orEmpty())?.let { album ->
          album.photoUrls.firstOrNull()?.let { remoteHomePhoto(context, it, album.headers) }
        }
        settings.usesWebUrl -> null
        else -> decodeHomePhoto { context.assets.open("photoframe_fallback/03_yosemite.jpg") }
      }
    }.getOrNull()

private fun remoteHomePhoto(context: Context, url: String, headers: Map<String, String>): Bitmap? {
  val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
  val cached = File(context.filesDir, "${MediaCache.DIR}/$hash.jpg")
  if (cached.isFile) return decodeHomePhoto { cached.inputStream() }
  val connection = URL(url).openPhotoSourceConnection()
  return try {
    connection.connectTimeout = 8_000
    connection.readTimeout = 12_000
    headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
    if (connection.responseCode !in 200..299) null else {
      // Bound the transfer and memory before decoding; Home never encodes or writes an image.
      val bytes = connection.inputStream.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
          PhotoSourceWork.checkpoint()
          val count = stream.read(buffer)
          if (count < 0) break
          if (output.size() + count > 16 * 1024 * 1024) return null
          output.write(buffer, 0, count)
        }
        output.toByteArray()
      }
      decodeHomePhoto { bytes.inputStream() }
    }
  } finally { connection.disconnect() }
}

/** Bounds pass and sampled decode both run on IO; the UI only receives a display-sized bitmap. */
private fun decodeHomePhoto(open: () -> InputStream?): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  val stream = open() ?: return null
  stream.use { BitmapFactory.decodeStream(it, null, bounds) }
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  // Portal+ hero is roughly 990×709 physical pixels. Leave modest crop headroom,
  // while bounding a square ARGB decode to 5.5 MiB instead of the old 9.8 MiB.
  while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
  val bitmap = open()?.use {
    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
  } ?: return null
  // EXIF is read from the source stream on IO, including mirrored phone-camera images.
  val orientation = runCatching {
    open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
  }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
  val matrix = Matrix()
  when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
    ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
    ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.preScale(-1f, 1f) }
    else -> return bitmap
  }
  return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
    if (it !== bitmap) bitmap.recycle()
  }
}
