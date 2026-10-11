/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Looper
import androidx.lifecycle.Lifecycle

/** A local Back owns one live host; a rejected admission leaves platform Back to the caller. */
internal class ApprovedSettingsBackDispatcher<H : Any>(
    private val eligible: (H) -> Boolean,
    private val back: (H) -> Boolean,
) {
    private val hosts = ApprovedHomeHostRegistry<H>()

    fun resumed(host: H) = hosts.resumed(host)
    fun unavailable(host: H) = hosts.unavailable(host)

    fun dispatch(): Boolean {
        val host = hosts.foreground(hosts.capture(), eligible) ?: return false
        return back(host)
    }
}

/** The pin manager invokes its fallback on main, after deciding whether to dismiss a cover. */
internal object ApprovedSettingsRemoteBack {
    private val dispatcher = ApprovedSettingsBackDispatcher<ImmortalSettingsActivity>(
        eligible = {
            Looper.myLooper() == Looper.getMainLooper() &&
                !ImmortalDreamNavigation.hasOwnedSurface() && approvedRetainedHomeEligible(
                resumed = it.lifecycle.currentState == Lifecycle.State.RESUMED,
                focused = it.hasWindowFocus(), destroyed = it.isDestroyed, finishing = it.isFinishing,
                stockHandoff = DreamPolicy.inStockHandoff,
            )
        },
        back = { it.handleRemoteSettingsBack() },
    )

    fun resumed(host: ImmortalSettingsActivity) = dispatcher.resumed(host)
    fun unavailable(host: ImmortalSettingsActivity) = dispatcher.unavailable(host)

    fun dispatch(): Boolean = dispatcher.dispatch()
}
