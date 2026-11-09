/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Hearthpane copy for the full-screen Music stage (from the approved mock). */
internal object MusicV3StageCopy {
  const val BROWSE = "Browse"
  const val NOTHING_PLAYING = "Nothing playing"
  const val NOTHING_PLAYING_SUB = "Pick up where you left off, or choose something below."
  const val NOTHING_QUEUED = "Nothing queued"
  const val PEEK_IDLE_DETAIL = "Playlists, liked songs, search"
  const val AD_TITLE = "A short ad"
  const val AD_KICKER = "Ad · your music continues after"
  const val PLAYER_CAPTION_TITLE = "Video"
  const val PLAYER_CAPTION_DETAIL = "Official YouTube player"
  const val SLEEP_TITLE = "Stop the music…"
  const val SLEEP_OFF = "Sleep timer"
  const val BRING_BACK = "Bring it back"
  const val CONTINUE = "Continue"
  const val NOT_NOW = "Not now"
  const val RESUME = "Resume"
  const val OPEN_LIBRARY = "Open Library"
  const val CLOSE_SHEET = "Close"
  const val TOAST_SHORT_MS = 2_400L
  const val TOAST_LONG_MS = 5_000L

  /** "Browse · Up next" while something is loaded, "Browse · Library" with nothing. */
  fun peekLabel(idle: Boolean): String = if (idle) "Library" else "Up next"

  fun peekNext(next: YtTrack?): String? = next?.let { if (it.artist.isBlank()) it.title else "${it.title} · ${it.artist}" }

  fun playing(title: String): String = "Playing $title"

  fun movedSlot(room: String): String = "Playing in $room"

  /** "Quiet hours · volume stays low until 7:00 am" ([until] already formatted), or null. */
  fun quietNote(until: String?): String = if (until.isNullOrBlank()) "Quiet hours · volume stays low" else
    "Quiet hours · volume stays low until $until"

  fun nextRepeat(r: YtRepeat): YtRepeat = when (r) {
    YtRepeat.OFF -> YtRepeat.ALL
    YtRepeat.ALL -> YtRepeat.ONE
    YtRepeat.ONE -> YtRepeat.OFF
  }

  fun repeatLabel(r: YtRepeat): String = when (r) {
    YtRepeat.OFF -> "Repeat"
    YtRepeat.ALL -> "Repeat, all songs"
    YtRepeat.ONE -> "Repeat, this song"
  }

  fun repeatToast(r: YtRepeat): String = when (r) {
    YtRepeat.OFF -> "Repeat off"
    YtRepeat.ALL -> "Repeating the queue"
    YtRepeat.ONE -> "Repeating this song"
  }
}
