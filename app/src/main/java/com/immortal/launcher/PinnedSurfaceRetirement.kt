/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.IdentityHashMap

/** Lost-panel installation, retirement retries and service close share this exact renderer. */
internal class PinnedRendererLifetime(private val stop: () -> Unit) {
    var stopped = false
        private set
    var failed = false
        private set
    fun stopOnce() {
        if (stopped) return
        stopped = true // admission precedes a throwing or reentrant stop
        failed = runCatching(stop).isFailure
    }
}

internal enum class PinnedRetirementResult { DONE, RETRY, FAILED, IGNORED }

/**
 * Main-thread ownership ledger. The scheduler is outside this class; there is no timer/polling here.
 * Every operation receives the captured old surface. Failed removal retains its ticket even when
 * an attach probe is uncertain. Only successful removal + detachment, or an actual detach event,
 * consumes ownership. Attempt exhaustion leaves an honest failed record and blocks more covers.
 */
internal class PinnedSurfaceRetirement<S : Any>(
    private val hide: (S) -> Unit,
    private val remove: (S) -> Unit,
    private val attached: (S) -> Boolean,
    private val attemptLimit: Int = 3,
    private val retainedLimit: Int = 3,
) {
    class Ticket<S : Any> internal constructor(val surface: S, internal val renderer: PinnedRendererLifetime) {
        internal var hidden = false
        internal var attempts = 0
        internal var finalAttempted = false
    }
    private val pending = IdentityHashMap<S, Ticket<S>>()
    init { require(attemptLimit > 0 && retainedLimit > 0) }
    val pendingCount get() = pending.size
    val failedCount get() = pending.values.count { it.attempts >= attemptLimit || it.finalAttempted }
    val canCreateCover get() = pendingCount < retainedLimit && failedCount == 0
    fun owns(ticket: Ticket<S>) = pending[ticket.surface] === ticket

    fun retire(surface: S, renderer: PinnedRendererLifetime): Ticket<S> {
        pending[surface]?.let { return it }
        val ticket = Ticket(surface, renderer)
        pending[surface] = ticket
        ticket.hidden = runCatching { hide(surface) }.isSuccess
        return ticket
    }

    fun attempt(ticket: Ticket<S>): PinnedRetirementResult {
        if (!owns(ticket)) return PinnedRetirementResult.IGNORED
        if (ticket.finalAttempted || ticket.attempts >= attemptLimit) return PinnedRetirementResult.FAILED
        ticket.attempts++
        return removeCaptured(ticket)
    }

    /** Service unbind gets one final pass; repeated close/callbacks cannot reset the retry budget. */
    fun drainOnce(ticket: Ticket<S>): PinnedRetirementResult {
        if (!owns(ticket) || ticket.finalAttempted) return PinnedRetirementResult.IGNORED
        ticket.finalAttempted = true
        return removeCaptured(ticket)
    }

    private fun removeCaptured(ticket: Ticket<S>): PinnedRetirementResult {
        if (!ticket.hidden) ticket.hidden = runCatching { hide(ticket.surface) }.isSuccess
        ticket.renderer.stopOnce()
        val removed = runCatching { remove(ticket.surface) }.isSuccess
        // A detach listener can consume this ticket reentrantly during removeViewImmediate.
        if (!owns(ticket)) return PinnedRetirementResult.DONE
        if (removed && definitelyDetached(ticket.surface)) {
            pending.remove(ticket.surface)
            return PinnedRetirementResult.DONE
        }
        return if (ticket.finalAttempted || ticket.attempts >= attemptLimit)
            PinnedRetirementResult.FAILED else PinnedRetirementResult.RETRY
    }

    /** Called only for an actual View detach event, not speculative missing window metadata. */
    fun detached(ticket: Ticket<S>): Boolean {
        if (!owns(ticket) || !definitelyDetached(ticket.surface)) return false
        ticket.renderer.stopOnce()
        pending.remove(ticket.surface)
        return true
    }

    private fun definitelyDetached(surface: S) = runCatching { !attached(surface) }.getOrDefault(false)
}
