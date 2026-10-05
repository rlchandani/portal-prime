/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Routes retain navigation history; resource-owning editor compositions do not stay hidden. */
internal data class ApprovedSettingsHistory(val entries: List<ApprovedSettingsRoute>) {
    val current: ApprovedSettingsRoute get() = entries.last()

    fun navigate(next: ApprovedSettingsRoute): ApprovedSettingsHistory = when {
        next.page == null -> ApprovedSettingsHistory(listOf(next))
        next == current -> this
        next.detailKey == current.detailKey -> copy(entries = entries.dropLast(1) + next)
        else -> copy(entries = (entries + next).takeLast(32))
    }

    fun back(): ApprovedSettingsHistory = if (entries.size > 1) copy(entries = entries.dropLast(1))
        else initial(ApprovedSettingsRoute(category = current.category))

    companion object {
        fun initial(route: ApprovedSettingsRoute) = ApprovedSettingsHistory(
            if (route.page == null) listOf(route)
            else listOf(ApprovedSettingsRoute(category = route.category), route),
        )
    }
}
