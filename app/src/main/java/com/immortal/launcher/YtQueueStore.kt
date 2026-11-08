/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log

/**
 * Saves the YouTube queue (public video metadata + resume position only; no tokens) so it
 * survives process death. Writes are asynchronous ([android.content.SharedPreferences.Editor.apply]).
 */
internal class YtQueueStore(context: Context) {
  private companion object {
    const val TAG = "ImmortalYtQueue"
    const val PREFS = "youtube_player_queue"
    const val KEY = "queue_v1"
  }

  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** The saved queue, or null when there is none or it cannot be read. */
  fun load(): YtPersistedQueue? = runCatching { YtQueueCodec.decode(prefs.getString(KEY, null)) }
      .onFailure { Log.w(TAG, "saved queue unreadable", it) }
      .getOrNull()

  fun save(queue: YtQueue, positionMs: Long) {
    runCatching { prefs.edit().putString(KEY, YtQueueCodec.encode(queue, positionMs)).apply() }
        .onFailure { Log.w(TAG, "queue save failed", it) }
  }
}
