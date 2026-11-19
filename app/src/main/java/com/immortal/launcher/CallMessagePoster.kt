/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * A message's sealed file as an [android.media.MediaDataSource]: every read opens the 64 KiB chunk
 * it falls in, in memory (the same [CallMessageCrypto.Reader] playback uses). Nothing is written.
 * MediaMetadataRetriever reads from its own thread, so reads are serialised.
 */
internal class SealedMessageSource(private val reader: CallMessageCrypto.Reader) : MediaDataSource() {
  override fun getSize(): Long = reader.plainBytes

  @Synchronized
  override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
    if (size == 0) return 0
    var done = 0
    while (done < size) {
      val n = reader.read(position + done, buffer, offset + done, size - done)
      if (n <= 0) break
      done += n
    }
    return if (done == 0) -1 else done
  }

  @Synchronized override fun close() = reader.close()
}

/** A tiny LRU with a byte budget (pure, unit tested): the posters kept in memory. */
internal class PosterCache<V : Any>(private val budgetBytes: Long, private val sizeOf: (V) -> Long) {
  private val map = LinkedHashMap<String, V>(16, 0.75f, true)
  private var used = 0L

  @Synchronized fun get(key: String): V? = map[key]

  @Synchronized fun put(key: String, value: V) {
    val size = sizeOf(value)
    if (size > budgetBytes) return
    map.remove(key)?.let { used -= sizeOf(it) }
    map[key] = value
    used += size
    val it = map.entries.iterator()
    while (used > budgetBytes && it.hasNext()) { val e = it.next(); used -= sizeOf(e.value); it.remove() }
  }

  @Synchronized fun remove(key: String) { map.remove(key)?.let { used -= sizeOf(it) } }
  @Synchronized fun clear() { map.clear(); used = 0L }
  @Synchronized fun bytes(): Long = used
  @Synchronized fun size(): Int = map.size
}

/**
 * Poster frames for video messages, without ever storing an unencrypted image: the first frame is
 * decoded at view time straight from the sealed file into a small bitmap held only in memory
 * ([PosterCache], ~2 MB). One decode at a time, off the main thread.
 */
internal object CallMessagePosters {
  private const val TAG = "HearthpaneCallMsg"
  /** Long edge of a poster, px: enough for the Messages preview card, tiny in memory (~330 KB). */
  const val LONG_EDGE_PX = 384
  const val CACHE_BYTES = 2L * 1024 * 1024

  private val cache = PosterCache<Bitmap>(CACHE_BYTES) { it.allocationByteCount.toLong() }
  @OptIn(ExperimentalCoroutinesApi::class)
  private val decoder = Dispatchers.IO.limitedParallelism(1)

  /** The size to decode a [w]×[h] video to: its long edge becomes [LONG_EDGE_PX], never upscaled. */
  fun target(w: Int, h: Int, longEdge: Int = LONG_EDGE_PX): Pair<Int, Int>? {
    if (w <= 0 || h <= 0) return null
    val scale = minOf(1f, longEdge.toFloat() / maxOf(w, h))
    return maxOf(1, Math.round(w * scale)) to maxOf(1, Math.round(h * scale))
  }

  fun cached(id: String): Bitmap? = cache.get(id)

  fun forget(id: String) = cache.remove(id)

  fun forgetAll() = cache.clear()

  /** For the probe: posters held in memory and their bytes. */
  fun stats(): Pair<Int, Long> = cache.size() to cache.bytes()

  suspend fun load(context: Context, m: CallMessage): Bitmap? {
    if (m.audioOnly) return null
    cache.get(m.id)?.let { return it }
    return withContext(decoder) { cache.get(m.id) ?: decode(context.applicationContext, m)?.also { cache.put(m.id, it) } }
  }

  private fun decode(c: Context, m: CallMessage): Bitmap? {
    val file = CallMessageStore.file(c, m.id)?.takeIf { it.exists() } ?: return null
    val started = android.os.SystemClock.elapsedRealtime()
    val retriever = MediaMetadataRetriever()
    var source: SealedMessageSource? = null
    return try {
      val key = CallMessageCrypto.restKey(CallMessageKeys.dataKey(c, CallMessageStore.dir(c)), m.id)
      source = SealedMessageSource(CallMessageCrypto.Reader(file, key, m.id))
      retriever.setDataSource(source)
      val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
      val (tw, th) = (if (rotation % 180 == 0) target(w, h) else target(h, w)) ?: return null
      val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
      } else {
        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
          Bitmap.createScaledBitmap(full, tw, th, true).also { if (it !== full) full.recycle() }
        }
      }
      Log.i(TAG, "poster ${m.id.take(4)}… ${frame?.width}x${frame?.height} in ${android.os.SystemClock.elapsedRealtime() - started} ms")
      frame
    } catch (e: Exception) {
      Log.w(TAG, "poster ${m.id.take(4)}… not decoded: ${e.javaClass.simpleName}")
      null
    } finally {
      runCatching { retriever.release() }
      runCatching { source?.close() }
    }
  }
}

/** The message's poster frame once decoded (null while decoding, for sound-only messages, or on failure). */
@Composable
internal fun rememberCallMessagePoster(m: CallMessage): State<ImageBitmap?> {
  val context = LocalContext.current
  return produceState(CallMessagePosters.cached(m.id)?.asImageBitmap(), m.id) {
    if (value == null) value = CallMessagePosters.load(context, m)?.asImageBitmap()
  }
}
