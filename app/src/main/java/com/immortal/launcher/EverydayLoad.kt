/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

/** Saved values remain usable while an independent refresh reports its progress or failure. */
internal class EverydayLoad<T> {
    private var savedValue by mutableStateOf<T?>(null)
    var generation = 0
        private set
    var value: T?
        get() = savedValue
        set(value) {
            savedValue = value
            generation++
            error = null
        }
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var retry by mutableIntStateOf(0)

    suspend fun refresh(loader: suspend () -> T) {
        if (loading) return
        val startedGeneration = generation
        loading = true
        try {
            val snapshot = loader()
            // A completed user save/start/remove takes precedence over an older refresh.
            if (generation == startedGeneration) value = snapshot
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation == startedGeneration) {
                error = if (value == null) "Couldn’t load your saved data."
                else "Couldn’t refresh your saved data. Showing the last loaded values."
            }
        } finally {
            loading = false
        }
    }
}
