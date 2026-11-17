/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The places that can show the ONE official YouTube player. Lower [rank] wins if both are composed
 * at once. (A floating picture-in-picture window was evaluated and is not available to apps on the
 * Portal; see docs/design/music-home-player.md.)
 */
enum class MusicPlayerHostKind(val rank: Int) {
  /** The full Music screen. */
  MUSIC_SCREEN(0),
  /** Home's Now Playing card (Home, Apps and Tools tabs). */
  HOME_CARD(1),
}

/**
 * Where Music's YouTube player is on screen. A host is [attached] while its surface is composed and
 * [active] while the screen around it is in the foreground (lifecycle RESUMED). The player is
 * [visible] when some host is both, and lives in the best-ranked one ([shownIn]). Pure; driven by
 * [YouTubeMusicEngine].
 *
 * Leaving every host (another app, the screensaver, Customize, ✕ on the card) is "hidden": the
 * engine pauses and starts the release grace. Moving between hosts keeps the same player playing;
 * the WebView is only re-parented.
 */
internal data class MusicPlayerHosts(
    val attached: Set<MusicPlayerHostKind> = emptySet(),
    val active: Set<MusicPlayerHostKind> = emptySet(),
) {
  val shownIn: MusicPlayerHostKind?
    get() = attached.filter { it in active }.minByOrNull { it.rank }

  val visible: Boolean get() = shownIn != null

  fun withAttached(kind: MusicPlayerHostKind, on: Boolean) = copy(attached = attached.toggled(kind, on))
  fun withActive(kind: MusicPlayerHostKind, on: Boolean) = copy(active = active.toggled(kind, on))

  /** What the engine does when the hosts change from this to [next]. */
  fun effectTo(next: MusicPlayerHosts): PlayerHostEffect {
    val from = shownIn
    val to = next.shownIn
    return when {
      from == to -> PlayerHostEffect.None
      to == null -> PlayerHostEffect.Hide
      from == null -> PlayerHostEffect.Show(to)
      else -> PlayerHostEffect.Move(from, to)
    }
  }

  private fun Set<MusicPlayerHostKind>.toggled(kind: MusicPlayerHostKind, on: Boolean) =
      if (on) this + kind else this - kind
}

internal sealed interface PlayerHostEffect {
  data object None : PlayerHostEffect
  /** Cancel a pending hide/release and host the player in [host] (creating it if it was released). */
  data class Show(val host: MusicPlayerHostKind) : PlayerHostEffect
  /** Re-parent the same player from [from] to [to]; playback continues, nothing reloads. */
  data class Move(val from: MusicPlayerHostKind, val to: MusicPlayerHostKind) : PlayerHostEffect
  /** No host shows it: pause after [MusicHostHandoff.SWAP_MS], release after the grace. */
  data object Hide : PlayerHostEffect
}

/**
 * Host swaps are not atomic: Music's surface leaves composition a frame before Home's card attaches
 * (and Home's card a frame before Music's slot). A hide therefore waits [SWAP_MS] before it pauses;
 * a host attaching meanwhile cancels it, so going Home never stutters the music.
 */
internal object MusicHostHandoff {
  /** Covers a same-screen swap (one or two frames), short enough that hidden audio is never noticeable. */
  const val SWAP_MS = 250L
}
