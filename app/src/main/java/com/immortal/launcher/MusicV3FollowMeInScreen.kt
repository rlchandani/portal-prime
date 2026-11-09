/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** "Continue <title> here?" shown inside the music screen; answer it through [MusicV3FollowMeInScreen.answer]. */
internal data class MusicFollowAsk(
    val seq: Long,
    val title: String,
    val line: String,
    val onContinue: () -> Unit,
    val onNotNow: () -> Unit,
)

/** "Music followed you from <room>" with Undo, shown as a stage toast. */
internal data class MusicFollowNotice(val seq: Long, val text: String, val onUndo: () -> Unit)

/**
 * Follow-me while the music screen is open. [FollowMeCardOverlay] draws over every app, so over the
 * music screen it would cover the YouTube player; instead the ask takes over the Resume card / the
 * strip / a right-column card, and the "followed you" banner becomes a toast with Undo. Main thread.
 */
internal object MusicV3FollowMeInScreen {
  private val main = Handler(Looper.getMainLooper())
  private val askFlow = MutableStateFlow<MusicFollowAsk?>(null)
  private val noticeFlow = MutableStateFlow<MusicFollowNotice?>(null)
  private var screens = 0
  private var seq = 0L
  private val askTimeout = Runnable { askFlow.value?.let { answer(it, continueHere = false) } }
  private val noticeTimeout = Runnable { noticeFlow.value = null }

  val ask: StateFlow<MusicFollowAsk?> = askFlow.asStateFlow()
  val notice: StateFlow<MusicFollowNotice?> = noticeFlow.asStateFlow()

  /** The music stage calls this when it shows (true) and leaves (false). */
  @MainThread
  fun screenShown(shown: Boolean) {
    screens = (screens + if (shown) 1 else -1).coerceAtLeast(0)
    if (screens > 0) return
    // Leaving with a question open counts as "Not now", like a tap outside the card.
    askFlow.value?.let { answer(it, continueHere = false) }
    main.removeCallbacks(noticeTimeout)
    noticeFlow.value = null
  }

  /** Shows the ask in the music screen; false when it isn't open (the caller draws its overlay). */
  @MainThread
  fun offerAsk(title: String, line: String, timeoutMs: Long, onContinue: () -> Unit, onNotNow: () -> Unit): Boolean {
    if (screens == 0) return false
    askFlow.value?.let { answer(it, continueHere = false) }
    askFlow.value = MusicFollowAsk(++seq, title, line, onContinue, onNotNow)
    main.postDelayed(askTimeout, timeoutMs)
    return true
  }

  @MainThread
  fun answer(ask: MusicFollowAsk, continueHere: Boolean) {
    if (askFlow.value?.seq != ask.seq) return
    main.removeCallbacks(askTimeout)
    askFlow.value = null
    if (continueHere) ask.onContinue() else ask.onNotNow()
  }

  @MainThread
  fun offerNotice(text: String, timeoutMs: Long, onUndo: () -> Unit): Boolean {
    if (screens == 0) return false
    main.removeCallbacks(noticeTimeout)
    noticeFlow.value = MusicFollowNotice(++seq, text, onUndo)
    main.postDelayed(noticeTimeout, timeoutMs)
    return true
  }

  @MainThread
  fun undo(notice: MusicFollowNotice) {
    if (noticeFlow.value?.seq != notice.seq) return
    main.removeCallbacks(noticeTimeout)
    noticeFlow.value = null
    notice.onUndo()
  }
}
