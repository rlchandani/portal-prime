/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Bounded ownership of a Calls handoff's Meta ambient dismissal. Times are caller-supplied uptime. */
internal class PortalAmbientExitSession(
    private val windowMs: Long = 3_000L,
    private val maxAttempts: Int = 3,
) {
    private class Request(val ticket: Long, val untilMs: Long) {
        var attempts = 0
        var inFlight = false
    }

    private var pendingRequest: Request? = null

    val pending: Boolean
        @Synchronized get() = pendingRequest != null

    @Synchronized
    fun request(ticket: Long, nowMs: Long) {
        pendingRequest = Request(ticket, nowMs + windowMs)
    }

    /** Null (including a disappearing window's exception) leaves the click budget untouched. */
    @Synchronized
    fun attempt(
        currentTicket: Long,
        portalHomeHandoff: Boolean,
        nowMs: Long,
        tryDismiss: () -> Boolean?,
    ): Boolean {
        val request = pendingRequest ?: return false
        if (request.ticket != currentTicket || !portalHomeHandoff || nowMs >= request.untilMs) {
            pendingRequest = null
            return false
        }
        // JVM monitors are reentrant; a callback must not dismiss this same request twice.
        if (request.inFlight) return false
        request.inFlight = true
        val result = runCatching { tryDismiss() }.getOrNull()
        request.inFlight = false

        // A callback can start a new request, even with the same ticket. Only finish our owner.
        if (pendingRequest === request) {
            when (result) {
                true -> pendingRequest = null
                false -> {
                    request.attempts++
                    if (request.attempts >= maxAttempts) pendingRequest = null
                }
                null -> Unit
            }
        }
        return result == true
    }
}
