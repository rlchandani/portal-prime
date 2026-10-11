/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Routes retain navigation history; resource-owning editor compositions do not stay hidden. */
internal data class ApprovedSettingsHistory(
    val entries: List<ApprovedSettingsRoute>,
    val position: Int = entries.lastIndex,
) {
    init {
        require(entries.isNotEmpty()) { "Settings history must contain at least one route" }
        require(position in entries.indices) { "Settings history position must be in range" }
    }

    val current: ApprovedSettingsRoute get() = entries[position]
    val canGoBack: Boolean get() = position > 0
    val canGoForward: Boolean get() = position < entries.lastIndex

    fun navigate(next: ApprovedSettingsRoute): ApprovedSettingsHistory {
        if (next == current) return this
        val visited = entries.take(position + 1)
        val updated = if (next.detailKey == current.detailKey) visited.dropLast(1) + next
            else visited + next
        return ApprovedSettingsHistory(updated.takeLast(32))
    }

    fun back(allowed: (ApprovedSettingsRoute) -> Boolean = { true }): ApprovedSettingsHistory {
        for (index in position - 1 downTo 0) {
            if (allowed(entries[index])) return copy(position = index)
        }
        return this
    }

    fun forward(allowed: (ApprovedSettingsRoute) -> Boolean = { true }): ApprovedSettingsHistory {
        for (index in position + 1..entries.lastIndex) {
            if (allowed(entries[index])) return copy(position = index)
        }
        return this
    }

    companion object {
        fun initial(route: ApprovedSettingsRoute) = ApprovedSettingsHistory(
            if (route.page == null) listOf(route)
            else listOf(ApprovedSettingsRoute(category = route.category), route),
        )
    }
}
