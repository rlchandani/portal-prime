/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicLong

internal data class ScreensaverSettingEdit(val domainId: String, val key: String, val value: Any)

/** Discrete edits share one process-owned IO writer; latest pending values retain event order. */
internal class ScreensaverSettingsWriteQueue(apply: (ScreensaverSettingEdit) -> Boolean) {
  private val failures = AtomicLong()
  private val writes = LatestSystemWrites(
    keyOf = { edit: ScreensaverSettingEdit -> edit.domainId to edit.key },
    onFailure = { failures.incrementAndGet() },
  ) { edit -> if (!apply(edit)) failures.incrementAndGet() }
  val failureCount: Long get() = failures.get()
  fun submit(edit: ScreensaverSettingEdit) = writes.submit(edit)
  suspend fun awaitIdle() = writes.awaitIdle()
}

/** Never retain an Activity; the registry still owns validation, locking and side effects. */
internal object ScreensaverSettingsWrites {
  @Volatile private var instance: ScreensaverSettingsWriteQueue? = null
  fun queue(context: android.content.Context): ScreensaverSettingsWriteQueue = instance ?: synchronized(this) {
    instance ?: run {
      val application = context.applicationContext
      ScreensaverSettingsWriteQueue { edit ->
        check(edit.domainId == "screensaver" || edit.domainId == "calendar")
        val applied = com.immortal.launcher.settings.SettingsRegistry.apply(application,
          edit.domainId, org.json.JSONObject().put(edit.key, edit.value))
        applied?.contains(edit.key) == true
      }.also { instance = it }
    }
  }
}
