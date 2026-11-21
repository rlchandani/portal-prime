/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * SharedPreferences through the application context. A new Activity or Service context has its
 * own empty prefs-path cache, so its first `getSharedPreferences` for each file stats the data and
 * prefs directories on the caller's thread (two StrictMode DiskReads per file, per Activity). The
 * application context resolves each path once per process (most at startup, in
 * StartupPrefsWarmup). The file and the process-wide [SharedPreferences] object are the same
 * either way. Used by the owners the screensaver frame reads while its Activity is created.
 */
internal fun processPrefs(context: Context, name: String): SharedPreferences =
    (context.applicationContext ?: context).getSharedPreferences(name, Context.MODE_PRIVATE)

/**
 * The app cache directory, resolved once per process. `Context.getCacheDir` stats (and may
 * create) the directory on every call; the screensaver frame resolved it inside its Activity's
 * onCreate. The path never changes for a process, and its user here ([EngineScaledCache]) creates
 * its directory, parents included (`mkdirs`), before each write, so the remembered [File] stays
 * valid even if the system clears the cache's contents.
 */
internal object ProcessCacheDir {
  private val memo = FirstValueMemo<File>()

  fun of(context: Context): File = memo.get { (context.applicationContext ?: context).cacheDir }
}

/** Remembers the first value [get] resolves; a resolver that throws is not remembered. */
internal class FirstValueMemo<T : Any> {
  @Volatile private var value: T? = null

  fun get(resolve: () -> T): T = value ?: resolve().also { value = it }
}
