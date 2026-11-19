/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * The message self preview: a mirrored TextureView, centre-cropped to fill, handed to the
 * recorder while it is on screen (the camera must have a visible preview). Detaching it closes
 * the camera.
 */
@Composable
internal fun CallMessagePreview(recorder: CallMessageRecorder, modifier: Modifier = Modifier) {
  AndroidView(
      factory = { ctx ->
        TextureView(ctx).apply {
          surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
              setTransform(cropFill(w, h, CallMessageRecorder.WIDTH, CallMessageRecorder.HEIGHT, mirror = true))
              recorder.attachPreview(st)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
              setTransform(cropFill(w, h, CallMessageRecorder.WIDTH, CallMessageRecorder.HEIGHT, mirror = true))
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { recorder.attachPreview(null); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
          }
        }
      },
      modifier = modifier,
  )
}

/** A message (or the take under review) playing into a centre-cropped TextureView. */
@Composable
internal fun CallMessageVideo(player: ExoPlayer, modifier: Modifier = Modifier, fill: Boolean = true) {
  AndroidView(
      factory = { ctx ->
        TextureView(ctx).apply {
          val view = this
          player.setVideoTextureView(view)
          player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
              if (size.width > 0 && size.height > 0) view.post {
                view.setTransform(if (fill) cropFill(view.width, view.height, size.width, size.height, mirror = false)
                    else fit(view.width, view.height, size.width, size.height))
              }
            }
          })
        }
      },
      modifier = modifier,
      onRelease = { view -> player.clearVideoTextureView(view) },
  )
}

/** TextureView stretches the buffer to the view; scale it back so it fills (cropping), optionally mirrored. */
internal fun cropFill(viewW: Int, viewH: Int, bufW: Int, bufH: Int, mirror: Boolean): Matrix = Matrix().apply {
  if (viewW <= 0 || viewH <= 0 || bufW <= 0 || bufH <= 0) return@apply
  val viewAspect = viewW.toFloat() / viewH
  val bufAspect = bufW.toFloat() / bufH
  val sx = if (bufAspect > viewAspect) bufAspect / viewAspect else 1f
  val sy = if (bufAspect > viewAspect) 1f else viewAspect / bufAspect
  setScale(if (mirror) -sx else sx, sy, viewW / 2f, viewH / 2f)
}

private fun fit(viewW: Int, viewH: Int, bufW: Int, bufH: Int): Matrix = Matrix().apply {
  if (viewW <= 0 || viewH <= 0 || bufW <= 0 || bufH <= 0) return@apply
  val viewAspect = viewW.toFloat() / viewH
  val bufAspect = bufW.toFloat() / bufH
  if (bufAspect > viewAspect) setScale(1f, viewAspect / bufAspect, viewW / 2f, viewH / 2f)
  else setScale(bufAspect / viewAspect, 1f, viewW / 2f, viewH / 2f)
}
