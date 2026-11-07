/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/**
 * Share to Portal settings (Settings → Sharing and the phone remote, via [ShareSettings.domain]).
 * Defaults are the approved prototype's recommendations.
 */
object ShareConfig {
  private const val PREFS = "immortal_share"

  data class Settings(
      /** Paired phones may send photos, notes and links here ("Sharing allowed"). */
      val allowSharing: Boolean = true,
      /** Notes clear at 4:00 AM unless dismissed; cleared notes stay under Tools → Shared from phones. */
      val clearNotesOvernight: Boolean = true,
      /** A new photo's first turn carries "New from <phone> · just now". */
      val freshPhotoBadge: Boolean = true,
      /** One soft chime when a note arrives, never in quiet hours. Photos stay silent. */
      val noteChime: Boolean = true,
  )

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): Settings {
    val p = prefs(context)
    return Settings(
        allowSharing = p.getBoolean("allow_sharing", true),
        clearNotesOvernight = p.getBoolean("clear_notes_overnight", true),
        freshPhotoBadge = p.getBoolean("fresh_photo_badge", true),
        noteChime = p.getBoolean("note_chime", true),
    )
  }

  fun setAllowSharing(c: Context, on: Boolean) = prefs(c).edit().putBoolean("allow_sharing", on).apply()
  fun setClearNotesOvernight(c: Context, on: Boolean) = prefs(c).edit().putBoolean("clear_notes_overnight", on).apply()
  fun setFreshPhotoBadge(c: Context, on: Boolean) = prefs(c).edit().putBoolean("fresh_photo_badge", on).apply()
  fun setNoteChime(c: Context, on: Boolean) = prefs(c).edit().putBoolean("note_chime", on).apply()
}
