/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File

/**
 * Plays a stored message straight from its sealed file: every read opens the 64 KiB chunk it
 * falls in, in memory, so no plaintext ever touches the disk.
 */
@OptIn(UnstableApi::class)
internal class CallMessageDataSource(private val file: File, private val key: ByteArray, private val msgId: String) :
    BaseDataSource(false) {
  private var reader: CallMessageCrypto.Reader? = null
  private var position = 0L
  private var remaining = 0L
  private var uri: Uri? = null

  override fun open(dataSpec: DataSpec): Long {
    transferInitializing(dataSpec)
    val r = CallMessageCrypto.Reader(file, key, msgId)
    reader = r
    uri = dataSpec.uri
    position = dataSpec.position
    if (position > r.plainBytes) throw java.io.EOFException("seek past the end")
    remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) r.plainBytes - position else minOf(dataSpec.length, r.plainBytes - position)
    transferStarted(dataSpec)
    return remaining
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (remaining <= 0) return C.RESULT_END_OF_INPUT
    val n = requireNotNull(reader).read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
    if (n < 0) return C.RESULT_END_OF_INPUT
    position += n; remaining -= n
    bytesTransferred(n)
    return n
  }

  override fun getUri(): Uri? = uri

  override fun close() {
    uri = null
    reader?.close(); reader = null
    transferEnded()
  }
}

/** ExoPlayer for a stored message (sealed) or the sender's own take under review (its temp file). */
@OptIn(UnstableApi::class)
internal object CallMessagePlayers {
  fun stored(context: Context, m: CallMessage): ExoPlayer? {
    val c = context.applicationContext
    val file = CallMessageStore.file(c, m.id)?.takeIf { it.exists() } ?: return null
    val key = CallMessageCrypto.restKey(CallMessageKeys.dataKey(c, CallMessageStore.dir(c)), m.id)
    val factory = DataSource.Factory { CallMessageDataSource(file, key, m.id) }
    val source = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.parse("hpvmsg://${m.id}")))
    return ExoPlayer.Builder(c).build().apply { setMediaSource(source); prepare() }
  }

  fun take(context: Context, file: File): ExoPlayer =
      ExoPlayer.Builder(context.applicationContext).build().apply { setMediaItem(MediaItem.fromUri(Uri.fromFile(file))); prepare() }
}
