/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.lang.ref.WeakReference

/**
 * Muted SurfaceView playback with all MediaPlayer access on one background Looper.
 * Main owns view/layout and immutable requests; position reads are cached and never call Binder.
 */
internal class SlideshowVideoView(context: Context) : SurfaceView(context), AutoCloseable {
  private data class Source(val uri: String, val headers: Map<String, String>)
  private data class Ticket(val source: Long, val surface: Long)
  private data class Request(
      val source: Source? = null, val surface: Surface? = null,
      val sourceRevision: Long = 0, val surfaceRevision: Long = 0,
      val play: Boolean = false, val seekRevision: Long = 0, val seekMs: Int = 0,
      val closed: Boolean = false,
  ) {
    val ticket: Ticket get() = Ticket(sourceRevision, surfaceRevision)
  }

  private var request = Request()
  private var videoWidth = 0
  private var videoHeight = 0
  private var preparedListener: ((Int, Int) -> Unit)? = null
  private var sizeListener: ((Int, Int) -> Unit)? = null
  private var completionListener: (() -> Unit)? = null
  private var errorListener: ((Int, Int) -> Unit)? = null
  private var infoListener: ((Int, Int) -> Unit)? = null
  private val owner = Owner(context.applicationContext, WeakReference(this))
  val currentPosition: Int get() =
      if (request.source == null) 0 else owner.positionForSource(request.sourceRevision)

  init {
    holder.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        change { it.copy(surface = holder.surface, surfaceRevision = it.surfaceRevision + 1) }
      }
      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
      override fun surfaceDestroyed(holder: SurfaceHolder) {
        val retained = VideoPlaybackRetention(request.sourceRevision, request.surfaceRevision, currentPosition)
        change {
          val revision = it.surfaceRevision + 1
          val recover = it.source != null && retained.needsSeek(it.sourceRevision, revision)
          // Carry recovery through an invalid-surface release until the replacement prepares.
          // Source replacement resets both seek fields, so an old clip cannot leak its position.
          it.copy(surface = null, surfaceRevision = revision,
              seekRevision = if (recover) it.seekRevision + 1 else it.seekRevision,
              seekMs = if (recover) retained.positionMs else it.seekMs)
        }
      }
    })
  }

  private fun change(transform: (Request) -> Request) {
    check(Looper.myLooper() === Looper.getMainLooper())
    if (request.closed) return
    request = transform(request)
    owner.offer(request)
  }

  fun setVideoPath(path: String) = setVideoURI(Uri.fromFile(java.io.File(path)))
  fun setVideoURI(uri: Uri, headers: Map<String, String> = emptyMap()) {
    change { it.copy(source = Source(uri.toString(), headers.toMap()),
        sourceRevision = it.sourceRevision + 1, play = false, seekRevision = 0, seekMs = 0) }
  }
  fun start() = change { it.copy(play = true) }
  fun pause() = change { it.copy(play = false) }
  fun seekTo(positionMs: Int) = change {
    it.copy(seekRevision = it.seekRevision + 1, seekMs = positionMs.coerceAtLeast(0))
  }
  fun stopPlayback() = change {
    it.copy(source = null, sourceRevision = it.sourceRevision + 1,
        play = false, seekRevision = 0, seekMs = 0)
  }
  fun setOnPreparedListener(listener: (Int, Int) -> Unit) { preparedListener = listener }
  fun setOnVideoSizeChangedListener(listener: (Int, Int) -> Unit) { sizeListener = listener }
  fun setOnCompletionListener(listener: () -> Unit) { completionListener = listener }
  fun setOnErrorListener(listener: (Int, Int) -> Unit) { errorListener = listener }
  fun setOnInfoListener(listener: (Int, Int) -> Unit) { infoListener = listener }

  override fun close() {
    change { it.copy(closed = true, source = null, surface = null,
        sourceRevision = it.sourceRevision + 1, play = false) }
    preparedListener = null; sizeListener = null; completionListener = null
    errorListener = null; infoListener = null
  }

  private fun accepts(ticket: Ticket): Boolean =
      !request.closed && request.source != null && request.surface != null && request.ticket == ticket

  private fun dimensions(width: Int, height: Int) {
    if (videoWidth == width && videoHeight == height) return
    videoWidth = width; videoHeight = height
    if (width > 0 && height > 0) holder.setFixedSize(width, height)
    requestLayout()
  }

  // Preserve VideoView's whole-video letterboxing; the host's cover bounds still implement fill.
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    var width = getDefaultSize(videoWidth, widthMeasureSpec)
    var height = getDefaultSize(videoHeight, heightMeasureSpec)
    if (videoWidth > 0 && videoHeight > 0) {
      if (videoWidth.toLong() * height > width.toLong() * videoHeight)
        height = (width.toLong() * videoHeight / videoWidth).toInt()
      else width = (height.toLong() * videoWidth / videoHeight).toInt()
    }
    setMeasuredDimension(width, height)
  }

  /** No strong View/Activity references are retained by blocked player work or its callbacks. */
  private class Owner(private val context: Context, private val view: WeakReference<SlideshowVideoView>) {
    private val worker = playbackHandler
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var latest = Request()
    private val positions = VideoPositionCache()
    fun positionForSource(sourceRevision: Long): Int = positions.read(sourceRevision)
    private var player: MediaPlayer? = null
    private var ticket: Ticket? = null
    private var prepared = false
    private var playing = false
    private var appliedSeek = 0L
    private val commands = ConflatedVideoCommands<Request>(worker::post, ::apply)
    private val positionTick = object : Runnable {
      override fun run() {
        val active = player ?: return
        val expected = ticket ?: return
        if (!prepared || !playing || latest.closed || latest.ticket != expected) return
        val value = runCatching { active.currentPosition }.getOrDefault(positions.read(expected.source))
        if (latest.ticket == expected) positions.publish(expected.source, value)
        worker.postDelayed(this, 250)
      }
    }

    fun offer(value: Request) {
      latest = value
      commands.offer(value)
    }

    private fun publish(expected: Ticket, action: (SlideshowVideoView) -> Unit) {
      main.post {
        val target = view.get()
        if (target != null && target.accepts(expected)) action(target)
      }
    }

    private fun release() {
      worker.removeCallbacks(positionTick)
      val old = player
      player = null; ticket = null; prepared = false; playing = false; appliedSeek = 0
      if (old != null) {
        runCatching {
          old.setOnPreparedListener(null); old.setOnCompletionListener(null)
          old.setOnErrorListener(null); old.setOnInfoListener(null); old.setOnVideoSizeChangedListener(null)
          old.setOnSeekCompleteListener(null)
        }
        runCatching { old.release() }
      }
    }

    private fun apply(value: Request) {
      check(Looper.myLooper() === worker.looper)
      // A snapshot waiting behind native release may already have been superseded.
      if (value != latest) return
      if (value.closed || value.source == null || value.surface?.isValid != true) {
        release()
        return
      }
      if (ticket != value.ticket) {
        release()
        // Native release can take time; revalidate before opening the replacement.
        if (latest != value) return
        val expected = value.ticket
        try {
          val active = MediaPlayer()
          player = active; ticket = expected
          active.setAudioAttributes(AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
          active.setOnPreparedListener { ready ->
            if (player !== ready || latest.ticket != expected || latest.closed) return@setOnPreparedListener
            prepared = true
            ready.isLooping = false
            ready.setVolume(0f, 0f)
            val width = ready.videoWidth
            val height = ready.videoHeight
            publish(expected) { target ->
              target.dimensions(width, height)
              target.preparedListener?.invoke(width, height)
            }
            control(latest)
          }
          active.setOnVideoSizeChangedListener { changed, width, height ->
            if (player !== changed) return@setOnVideoSizeChangedListener
            publish(expected) { target ->
              target.dimensions(width, height)
              target.sizeListener?.invoke(width, height)
            }
          }
          active.setOnCompletionListener { completed ->
            if (player !== completed) return@setOnCompletionListener
            playing = false
            worker.removeCallbacks(positionTick)
            publish(expected) { it.completionListener?.invoke() }
          }
          active.setOnSeekCompleteListener { seeked ->
            if (player === seeked && latest.ticket == expected && !latest.closed)
              positions.publish(expected.source, runCatching { seeked.currentPosition }
                  .getOrDefault(positions.read(expected.source)))
          }
          active.setOnErrorListener { failed, what, extra ->
            if (player !== failed) return@setOnErrorListener true
            release()
            publish(expected) { it.errorListener?.invoke(what, extra) }
            true
          }
          active.setOnInfoListener { changed, what, extra ->
            if (player !== changed) return@setOnInfoListener false
            publish(expected) { it.infoListener?.invoke(what, extra) }
            false
          }
          active.setSurface(value.surface)
          active.setDataSource(context, Uri.parse(value.source.uri), value.source.headers)
          active.prepareAsync()
        } catch (_: Exception) {
          release()
          publish(expected) { it.errorListener?.invoke(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0) }
        }
      }
      control(latest)
    }

    private fun control(value: Request) {
      val active = player ?: return
      if (!prepared || value.closed || value.ticket != ticket) return
      try {
        if (appliedSeek != value.seekRevision) {
          if (Build.VERSION.SDK_INT >= 26)
            active.seekTo(value.seekMs.toLong(), MediaPlayer.SEEK_CLOSEST)
          else active.seekTo(value.seekMs)
          appliedSeek = value.seekRevision
        }
        if (playing != value.play) {
          if (value.play) active.start() else active.pause()
          playing = value.play
          worker.removeCallbacks(positionTick)
          if (playing) positionTick.run()
          else positions.publish(value.sourceRevision, runCatching { active.currentPosition }
              .getOrDefault(positions.read(value.sourceRevision)))
        }
      } catch (_: Exception) {
        val expected = value.ticket
        release()
        publish(expected) { it.errorListener?.invoke(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0) }
      }
    }
  }

  private companion object {
    // One process-wide owner Looper, not an accumulating thread for each frame/replacement.
    val playbackHandler by lazy {
      Handler(HandlerThread("slideshow-video-owner", android.os.Process.THREAD_PRIORITY_BACKGROUND)
          .apply { isDaemon = true; start() }.looper)
    }
  }
}
