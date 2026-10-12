/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicLong

/** Accepted edits survive pane departure; the registry owns validation and alarm side effects. */
internal class ChimeSettingsWriteQueue(apply: (String, Any) -> Boolean) {
  private val failures = AtomicLong()
  private val writes = LatestSystemWrites(
    keyOf = { edit: Pair<String, Any> -> edit.first },
    onFailure = { failures.incrementAndGet() },
  ) { edit -> if (!apply(edit.first, edit.second)) failures.incrementAndGet() }
  val failureCount: Long get() = failures.get()
  fun submit(key: String, value: Any) = writes.submit(key to value)
  suspend fun awaitIdle() = writes.awaitIdle()
}

/** One process writer retains only the application, never an Activity or settings pane. */
internal object ChimeSettingsWrites {
  @Volatile private var instance: ChimeSettingsWriteQueue? = null
  fun queue(context: android.content.Context): ChimeSettingsWriteQueue = instance ?: synchronized(this) {
    instance ?: run {
      val application = context.applicationContext
      ChimeSettingsWriteQueue { key, value ->
        key in com.immortal.launcher.settings.SettingsDomains.chime.apply(application,
          org.json.JSONObject().put(key, value))
      }.also { instance = it }
    }
  }
}

/** Only completed reads may clear busy; a resume keeps the submitted edit's failure baseline. */
internal data class ChimeSaveState(
  val revision: Int = 0,
  val saving: Boolean = true,
  val failed: Boolean = false,
  private val failureBaseline: Long? = null,
) {
  fun beginRefresh() = copy(saving = true)
  fun beginEdit(failures: Long) = copy(revision = revision + 1, saving = true,
    failed = false, failureBaseline = failures)
  fun completeRead(expectedRevision: Int, failures: Long) = if (expectedRevision != revision) this
    else copy(saving = false, failed = failureBaseline?.let { it != failures } ?: false)
  fun readFailed(expectedRevision: Int) = if (expectedRevision != revision) this
    else copy(saving = false, failed = true)
}
