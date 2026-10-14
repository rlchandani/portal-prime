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
    private var failed = false
    private var targetDocument: String? = null

    fun request(url: String, currentUrl: String?, force: Boolean = false): Boolean {
        if (destroyed || (!force && document(url) == document(currentUrl))) return false
        revision++; awaitingStart = true; revealed = false; failed = false
        targetDocument = document(url)
        return true
    }
    fun started(url: String? = null): Boolean {
        // Error-document callbacks cannot clear a failed attempt; retry/new entry must admit it.
        if (destroyed || failed) return false
        revision++; awaitingStart = false; revealed = false
        if (url != null) targetDocument = document(url)
        return true
    }
    fun fail(url: String?, currentUrl: String?): Boolean {
        if (destroyed || failed || revision == 0L || url == null) return false
        val expected = targetDocument ?: document(currentUrl) ?: return false
        if (document(url) != expected) return false
        revision++; failed = true; revealed = false
        return true
    }
    fun capture(url: String?, currentUrl: String?): Long? =
        if (destroyed || failed || awaitingStart || revealed || revision == 0L || url == null ||
            currentUrl == null || document(url) != document(currentUrl)) null else revision

    fun complete(requestId: Long): Boolean {
        if (destroyed || failed || awaitingStart || revealed || revision == 0L || requestId != revision) return false
        revealed = true
        return true
    }
    fun destroy() { destroyed = true; revision++ }
    private fun document(url: String?): String? = url?.substringBefore('#')
}
