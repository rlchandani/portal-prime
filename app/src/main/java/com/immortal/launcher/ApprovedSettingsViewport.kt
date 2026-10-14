/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** Session-only viewport memory; editor compositions, drafts and resources are not retained. */
internal class ApprovedSettingsViewportMemory(private val capacity: Int = 32) {
    data class Position(val index: Int = 0, val offset: Int = 0)
    private val positions = LinkedHashMap<String, Position>()

    init { require(capacity > 0) }

    fun read(route: String): Position? = positions[route]

    fun save(route: String, position: Position) {
        require(position.index >= 0 && position.offset >= 0)
        positions.remove(route)
        positions[route] = position
        while (positions.size > capacity) positions.remove(positions.keys.first())
    }
}

internal val LocalApprovedSettingsViewportMemory =
    staticCompositionLocalOf<ApprovedSettingsViewportMemory?> { null }

@Composable
internal fun rememberApprovedSettingsScrollState(routeKey: String): ScrollState {
    val memory = LocalApprovedSettingsViewportMemory.current ?: return rememberScrollState()
    return key(routeKey) {
        val saved = remember(memory) { memory.read("column:$routeKey") }
        val state = rememberScrollState(saved?.offset ?: 0)
        DisposableEffect(memory, state) {
            onDispose {
                memory.save("column:$routeKey", ApprovedSettingsViewportMemory.Position(offset = state.value))
            }
        }
        state
    }
}

@Composable
internal fun rememberApprovedSettingsLazyScrollState(routeKey: String?): LazyListState {
    val memory = LocalApprovedSettingsViewportMemory.current
    if (memory == null || routeKey == null) return rememberLazyListState()
    return key(routeKey) {
        val saved = remember(memory) { memory.read("list:$routeKey") }
        val state = rememberLazyListState(saved?.index ?: 0, saved?.offset ?: 0)
        DisposableEffect(memory, state) {
            onDispose {
                memory.save("list:$routeKey", ApprovedSettingsViewportMemory.Position(
                    state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset))
            }
        }
        state
    }
}
