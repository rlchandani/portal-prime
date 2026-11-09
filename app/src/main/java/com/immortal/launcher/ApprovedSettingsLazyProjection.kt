/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

internal enum class ApprovedSettingsLazyKind { HEADER, CONTROL, GAP }

internal data class ApprovedSettingsLazyItem<T>(
    val id: String,
    val kind: ApprovedSettingsLazyKind,
    val value: T? = null,
    val section: String? = null,
    val firstGroup: Boolean = false,
    val firstRow: Boolean = false,
    val lastRow: Boolean = false,
)

internal data class ApprovedSettingsLazyProjection<T>(
    val items: List<ApprovedSettingsLazyItem<T>>,
    val controlIndices: Map<String, Int>,
)

/** Receives already-filtered groups; indices include headers and gaps, but never hidden controls. */
internal fun <T> approvedSettingsLazyProjection(
    domain: String,
    groups: Map<String?, List<T>>,
    keyOf: (T) -> String,
): ApprovedSettingsLazyProjection<T> {
    val items = ArrayList<ApprovedSettingsLazyItem<T>>()
    val indices = LinkedHashMap<String, Int>()
    var groupIndex = 0
    groups.forEach { (section, controls) ->
        if (controls.isEmpty()) return@forEach
        val groupId = section ?: "\u0000"
        if (section != null) items += ApprovedSettingsLazyItem(
            "$domain:header:$groupId", ApprovedSettingsLazyKind.HEADER,
            section = section, firstGroup = groupIndex == 0,
        )
        controls.forEachIndexed { index, value ->
            val id = "$domain.${keyOf(value)}"
            require(id !in indices) { "Duplicate settings control: $id" }
            indices[id] = items.size
            items += ApprovedSettingsLazyItem(
                id, ApprovedSettingsLazyKind.CONTROL, value,
                firstRow = index == 0, lastRow = index == controls.lastIndex,
            )
        }
        items += ApprovedSettingsLazyItem("$domain:gap:$groupId", ApprovedSettingsLazyKind.GAP)
        groupIndex++
    }
    return ApprovedSettingsLazyProjection(items, indices)
}
