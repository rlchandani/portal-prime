/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * While music is on screen the soft keyboard overlays it (ADJUST_NOTHING) instead of panning the
 * launcher, so it rises from the bottom and stops below the player; restored after close. Changing the
 * mode relayouts the launcher's window (a binder round trip to the window manager, 3 ms to ~50 ms on
 * the Portal Mini), so it is applied when a Music text field takes focus (before its keyboard shows)
 * or once Music has opened ([MusicKeyboardPolicy.SETTLE_MS]), never inside the opening frame, and it is
 * restored [MusicKeyboardPolicy.RESTORE_MS] after Music closes, never inside the closing frame. It is
 * restored only if it was applied.
 */
@Composable
internal fun rememberMusicV3KeyboardPolicy(): MusicKeyboardPolicy {
  val view = LocalView.current
  val policy = remember(view) {
    val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()?.window
    MusicKeyboardPolicy(window?.let(::WindowSoftInput), ViewScheduler(view))
  }
  DisposableEffect(policy) {
    val settle = Runnable { policy.ensure() }
    view.postDelayed(settle, MusicKeyboardPolicy.SETTLE_MS)
    onDispose {
      view.removeCallbacks(settle)
      policy.restoreLater()
    }
  }
  return policy
}

/** The launcher window's soft-input mode. */
internal interface SoftInputTarget {
  val mode: Int
  fun apply(mode: Int)
}

/** Main-thread delayed work. */
internal interface MusicMainScheduler {
  fun post(task: Runnable, delayMs: Long)
  fun cancel(task: Runnable)
}

private class WindowSoftInput(private val window: Window) : SoftInputTarget {
  override val mode: Int get() = window.attributes.softInputMode
  override fun apply(mode: Int) = window.setSoftInputMode(mode)
}

private class ViewScheduler(private val view: View) : MusicMainScheduler {
  override fun post(task: Runnable, delayMs: Long) { view.postDelayed(task, delayMs) }
  override fun cancel(task: Runnable) { view.removeCallbacks(task) }
}

/**
 * Applies Music's soft-keyboard mode to the launcher window at most once per visit; see
 * [rememberMusicV3KeyboardPolicy]. Main thread only. A restore still pending when Music opens again is
 * taken over by the new visit (the window is still in Music's mode), so a quick close and reopen never
 * restores Music's own mode as "the previous one".
 */
internal class MusicKeyboardPolicy(private val target: SoftInputTarget?, private val scheduler: MusicMainScheduler) {
  private var previous: Int? = null

  fun ensure() {
    val target = target ?: return
    if (previous != null) return
    val pending = pendingRestore?.takeIf { it.target === target }
    if (pending != null) {
      scheduler.cancel(pending.task)
      pendingRestore = null
      previous = pending.mode
      return
    }
    previous = target.mode
    target.apply(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
  }

  /** Music closed: restore the window's own mode once the closing frames are done. */
  fun restoreLater() {
    val target = target ?: return
    val mode = previous ?: return
    previous = null
    pendingRestore?.let { scheduler.cancel(it.task) }
    val pending = PendingRestore(target, mode)
    pending.task = Runnable {
      if (pendingRestore === pending) pendingRestore = null
      target.apply(mode)
    }
    pendingRestore = pending
    scheduler.post(pending.task, RESTORE_MS)
  }

  private class PendingRestore(val target: SoftInputTarget, val mode: Int) {
    lateinit var task: Runnable
  }

  internal companion object {
    /** After Music's opening work (about 1 s) and before Home goes inactive ([HomeMusicCover.SETTLE_MS]). */
    const val SETTLE_MS = 2_000L

    /** After the closing frames ([HomeMusicCover.closePlan]); well before anyone can reach a Home text field. */
    const val RESTORE_MS = 600L

    /** At most one restore is pending: there is one launcher window and one Music at a time. */
    private var pendingRestore: PendingRestore? = null

    /** Tests only. */
    internal fun resetForTest() { pendingRestore = null }
  }
}

/** Music's keyboard policy, for its text fields to apply before their keyboard shows; null outside Music v3. */
internal val LocalMusicKeyboardPolicy = staticCompositionLocalOf<MusicKeyboardPolicy?> { null }
