/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.coroutineContext

/**
 * One-shot transfer to the existing Activity's entity destination. This avoids repeating a
 * successful discovery just to change screens; it is cleared on read and never persisted.
 */
internal object ApprovedEditorsHaTransfer {
    private data class Result(
        val url: String, val token: String, val entities: List<ApprovedEditorsEntity>,
    )
    private var result: Result? = null

    fun publish(url: String, token: String, entities: List<ApprovedEditorsEntity>) {
        result = Result(url, token, entities)
    }

    fun take(url: String, token: String): List<ApprovedEditorsEntity>? {
        val available = result
        result = null
        return available?.takeIf { it.url == url && it.token == token }?.entities
    }
}

/**
 * Probe a draft without temporarily replacing the live connection. Http and HaClient's parser
 * remain the existing transport/entity policy; only the credentials' apply boundary moves.
 * A null result is a real failure, while an empty list is a successful, empty household.
 */
internal suspend fun approvedEditorsDiscoverHa(
    baseUrl: String, token: String,
): List<ApprovedEditorsEntity>? = withContext(Dispatchers.IO) {
    val url = baseUrl.trim().trimEnd('/')
    val headers = mapOf("Authorization" to "Bearer ${token.trim()}", "Content-Type" to "application/json")
    val ping = Http.getString("$url/api/", headers = headers) ?: return@withContext null
    coroutineContext.ensureActive()
    if (!ping.contains("API running", ignoreCase = true) &&
        !ping.contains("message", ignoreCase = true)) return@withContext null
    val body = Http.getString("$url/api/states", headers = headers) ?: return@withContext null
    coroutineContext.ensureActive()
    runCatching {
        val array = JSONArray(body)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(HaClient::parseState)
        }.filter { it.toggleable || it.activatable || it.readable }
            .distinctBy { it.entityId }
            .sortedBy { it.friendlyName.lowercase() }
            .map { ApprovedEditorsEntity(it.entityId, it.friendlyName) }
    }.getOrNull()
}
