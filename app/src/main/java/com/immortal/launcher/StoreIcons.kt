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
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Small icon loader for the App Store: memory LRU + disk cache under
 * cacheDir/store_icons, decoded at ~128px. Catalog entries without an iconUrl
 * (some community submissions) simply get null — the UI draws a monogram tile.
 */
object StoreIcons {
  private const val MAX_ICON_BYTES = 2L * 1024 * 1024
  private val mem = LruCache<String, Bitmap>(64)
  private val io =
      ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(24))
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val lock = Any()
  private val pending = HashMap<String, Work>()

  class Request internal constructor(private val cancelAction: () -> Unit) {
    fun cancel() = cancelAction()
  }

  private class Interest(@Volatile var callback: ((Bitmap?) -> Unit)?) {
    fun deliver(bitmap: Bitmap?) { callback?.invoke(bitmap) }
  }

  private class Work(val pkg: String) {
    val interests = mutableSetOf<Interest>()
    lateinit var task: FutureTask<Unit>
    @Volatile var cancelled = false
    // Attached/detached under lock; each work owns only its own connection.
    var connection: HttpURLConnection? = null
  }

  fun cached(pkg: String): Bitmap? = mem.get(pkg)

  /** The last disposed card cancels both queued work and its active connection. */
  fun load(context: Context, app: CatalogApp, onLoaded: (Bitmap?) -> Unit): Request {
    val interest = Interest(onLoaded)
    val url = app.iconUrl
    if (url == null) {
      main.post { interest.deliver(null) }
      return Request { interest.callback = null }
    }
    synchronized(lock) {
      mem.get(app.packageName)?.let { bitmap ->
        main.post { interest.deliver(bitmap) }
        return Request { interest.callback = null }
      }
      val existing = pending[app.packageName]
      val work = existing ?: Work(app.packageName)
      work.interests += interest
      if (existing == null) {
        val appCtx = context.applicationContext
        pending[work.pkg] = work
        work.task = FutureTask {
          val bitmap = runCatching { readIcon(appCtx, work, url) }.getOrNull()
          finish(work, bitmap)
        }
        try {
          io.execute(work.task)
        } catch (_: RejectedExecutionException) {
          // A saturated queue keeps the monogram; never run network IO on the caller.
          finish(work, null)
        }
      }
      return Request {
        interest.callback = null
        val connection = synchronized(lock) {
          work.interests -= interest
          if (work.interests.isEmpty() && pending[work.pkg] === work) {
            work.cancelled = true
            pending.remove(work.pkg)
            io.remove(work.task)
            work.task.cancel(true)
            work.connection.also { work.connection = null }
          } else null
        }
        // Closing a socket must never hold the shared pending/cache lock.
        runCatching { connection?.disconnect() }
      }
    }
  }

  private fun finish(work: Work, bitmap: Bitmap?) {
    val interests = synchronized(lock) {
      if (work.cancelled || pending[work.pkg] !== work) {
        work.interests.clear()
        return
      }
      if (bitmap != null) mem.put(work.pkg, bitmap)
      pending.remove(work.pkg)
      work.interests.toList().also { work.interests.clear() }
    }
    main.post { interests.forEach { it.deliver(bitmap) } }
  }

  private fun checkActive(work: Work) {
    if (work.cancelled || Thread.currentThread().isInterrupted)
      throw InterruptedIOException("Icon request cancelled")
  }

  /** Called under lock before touching shared state or the final cache file. */
  private fun checkCurrent(work: Work) {
    checkActive(work)
    if (pending[work.pkg] !== work) throw InterruptedIOException("Icon request replaced")
  }

  private fun readIcon(context: Context, work: Work, url: String): Bitmap? {
    checkActive(work)
    val directory = File(context.cacheDir, "store_icons")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Icon cache unavailable")
    val file = File(directory, "${work.pkg}.png")
    if (file.length() in 1..MAX_ICON_BYTES) {
      decodeAt(file, 128, work)?.let { return it }
    }
    synchronized(lock) {
      checkCurrent(work)
      file.delete()
    }

    val temporary = File.createTempFile("icon-", ".tmp", directory)
    var connection: HttpURLConnection? = null
    try {
      val c = URL(url).openConnection() as HttpURLConnection
      connection = c
      synchronized(lock) {
        checkCurrent(work)
        work.connection = c
      }
      c.connectTimeout = 8000
      c.readTimeout = 15000
      c.instanceFollowRedirects = true
      c.setRequestProperty("User-Agent", "PortalStore/1.0")
      checkActive(work)
      if (c.responseCode !in 200..299) throw IOException("Icon download failed")
      checkActive(work)
      if (c.contentLengthLong > MAX_ICON_BYTES) throw IOException("Icon exceeds size limit")
      c.inputStream.use { input ->
        temporary.outputStream().use { output ->
          val buffer = ByteArray(8192)
          var total = 0L
          while (true) {
            checkActive(work)
            val count = input.read(buffer)
            checkActive(work)
            if (count < 0) break
            total += count
            if (total > MAX_ICON_BYTES) throw IOException("Icon exceeds size limit")
            output.write(buffer, 0, count)
          }
        }
      }
      val bitmap = decodeAt(temporary, 128, work) ?: throw IOException("Invalid icon image")
      // Same-directory rename publishes only a complete, validated image.
      synchronized(lock) {
        checkCurrent(work)
        if (!temporary.renameTo(file)) throw IOException("Icon cache write failed")
      }
      return bitmap
    } finally {
      synchronized(lock) {
        if (work.connection === connection) work.connection = null
      }
      runCatching { connection?.disconnect() }
      temporary.delete()
    }
  }

  /** Decode [f] downsampled to roughly [px] on its longest side. */
  private fun decodeAt(f: File, px: Int, work: Work): Bitmap? {
    checkActive(work)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, bounds)
    checkActive(work)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
    checkActive(work)
    val bitmap = BitmapFactory.decodeFile(
        f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    checkActive(work)
    return bitmap
  }
}
