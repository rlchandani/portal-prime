/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings reopens on the last page used. This is navigation state, not a user setting, so it has
 * no registry spec and is never exposed to the phone remote.
 */
internal object SettingsLastPage {
    private const val PREFS = "settings_split_view"
    private const val KEY_PAGE = "last_page"

    /** A stored id that is unknown, blank or Advanced-only (with Advanced off) falls back to the default. */
    fun resolve(stored: String?, advanced: Boolean): String = stored
        ?.takeIf(SettingsTree::contains)
        ?.takeIf { advanced || !SettingsTree.needsAdvanced(it) }
        ?: SettingsPageIds.DEFAULT

    /** Only real pages are written; an unchanged id skips the write. */
    fun shouldStore(previous: String?, next: String): Boolean = SettingsTree.contains(next) && previous != next

    fun read(context: Context): String? = prefs(context).getString(KEY_PAGE, null)

    fun write(context: Context, page: String) {
        val prefs = prefs(context)
        if (shouldStore(prefs.getString(KEY_PAGE, null), page)) prefs.edit().putString(KEY_PAGE, page).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
