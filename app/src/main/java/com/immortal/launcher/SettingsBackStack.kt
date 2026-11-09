/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The right column's back stack: a top-level page and at most two sub-pages beneath it. It is
 * replaced (not extended) by sidebar and search navigation, so Back always walks up the tree.
 * [control] is the row the current page should scroll to and highlight, if any.
 */
internal data class SettingsBackStack(
    val pages: List<String>,
    val control: String? = null,
) {
    init {
        require(pages.isNotEmpty()) { "Settings back stack must hold a page" }
        require(pages.size <= MAX_DEPTH) { "Settings back stack is at most $MAX_DEPTH deep" }
    }

    val current: String get() = pages.last()
    val root: String get() = pages.first()
    val canGoBack: Boolean get() = pages.size > 1
    val parent: String? get() = pages.getOrNull(pages.lastIndex - 1)

    /** Sidebar and search: show [page] with its ancestors beneath it. */
    fun select(page: String, control: String? = null): SettingsBackStack = of(page, control)

    /**
     * A link row inside the current page pushes its target, so Back returns here (also for links
     * into another branch, e.g. Photo Frame › Photo source). An already-open page truncates the
     * stack instead of repeating; a full stack restarts from the target's own chain.
     */
    fun open(page: String, control: String? = null): SettingsBackStack {
        if (!SettingsTree.contains(page)) return this
        val existing = pages.indexOf(page)
        if (existing >= 0) return SettingsBackStack(pages.take(existing + 1), control)
        if (pages.size < MAX_DEPTH) return SettingsBackStack(pages + page, control)
        return of(page, control)
    }

    fun back(): SettingsBackStack = if (canGoBack) SettingsBackStack(pages.dropLast(1)) else this

    /** Clears a one-shot highlight once it has been shown, without changing the page. */
    fun withoutControl(): SettingsBackStack = if (control == null) this else copy(control = null)

    companion object {
        const val MAX_DEPTH = 3

        fun of(page: String, control: String? = null): SettingsBackStack {
            val chain = SettingsTree.chainOf(page).ifEmpty { listOf(SettingsPageIds.DEFAULT) }
            return SettingsBackStack(chain.takeLast(MAX_DEPTH), control)
        }

        /** Rebuilds a saved stack; unknown ids are dropped and at most [MAX_DEPTH] pages are kept. */
        fun restore(saved: List<String>?, control: String? = null): SettingsBackStack? =
            saved.orEmpty().filter(SettingsTree::contains).distinct().takeLast(MAX_DEPTH)
                .takeIf { it.isNotEmpty() }?.let { SettingsBackStack(it, control) }
    }
}
