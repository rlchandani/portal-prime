/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The other room, full screen: a SurfaceView renderer (its own hardware layer, cheapest path for a
 * 30 fps full-screen video on the Portal GPU). Detached and released when it leaves composition.
 */
@Composable
internal fun CallRemoteVideo(media: CallMedia, modifier: Modifier = Modifier,
                              scaling: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL) {
  AndroidView(
      factory = { ctx ->
        SurfaceViewRenderer(ctx).apply {
          init(media.egl.eglBaseContext, null)
          setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
          tag = RendererCommon.ScalingType.SCALE_ASPECT_FILL
          setEnableHardwareScaler(true)
          media.remote.target = this
        }
      },
      modifier = modifier,
      // Rotating the Mini changes only the scaling (no new surface); a no-op while it is unchanged.
      update = { view -> if (view.tag != scaling) { view.tag = scaling; view.setScalingType(scaling) } },
      onRelease = { view ->
        if (media.remote.target === view) media.remote.target = null
        view.release()
      },
  )
}

/**
 * The self view: a TextureView renderer, so it clips to the PIP's rounded corners and stacks above
 * the remote SurfaceView. Mirrored like a mirror. It must stay visible whenever the camera is open:
 * Portal's camera service closes a camera after 7 s unless a visible preview is receiving frames.
 */
@Composable
internal fun CallSelfVideo(media: CallMedia, modifier: Modifier = Modifier) {
  AndroidView(
      factory = { ctx -> CallTextureRenderer(ctx).apply { init(media.egl.eglBaseContext, mirror = true); media.local.target = this } },
      modifier = modifier,
      onRelease = { view ->
        if (media.local.target === view) media.local.target = null
        view.release()
      },
  )
}

/** A minimal TextureView + EglRenderer (libwebrtc ships only the SurfaceView flavour). */
internal class CallTextureRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
  private val renderer = EglRenderer("hearthpane-call-self")
  private var initialized = false

  fun init(shared: EglBase.Context, mirror: Boolean) {
    renderer.init(shared, EglBase.CONFIG_PLAIN, GlRectDrawer())
    renderer.setMirror(mirror)
    initialized = true
    surfaceTextureListener = this
    isOpaque = true
  }

  override fun onFrame(frame: VideoFrame) {
    if (initialized) renderer.onFrame(frame)
  }

  override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
    renderer.setLayoutAspectRatio(width.toFloat() / height.coerceAtLeast(1))
    renderer.createEglSurface(surface)
  }

  override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
    renderer.setLayoutAspectRatio(width.toFloat() / height.coerceAtLeast(1))
  }

  override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
    val done = CountDownLatch(1)
    renderer.releaseEglSurface(done::countDown)
    done.await(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
    return true
  }

  override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

  fun release() {
    if (!initialized) return
    initialized = false
    renderer.release()
  }

  private companion object {
    const val RELEASE_WAIT_MS = 500L
  }
}
