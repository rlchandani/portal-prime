/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Immutable rate limit for `/remote/pair` guesses. Two layers:
 *
 *  - **Per source address:** after [IP_FAILURES] wrong guesses an address waits [IP_BLOCK_BASE_MS],
 *    doubling on each repeat up to [IP_BLOCK_MAX_MS]. One noisy device can't burn the code alone.
 *  - **Global:** the code itself burns after [PIN_FAILURES] wrong guesses from any mix of
 *    addresses (online brute force stays at 5 guesses per 10^6-code PIN, as before), then every
 *    address waits a cooldown of [COOLDOWN_BASE_MS], doubling up to [COOLDOWN_MAX_MS].
 *
 * Neither wait is ever longer than its cap, so the owner is never locked out for good: the Portal
 * shows the wait, then the owner can generate a fresh code (never automatically, so an attacker
 * can't harvest a stream of codes from an unattended card). Escalation is forgotten after [FORGET_MS]
 * of quiet or a successful pairing. Only guesses against a live code count.
 */
internal data class PairingThrottle(
    val addresses: Map<String, Address> = emptyMap(),
    val pinFailures: Int = 0,
    val cooldownUntilMs: Long = 0L,
    val cooldownLevel: Int = 0,
    val lastCooldownMs: Long = 0L,
) {
  data class Address(val failures: Int, val lockouts: Int, val blockedUntilMs: Long, val lastFailureMs: Long)

  sealed interface Decision {
    data object Allowed : Decision
    /** [global] when the whole pairing is cooling down, not just this address. */
    data class Limited(val retryAfterMs: Long, val global: Boolean) : Decision
  }

  /** The outcome of a wrong guess: the new state and whether the code is now burned. */
  data class Failure(val throttle: PairingThrottle, val burned: Boolean)

  fun check(address: String, now: Long): Decision {
    if (now < cooldownUntilMs) return Decision.Limited(cooldownUntilMs - now, global = true)
    val blockedUntil = addresses[address]?.blockedUntilMs ?: 0L
    return if (now < blockedUntil) Decision.Limited(blockedUntil - now, global = false) else Decision.Allowed
  }

  /** Remaining global cooldown, for the on-Portal "wait" message. */
  fun cooldownRemainingMs(now: Long): Long = (cooldownUntilMs - now).coerceAtLeast(0L)

  fun onFailure(address: String, now: Long): Failure {
    val previous = addresses[address]?.takeIf { now - it.lastFailureMs < FORGET_MS }
    val failures = (previous?.failures ?: 0) + 1
    val lockouts = previous?.lockouts ?: 0
    val entry = if (failures >= IP_FAILURES) {
      Address(0, lockouts + 1, now + backoff(IP_BLOCK_BASE_MS, lockouts, IP_BLOCK_MAX_MS), now)
    } else Address(failures, lockouts, previous?.blockedUntilMs ?: 0L, now)
    val kept = (addresses - address).filterValues { now - it.lastFailureMs < FORGET_MS }
        .entries.sortedByDescending { it.value.lastFailureMs }.take(MAX_ADDRESSES - 1)
        .associate { it.key to it.value } + (address to entry)
    val total = pinFailures + 1
    if (total < PIN_FAILURES) return Failure(copy(addresses = kept, pinFailures = total), burned = false)
    val level = if (now - lastCooldownMs < FORGET_MS) cooldownLevel else 0
    val cooldown = backoff(COOLDOWN_BASE_MS, level, COOLDOWN_MAX_MS)
    return Failure(copy(addresses = kept, pinFailures = 0, cooldownUntilMs = now + cooldown,
        cooldownLevel = level + 1, lastCooldownMs = now), burned = true)
  }

  /** A fresh code starts its own guess budget; waits already earned still apply. */
  fun onNewPin(): PairingThrottle = copy(pinFailures = 0)

  /** Someone with the real code paired: forget the escalation for everybody. */
  fun onSuccess(): PairingThrottle = PairingThrottle()

  companion object {
    const val IP_FAILURES = 3
    const val PIN_FAILURES = 5
    const val IP_BLOCK_BASE_MS = 30_000L
    const val IP_BLOCK_MAX_MS = 10 * 60_000L
    const val COOLDOWN_BASE_MS = 30_000L
    const val COOLDOWN_MAX_MS = 5 * 60_000L
    const val FORGET_MS = 30 * 60_000L
    const val MAX_ADDRESSES = 64
    private const val MAX_DOUBLINGS = 20

    internal fun backoff(base: Long, level: Int, max: Long): Long =
        (base shl level.coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(max)

    /** Whole seconds, rounded up, for a "wait N s" message (never 0 while limited). */
    fun seconds(ms: Long): Long = ((ms + 999) / 1000).coerceAtLeast(1)
  }
}

/** The single household-key grant a PIN pairing creates (see [RemotePairing.redeem]). */
internal data class HouseholdGrant(val sessionId: String, val issuedAtMs: Long)

/**
 * Pure rule for `/remote/household` POST: only the session minted by the latest PIN pairing,
 * within [WINDOW_MS], and only once (the caller drops the grant after a successful share or set).
 */
internal object HouseholdGrantPolicy {
  const val WINDOW_MS = 10 * 60_000L

  fun allows(grant: HouseholdGrant?, caller: String, now: Long): Boolean =
      grant != null && grant.sessionId == caller && now - grant.issuedAtMs in 0..WINDOW_MS
}
