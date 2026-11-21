/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Process
import android.util.Log
import com.immortal.launcher.youtube.YtAndroid

/**
 * Off-main warm-up of the one-time loads that the first open of a screen otherwise does on the
 * main thread (StrictMode sweep, docs/quality/main-thread-sweep-2026-11-20.md):
 *
 *  - the YouTube account store (Keystore-sealed file read and AES-GCM decrypt) behind the first
 *    Music open;
 *  - the Grandma-mode auto-answer rules (also Keystore-sealed) behind the first Calls open and
 *    the first incoming-call decision;
 *  - the app cache directory, which the screensaver frame resolves while it is being built;
 *  - the kitchen-timer prefs file, first read by the screensaver face's first render.
 *
 * Each task calls the owner's own process-wide getter, which is synchronized and loads at most
 * once. So the main thread either finds the loaded value or, arriving while the warm-up is
 * still loading it, waits for that same load instead of starting its own; there is never a
 * second copy and never a use before load. A failed task leaves its owner untouched, and the
 * first real use loads it on demand exactly as it would without this warm-up.
 */
internal object FirstOpenWarmup {
  private const val TAG = "FirstOpenWarmup"
  private const val THREAD_NAME = "hearthpane-first-open"

  internal class Task(val name: String, val load: () -> Unit)

  @Volatile private var started = false

  /** Once per process; returns immediately. Runs as a deferred startup step, after the first frame. */
  fun start(context: Context) {
    if (started) return
    started = true
    val app = context.applicationContext ?: context
    runCatching {
      Thread({
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val failed = run(tasks(app)) { name, error -> Log.w(TAG, "$name not warmed: ${error.javaClass.simpleName}") }
        if (failed.isNotEmpty()) Log.i(TAG, "left to first use: $failed")
      }, THREAD_NAME).apply { isDaemon = true }.start()
    }.onFailure { Log.w(TAG, "warm-up thread failed to start", it) }
  }

  fun tasks(app: Context): List<Task> = listOf(
      Task("cacheDir") { ProcessCacheDir.of(app) },
      Task("CallAutoAnswerStore") { CallAutoAnswerStore.repository(app) },
      Task("YouTubeLibrary") { YtAndroid.library(app) },
      Task("TimerStore") { TimerStore.load(app) },
  )

  /**
   * Runs every task in order on the calling thread. One failing never stops the rest; returns
   * the names of the ones that failed. Only the exception's type reaches [onFailure]'s log line:
   * these stores hold secrets and their messages are not vetted.
   */
  fun run(tasks: List<Task>, onFailure: (String, Throwable) -> Unit = { _, _ -> }): List<String> =
      tasks.mapNotNull { task ->
        runCatching { task.load() }.exceptionOrNull()?.let { error ->
          onFailure(task.name, error)
          task.name
        }
      }
}
