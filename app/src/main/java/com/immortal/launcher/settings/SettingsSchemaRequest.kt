/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import android.content.Context
import java.util.IdentityHashMap

/** A typed source shared explicitly by domains that read the same immutable snapshot. */
class SettingsSnapshotSource<S>(private val read: (Context) -> S) {
    fun load(context: Context): S = read(context)
}

/** One complete schema response only; never retained between requests or by native UI. */
internal class SettingsSchemaRequest(private val context: Context) {
    private val snapshots = IdentityHashMap<SettingsSnapshotSource<*>, Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <S> load(source: SettingsSnapshotSource<S>): S {
        // Identity keys preserve the source's type; containsKey also preserves a valid null.
        if (snapshots.containsKey(source)) return snapshots[source] as S
        val value = source.load(context)
        // A failed read cannot populate the request or prevent a later successful attempt.
        snapshots[source] = value
        return value
    }
}
