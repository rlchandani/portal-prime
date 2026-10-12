/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsDomains
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal sealed interface SunriseSettingEdit {
  data class Scalar(val key: String, val value: Any) : SunriseSettingEdit
  data class Days(val values: Set<Int>) : SunriseSettingEdit
}

/** Accepted edits outlive their pane; unrelated scalar fields are never rewritten. */
internal class SunriseSettingsWriteQueue(apply: (SunriseSettingEdit) -> Boolean) {
  private val failures = AtomicLong()
  private val lastFailed = AtomicBoolean()
  private val writes = LatestSystemWrites(
    keyOf = { edit: SunriseSettingEdit -> when (edit) {
      is SunriseSettingEdit.Scalar -> "scalar:${edit.key}"
      is SunriseSettingEdit.Days -> "days"
    } },
    onFailure = { failures.incrementAndGet(); lastFailed.set(true) },
  ) { edit ->
    val saved = apply(edit)
    lastFailed.set(!saved)
    if (!saved) failures.incrementAndGet()
  }
  val failureCount: Long get() = failures.get()
  val lastSaveFailed: Boolean get() = lastFailed.get()
  fun submit(key: String, value: Any) = writes.submit(SunriseSettingEdit.Scalar(key, value))
  fun submitDays(days: Set<Int>) = writes.submit(SunriseSettingEdit.Days(days.toSet()))
  suspend fun awaitIdle() = writes.awaitIdle()
}

/** Keep the bespoke day group and the registry's single alarm hook under one ordering lock. */
internal fun applySunriseSettingEdit(context: Context, edit: SunriseSettingEdit,
    domain: SettingsDomain<SunriseConfig.Config> = SettingsDomains.sunrise): Boolean = when (edit) {
  is SunriseSettingEdit.Scalar -> edit.key in domain.apply(context, JSONObject().put(edit.key, edit.value))
  is SunriseSettingEdit.Days -> if (edit.values.any { it !in 1..7 }) false else synchronized(domain) {
    SunriseConfig.setDays(context, edit.values)
    // Days are a bespoke set, not a registry scalar. Reaffirm the existing enabled value to
    // invoke the same registry hook once, after the new days are stored. Do not toggle it.
    val enabled = SunriseConfig.load(context).enabled
    "enabled" in domain.apply(context, JSONObject().put("enabled", enabled))
  }
}

/** One process writer retains the application only; cancelling a read never cancels a save. */
internal object SunriseSettingsWrites {
  @Volatile private var instance: SunriseSettingsWriteQueue? = null
  fun queue(context: Context): SunriseSettingsWriteQueue = instance ?: synchronized(this) {
    instance ?: run {
      val application = context.applicationContext
      SunriseSettingsWriteQueue { applySunriseSettingEdit(application, it) }.also { instance = it }
    }
  }
}
