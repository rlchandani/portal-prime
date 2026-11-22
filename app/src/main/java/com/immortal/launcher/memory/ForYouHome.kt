/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import android.content.Context
import android.util.Log
import java.time.ZoneId

/**
 * Binds the "For you right now" row to the live Portal. It is behind the same switch as the rest of personalization
 * ("Jarvis learns from conversations", off by default) and follows the same rule of who it belongs to
 * ([MemoryAccess]): nothing for guests, children or teens; the recognised adult's own suggestions; the household's
 * when nobody is recognised, or when others are near (plan §4.4). Reads are on the caller's (background) thread;
 * writes go through the memory thread. Logs carry counts only.
 */
internal object ForYouHome {
  private const val TAG = "HearthpaneForYou"

  /** What the row shows for the moment: whose suggestions, whether learning is paused, and the ranking. */
  data class Snapshot(val scope: MemoryScope, val paused: Boolean, val ranked: List<ForYouRanker.Suggestion>) {
    val household: Boolean get() = scope is MemoryScope.Household
  }

  /** The row for right now, or null when nothing may be shown. Call off the main thread. */
  fun load(context: Context): Snapshot? {
    val on = JarvisMemory.accessNow(context) as? MemoryAccess.On ?: return null
    val scope = if (on.othersNear) MemoryScope.Household else on.scope
    val runtime = JarvisMemory.runtime(context)
    val ranked = ForYouRanker.rank(runtime.usage(), runtime.feedback(), scope, System.currentTimeMillis(),
        ZoneId.systemDefault())
    return Snapshot(scope, runtime.profile(scope).paused, ranked)
  }

  /** Something done with a chip; "Don't suggest this" is written at once. */
  fun note(context: Context, scope: MemoryScope, surface: String, kind: ForYouFeedback.Kind) {
    val ctx = context.applicationContext
    JarvisMemory.background {
      JarvisMemory.runtime(ctx).recordFeedback(immediate = kind == ForYouFeedback.Kind.NEVER) {
        it.with(scope, surface, kind, System.currentTimeMillis())
      }
      if (kind != ForYouFeedback.Kind.SHOWN) Log.i(TAG, "chip ${kind.name.lowercase()}")
    }
  }

  /** Undo of "Don't suggest this". */
  fun allowAgain(context: Context, scope: MemoryScope, surface: String) {
    val ctx = context.applicationContext
    JarvisMemory.background { JarvisMemory.runtime(ctx).recordFeedback(immediate = true) { it.allowAgain(scope, surface) } }
  }

  /** The row's "Resume" on the paused banner: learning back on for [scope]. */
  fun resume(context: Context, scope: MemoryScope) {
    val ctx = context.applicationContext
    JarvisMemory.background { JarvisMemory.runtime(ctx).update(scope) { MemoryLearner.setPaused(it, false) } }
  }
}
