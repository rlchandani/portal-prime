/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log

internal data class SlideshowAsset(
    val key: String,
    val decode: (Int) -> Bitmap?,
    val portrait: (() -> Boolean)? = null,
    val revisionVerified: Boolean = false,
    val contentIdentity: Boolean = false,
    val identity: ((Bitmap) -> String?)? = null,
)

/** Owns acquired-but-not-displayed bitmaps. Displayed bitmaps belong to the stage. */
internal class SlideshowLoader(
    context: Context,
    private val budget: SlideshowPixelBudget = SlideshowPixelBudget(),
    private val onCancelTransports: () -> Unit = {},
) : AutoCloseable {
  private val work = SlideshowWork()
  private val ui = Handler(Looper.getMainLooper())
  private val faceDelegate = lazy { SlideshowFaceStore(context) }
  private val positions = SlideshowFramingStore(context.filesDir)
  private data class Warm(val key: String, val bitmap: Bitmap, val identity: String?)
  private var lookahead: Warm? = null
  private data class Ready(
      val ticket: Int, val photos: List<SlideshowPhoto>, val callback: (List<SlideshowPhoto>, String?) -> Unit,
      val companion: String?,
  )
  private var ready: Ready? = null
  private var deliveryPosted = false
  @Volatile private var closed = false
  @Volatile private var generation = 0

  fun load(
      asset: SlideshowAsset, partners: List<SlideshowAsset>, next: SlideshowAsset?,
      pair: Boolean, people: Boolean,
      onReady: (List<SlideshowPhoto>, String?) -> Unit,
      onFailure: () -> Unit,
      nextAfterPartner: ((String) -> SlideshowAsset?)? = null,
  ) {
    onCancelTransports()
    val ticket = ++generation
    work.submit { current ->
      val owned = ArrayList<Pair<SlideshowAsset, Bitmap>>()
      val identities = HashMap<String, String?>()
      var delivered = false
      try {
        fun entries(extra: Bitmap? = null) =
            (owned.map { it.second } + listOfNotNull(extra)).map {
              SlideshowPixelBudget.Entry(it, it.allocationByteCount.toLong())
            }
        fun acquire(candidate: SlideshowAsset, maxEdge: Int, shrinkPortrait: Boolean = false): Bitmap? {
          if (!current() || closed) return null
          val warm = synchronized(this) {
            val found = lookahead
            lookahead = null
            val bitmap = if (found?.key == candidate.key) {
              identities[candidate.key] = found.identity
              found.bitmap
            } else {
              found?.bitmap?.recycle()
              null
            }
            budget.setOwner("preparation", entries(bitmap))
            budget.setOwner("lookahead", emptyList())
            bitmap
          }
          // Decoder sampling plus EXIF output can hold two bounded 12 MiB images.
          val scratch = if (warm == null) 24L * 1024 * 1024 else 12L * 1024 * 1024
          val needsShrink = warm != null && (maxOf(warm.width, warm.height) > maxEdge ||
              (shrinkPortrait && warm.height > warm.width && warm.height > 1400))
          if (warm != null && !needsShrink) return warm
          val token = budget.reserve(scratch) { current() && !closed }
          if (token == null) {
            warm?.recycle()
            budget.setOwner("preparation", entries())
            return null
          }
          try {
            var bitmap = warm ?: candidate.decode(maxEdge) ?: return null
            // Capture encoded revision before any presentation-specific resizing.
            if (warm == null) identities[candidate.key] =
                candidate.identity?.invoke(bitmap) ?: if (candidate.contentIdentity)
                  analysisKey(candidate, bitmap) else null
            val edge = if (shrinkPortrait && bitmap.height > bitmap.width) 1400 else maxEdge
            if (maxOf(bitmap.width, bitmap.height) > edge) {
              val scale = edge.toFloat() / maxOf(bitmap.width, bitmap.height)
              val smaller = Bitmap.createScaledBitmap(bitmap,
                  (bitmap.width * scale).toInt().coerceAtLeast(1),
                  (bitmap.height * scale).toInt().coerceAtLeast(1), true)
              if (smaller !== bitmap) bitmap.recycle()
              bitmap = smaller
            }
            if (!current() || closed) { bitmap.recycle(); return null }
            budget.commit(token, "preparation", entries(bitmap))
            return bitmap
          } finally { budget.release(token) }
        }
        val first = acquire(asset, 2400, shrinkPortrait = pair)
        if (first == null) {
          ui.post { if (!closed && ticket == generation) onFailure() }
          return@submit
        }
        owned.add(asset to first)
        if (pair && first.height > first.width && current()) {
          for (partner in partners.take(4)) {
            if (partner.key == asset.key || partner.portrait?.invoke() == false) continue
            val bitmap = acquire(partner, 1400) ?: continue
            val duplicate = identities[asset.key]?.let { it == identities[partner.key] } == true
            if (bitmap.height > bitmap.width && !duplicate) { owned.add(partner to bitmap); break }
            bitmap.recycle()
            budget.setOwner("preparation", entries())
          }
        }
        if (!current()) return@submit
        val analysisKeys = owned.associate { (item, bitmap) ->
          item.key to if (people || item.contentIdentity)
            identities[item.key] ?: analysisKey(item, bitmap) else null
        }
        val prepared = owned.map { (item, bitmap) ->
          bitmap.prepareToDraw()
          val revisionKey = analysisKeys[item.key]
          val regions = if (people && revisionKey != null)
            faceDelegate.value.regionsFor(revisionKey).orEmpty() else emptyList()
          val identity = if (item.contentIdentity) revisionKey ?: item.key else item.key
          SlideshowPhoto(identity, bitmap, regions, positions.get(identity), item.key)
        }
        // Copy analysis derivatives before publishing: an obsolete UI runnable can recycle
        // a never-displayed scene, so the worker must not read those bitmaps afterwards.
        if (people) owned.forEach { (item, bitmap) ->
          val revisionKey = analysisKeys[item.key]
          if (current() && revisionKey != null) faceDelegate.value.analyzeLater(revisionKey, bitmap)
        }
        // Keep ownership until this runnable either hands the scene to the stage or discards it.
        delivered = true
        publish(Ready(ticket, prepared, onReady, owned.getOrNull(1)?.first?.key))
        budget.setOwner("preparation", emptyList())
        val used = owned.map { it.first.key }.toSet()
        val upcoming = owned.getOrNull(1)?.first?.key?.let { nextAfterPartner?.invoke(it) } ?: next
        if (upcoming != null && upcoming.key !in used && current()) {
          val token = budget.reserve(24L * 1024 * 1024) { current() && !closed }
              ?: return@submit
          try {
            val bitmap = upcoming.decode(2400)
            if (bitmap != null) {
              if (!current() || closed) bitmap.recycle()
              else {
                val identity = upcoming.identity?.invoke(bitmap) ?:
                    if (people || upcoming.contentIdentity) analysisKey(upcoming, bitmap) else null
                if (people && identity != null) faceDelegate.value.analyzeLater(identity, bitmap)
                bitmap.prepareToDraw()
                synchronized(this) {
                  if (closed || !current()) bitmap.recycle()
                  else {
                    lookahead?.bitmap?.recycle()
                    lookahead = Warm(upcoming.key, bitmap, identity)
                    budget.commit(token, "lookahead", listOf(
                        SlideshowPixelBudget.Entry(bitmap, bitmap.allocationByteCount.toLong())))
                  }
                }
              }
            }
          } finally { budget.release(token) }
        }
      } catch (error: Exception) {
        if (current() && !closed) {
          Log.w("ImmortalSlideshow", "Image preparation failed: ${error.javaClass.simpleName}")
          if (!delivered) ui.post { if (!closed && ticket == generation) onFailure() }
        }
      } finally {
        if (!delivered) owned.forEach { (_, bitmap) -> if (!bitmap.isRecycled) bitmap.recycle() }
        budget.setOwner("preparation", emptyList())
        if (closed && faceDelegate.isInitialized()) faceDelegate.value.close()
      }
    }
  }

  private fun publish(next: Ready) = synchronized(this) {
    if (closed || next.ticket != generation) {
      next.photos.forEach { it.bitmap.recycle() }
      return@synchronized
    }
    ready?.photos?.forEach { it.bitmap.recycle() }
    ready = next
    budget.setOwner("ready", next.photos.map {
      SlideshowPixelBudget.Entry(it.bitmap, it.bitmap.allocationByteCount.toLong())
    })
    if (!deliveryPosted) {
      deliveryPosted = true
      ui.post {
        val prepared = synchronized(this) {
          deliveryPosted = false
          ready.also {
            if (it != null) budget.setOwner("delivery", it.photos.map { photo ->
              SlideshowPixelBudget.Entry(photo.bitmap, photo.bitmap.allocationByteCount.toLong())
            })
            ready = null
            budget.setOwner("ready", emptyList())
          }
        } ?: return@post
        try {
          if (closed || prepared.ticket != generation) prepared.photos.forEach { it.bitmap.recycle() }
          else prepared.callback(prepared.photos, prepared.companion)
        } finally { budget.setOwner("delivery", emptyList()) }
      }
    }
  }

  /** Servers may overwrite a stable URL. Hash bounded upright pixels when no validator exists. */
  private fun analysisKey(asset: SlideshowAsset, bitmap: Bitmap): String? {
    if (asset.revisionVerified) return asset.key
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val row = IntArray(bitmap.width)
    val bytes = java.nio.ByteBuffer.allocate(bitmap.width * 4)
    for (y in 0 until bitmap.height) {
      if (Thread.currentThread().isInterrupted) return null
      bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
      bytes.clear()
      bytes.asIntBuffer().put(row)
      digest.update(bytes.array())
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    val prefix = if (asset.contentIdentity) "pixels" else asset.key
    return "$prefix:${bitmap.width}x${bitmap.height}:$hash"
  }

  fun savePosition(key: String, adjustment: SlideshowAdjustment?, onSaved: (Boolean) -> Unit) {
    // Order writes across viewer sessions without tying user saves to image cancellation.
    try {
      saves.execute {
        val saved = runCatching { positions.save(key, adjustment) }.isSuccess
        ui.post { if (!closed) onSaved(saved) }
      }
    } catch (_: java.util.concurrent.RejectedExecutionException) {
      ui.post { if (!closed) onSaved(false) }
    }
  }

  /** Memory-only: revisiting default-feed history may now have completed analysis. */
  fun cachedRegions(key: String): List<SlideshowGeometry.Region>? =
      if (!closed && faceDelegate.isInitialized()) faceDelegate.value.regionsFor(key) else null

  fun cancel() {
    onCancelTransports()
    generation++
    work.submit { }
    synchronized(this) {
      ready?.photos?.forEach { it.bitmap.recycle() }; ready = null
      budget.setOwner("ready", emptyList())
    }
  }

  override fun close() {
    closed = true
    generation++
    onCancelTransports()
    work.close()
    if (faceDelegate.isInitialized()) faceDelegate.value.close()
    synchronized(this) {
      lookahead?.bitmap?.recycle(); lookahead = null
      ready?.photos?.forEach { it.bitmap.recycle() }; ready = null
      budget.setOwner("lookahead", emptyList())
      budget.setOwner("ready", emptyList())
    }
    // Do not remove readiness runnables: they release a scene that was never displayed.
  }

  private companion object {
    val saves = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(16),
        { action -> Thread(action, "slideshow-position").apply {
          isDaemon = true
          priority = Thread.NORM_PRIORITY - 1
        } }).apply { allowCoreThreadTimeOut(true) }
  }
}
