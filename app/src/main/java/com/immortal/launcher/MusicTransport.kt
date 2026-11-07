/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Shared contract for the YouTube Music player build (2026-11-07).
 *
 * Every music surface (split-screen Now Playing column, ambient screensaver card, header
 * mini-player) READS [NowPlayingHub]. Sources PUBLISH into it:
 *  - Android media sessions via [MediaSessionReader] (existing; e.g. a browser playing music);
 *  - the embedded YouTube Music page via its JS bridge, with [NowPlayingState.source] =
 *    [SOURCE_EMBEDDED_YT_MUSIC]. An Android WebView does not publish a media session, so the
 *    page itself must be observed and controlled.
 *
 * Commands go through [MusicTransport]. Surfaces call [MusicTransports.forState] and must not
 * reach into MediaController or the WebView directly.
 */
interface MusicTransport {
  fun play()
  fun pause()
  fun next()
  fun previous()
  fun seekTo(positionMs: Long)
}

const val SOURCE_EMBEDDED_YT_MUSIC = "embedded:youtube-music"

object MusicTransports {
  /** Set by the embedded YouTube Music pane while its page is alive; cleared on release. */
  @Volatile var embedded: MusicTransport? = null

  /** Set by the integration layer to control Android media sessions for a given state. */
  @Volatile var mediaSession: ((NowPlayingState) -> MusicTransport?)? = null

  /** The transport for [state]'s source, or null when nothing can be controlled. */
  fun forState(state: NowPlayingState?): MusicTransport? = when {
    state == null -> null
    state.source == SOURCE_EMBEDDED_YT_MUSIC -> embedded
    else -> mediaSession?.invoke(state)
  }
}
