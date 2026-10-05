/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.URI
import java.util.Locale

internal fun approvedEditorsCredential(draft: String, stored: String): String =
    draft.trim().ifBlank { stored }

internal fun approvedEditorsHttpUrl(value: String): Boolean = runCatching {
    val uri = URI(value.trim())
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)

internal fun approvedEditorsCryptoSymbol(id: String, symbol: String): String =
    symbol.trim().ifBlank { id.trim() }.uppercase(Locale.ROOT)

/** All request transitions occur on the UI thread, including the final commit check. */
internal class ApprovedEditorsRequestGate {
    private var revision = 0L
    fun begin(): Long = ++revision
    fun cancel() { revision++ }
    fun isCurrent(request: Long): Boolean = request == revision
}

internal data class ApprovedEditorsEntity(val id: String, val name: String) {
    val domain = id.substringBefore('.', "")
    val searchText = "$name\n$id".lowercase(Locale.ROOT)
}

internal data class ApprovedEditorsHaGroup(
    val domain: String, val total: Int, val expanded: Boolean,
    val shown: List<ApprovedEditorsEntity>, val hidden: Int,
)

private val APPROVED_EDITORS_HA_ORDER = listOf(
    "light", "switch", "fan", "scene", "script", "automation", "input_boolean",
    "climate", "cover", "media_player", "binary_sensor", "sensor", "person",
    "device_tracker", "weather", "sun")

internal fun approvedEditorsHaGroups(
    entities: List<ApprovedEditorsEntity>, query: String, expandedDomains: Set<String>,
): List<ApprovedEditorsHaGroup> {
    val q = query.trim().lowercase(Locale.ROOT)
    val matching = if (q.isEmpty()) entities else entities.filter { q in it.searchText }
    return matching.groupBy { it.domain }.entries.sortedWith(
        compareBy({ APPROVED_EDITORS_HA_ORDER.indexOf(it.key).let { i ->
            if (i < 0) APPROVED_EDITORS_HA_ORDER.size else i
        } }, { it.key })
    ).map { (domain, group) ->
        val expanded = q.isNotEmpty() || domain in expandedDomains
        val shown = if (!expanded) emptyList() else if (q.isNotEmpty()) group else group.take(12)
        ApprovedEditorsHaGroup(domain, group.size, expanded, shown,
            if (expanded) group.size - shown.size else 0)
    }
}
