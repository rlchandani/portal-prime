/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Main-thread document readiness, independent of native first-frame admission. */
internal class WebAppContentRevealGate {
    private var revision = 0L
    private var awaitingStart = false
    private var revealed = false
    private var destroyed = false

    fun request(url: String, currentUrl: String?) {
        if (destroyed || document(url) == document(currentUrl)) return
        revision++; awaitingStart = true; revealed = false
    }
    fun started() {
        if (destroyed) return
        revision++; awaitingStart = false; revealed = false
    }
    fun capture(url: String?, currentUrl: String?): Long? =
        if (destroyed || awaitingStart || revealed || revision == 0L || url == null ||
            currentUrl == null || document(url) != document(currentUrl)) null else revision

    fun complete(requestId: Long): Boolean {
        if (destroyed || awaitingStart || revealed || revision == 0L || requestId != revision) return false
        revealed = true
        return true
    }
    fun destroy() { destroyed = true; revision++ }
    private fun document(url: String?): String? = url?.substringBefore('#')
}
