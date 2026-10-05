/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Main-thread owner: distinguish a pending button request from focus-driven pager movement. */
internal class ApprovedHomePagerNavigation(initialPage: Int = 0) {
    private var target = initialPage
    private var pending = false

    fun request(page: Int) {
        target = page
        pending = true
    }

    fun reflectParent(page: Int) {
        if (target != page) request(page)
    }

    /** Handles a request already at its target, which may produce no snapshotFlow event. */
    fun acknowledge(actualPage: Int) {
        if (pending && actualPage == target) pending = false
    }

    fun observe(actualPage: Int): Int? {
        if (pending) {
            acknowledge(actualPage)
            return null
        }
        if (actualPage == target) return null
        target = actualPage
        return actualPage
    }
}
