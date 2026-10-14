/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

internal data class ApprovedTool(
    val id: String, val title: String, val description: String,
    val group: String, val glyph: String, val tint: Long,
)

internal object ApprovedToolsCatalog {
    val all = listOf(
        ApprovedTool("alarm", "Alarm", "Wake up on your schedule.", "Everyday", "bell", 0xFFEEC184),
        ApprovedTool("timers", "Timers", "A few minutes, or a few hours.", "Everyday", "timer", 0xFFEEC184),
        ApprovedTool("countdowns", "Countdowns", "Keep a date worth looking forward to.", "Everyday", "cal", 0xFFA9C9F5),
        ApprovedTool("converter", "Converter", "Measurements and currencies.", "Everyday", "calc", 0xFFA9C9F5),
        ApprovedTool("lamp", "Lamp", "A little light for your room.", "Everyday", "bulb", 0xFFEED6A6),
        ApprovedTool("notes", "Leave a note", "A message for whoever comes next.", "Everyday", "notes", 0xFFEED6A6),
        ApprovedTool("camera", "Cameras", "Your saved home camera views.", "Home & downtime", "cameraLens", 0xFFB4DCD0),
        ApprovedTool("intercom", "Intercom", "Broadcast or listen across the house.", "Home & downtime", "megaphone", 0xFFB4DCD0),
        ApprovedTool("stories", "Bedtime story", "Something gentle before sleep.", "Home & downtime", "book", 0xFFC4B6E7),
        ApprovedTool("iss", "ISS passes", "Know when to look up.", "Discover", "globe", 0xFFA9C9F5),
        ApprovedTool("aurora", "Aurora outlook", "Check the northern lights forecast.", "Discover", "weather", 0xFFB4DCD0),
        ApprovedTool("speed", "Speed test", "Check your connection.", "Advanced", "wifi", 0xFFA9C9F5),
    )
    val groups = listOf("Everyday", "Home & downtime", "Discover", "Advanced")

    private val allCategories = listOf("All tools") + groups
    private val basicCategories = allCategories.filter { it != "Advanced" }
    fun categories(advanced: Boolean): List<String> = if (advanced) allCategories else basicCategories

    /** A restored or hidden category must always have a visible selection in the library. */
    fun resolveGroup(group: String, advanced: Boolean): String =
        group.takeIf { it in categories(advanced) } ?: "All tools"

    fun visible(advanced: Boolean, group: String = "All tools", query: String = ""): List<ApprovedTool> {
        val term = query.trim()
        return all.filter {
            (advanced || it.group != "Advanced") &&
                (group == "All tools" || it.group == group) &&
                (term.isEmpty() || "${it.title} ${it.description}".contains(term, ignoreCase = true))
        }
    }
    fun resolve(id: String?, advanced: Boolean): ApprovedTool? =
        all.firstOrNull { it.id == id && (advanced || it.group != "Advanced") }
}
