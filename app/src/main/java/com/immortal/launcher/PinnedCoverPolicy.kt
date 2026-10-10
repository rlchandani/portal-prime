/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

internal enum class PinnedCoverMode { DAY, NIGHT }
internal data class PinnedCoverSelection(val mode: PinnedCoverMode, val lostBarrier: Boolean)
internal enum class PinnedRevealDecision { REVEAL, WAIT, LOST, INACTIVE, OBSOLETE }

/** Main-thread policy; missing occluded metadata is unknown until a bounded reveal decision. */
internal class PinnedCoverPolicy {
    private val session = PinnedSessionState()
    @Volatile var expired = false
        private set
    @Volatile var revision = 0L
        private set
    private var lastApplicationPackage: String? = null
    val target get() = session.target
    val pinned get() = expired || target != null
    val lost get() = expired || session.lost

    fun pin(window: PinnedWindowIdentity): Boolean {
        if (!session.pin(window)) return false
        expired = false
        lastApplicationPackage = window.packageName
        revision++
        return true
    }

    fun expire(foreground: PinnedWindowIdentity?) {
        expired = true
        foreground?.takeUnless { ignoredPackage(it.packageName) }?.let {
            lastApplicationPackage = it.packageName
        }
        revision++
    }

    fun release() {
        session.clear()
        expired = false
        lastApplicationPackage = null
        revision++
    }

    /**
     * Caller passes only actual focused/active TYPE_APPLICATION roots. Accessibility curtains and
     * native TYPE_SYSTEM Dreams are absent, so a foreign/Home root is navigation even if covered.
     * Eligibility for pinning is deliberately not used here: Meta's stock Home is also an exit.
     */
    fun observe(windows: List<PinnedWindowIdentity>, foreground: PinnedWindowIdentity?, covered: Boolean): Boolean {
        val application = foreground?.takeUnless { ignoredPackage(it.packageName) }
        val expectedPackage = target?.packageName ?: if (expired) lastApplicationPackage else null
        if (pinned && application != null && expectedPackage != null && application.packageName != expectedPackage) {
            release() // clear the guard before navigation/finish can generate more window events
            lastApplicationPackage = application.packageName
            return true
        }
        val hadTarget = target
        val wasLost = lost
        session.observe(windows, application, covered = covered || expired || session.lost)
        application?.let { lastApplicationPackage = it.packageName }
        if (hadTarget != target || wasLost != lost) revision++
        return hadTarget != null && !pinned
    }

    /** Compute the desired presentation before the host considers reusing an existing cover. */
    fun select(overnight: Boolean, nightEnabled: Boolean): PinnedCoverSelection? {
        if (!pinned) return null
        val mode = when {
            !overnight -> PinnedCoverMode.DAY
            nightEnabled -> PinnedCoverMode.NIGHT
            lost -> PinnedCoverMode.DAY // an explicit lost-session/Home barrier survives a dark window
            else -> return null
        }
        return PinnedCoverSelection(mode, lostBarrier = lost)
    }

    fun reveal(windows: List<PinnedWindowIdentity>, nowMs: Long, graceUntilMs: Long,
        expectedRevision: Long = revision): PinnedRevealDecision {
        if (expectedRevision != revision) return PinnedRevealDecision.OBSOLETE
        if (!pinned) return PinnedRevealDecision.INACTIVE
        if (lost) return PinnedRevealDecision.LOST
        val saved = target ?: return PinnedRevealDecision.LOST
        if (saved in windows) return PinnedRevealDecision.REVEAL
        // A known replacement cannot borrow the settling grace for temporarily missing metadata.
        val replacement = windows.any { it.packageName == saved.packageName }
        if (!replacement && nowMs < graceUntilMs) return PinnedRevealDecision.WAIT
        session.canReveal(windows) // absent/replaced identity latches loss; never resurrect it later
        revision++
        return PinnedRevealDecision.LOST
    }

    companion object {
        private fun ignoredPackage(pkg: String) = pkg == "android" || pkg == "com.android.systemui"
        fun keepScreenOn(mode: PinnedCoverMode, presenceMode: FrameMode, hasBattery: Boolean,
            batterySaver: Boolean, powered: Boolean): Boolean = mode == PinnedCoverMode.NIGHT ||
            DreamPolicy.holdScreenOn(presenceMode, hasBattery, batterySaver, powered)
    }
}
