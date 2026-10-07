/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Keep a small set of local controls; audio, camera and network owners are transient. */
internal object ToolsPaneRetention {
    private val local = setOf("alarm", "timers", "countdowns", "converter", "lamp")
    private const val CAPACITY = 4

    fun afterSelection(previous: List<String>, selected: String): List<String> {
        val retained = previous.filter { it in local && it != selected }.distinct()
        return if (selected in local) (retained + selected).takeLast(CAPACITY)
        else retained.takeLast(CAPACITY) + selected
    }
}
