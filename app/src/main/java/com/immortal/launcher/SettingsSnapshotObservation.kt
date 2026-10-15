/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicBoolean

/** Config-owned subscription; the disposer retains its listener and rejects queued late events. */
internal fun observeSettingsPreferenceChanges(
    store: SharedPreferences,
    keys: Set<String>,
    changed: () -> Unit,
): () -> Unit {
  val active = AtomicBoolean(true)
  val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (active.get() && (key == null || key in keys)) changed()
  }
  store.registerOnSharedPreferenceChangeListener(listener)
  return {
    if (active.compareAndSet(true, false)) store.unregisterOnSharedPreferenceChangeListener(listener)
  }
}
