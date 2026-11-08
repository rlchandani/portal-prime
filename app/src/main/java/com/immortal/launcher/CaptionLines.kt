/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * What the screensaver caption shows. Place, date and the "years ago today" memory are photo
 * metadata and follow the face's caption setting; the fresh-photo "New from …" badge has its own
 * setting (ShareConfig) and shows even when captions are off. Null means hide the caption.
 */
internal object CaptionLines {
  data class Lines(val badge: String?, val place: String?, val date: String?)

  fun resolve(metadataOn: Boolean, place: String?, date: String?, memory: String?, fresh: String?): Lines? {
    fun String?.clean() = this?.takeIf { it.isNotBlank() }
    val p = place.clean().takeIf { metadataOn }
    val d = date.clean().takeIf { metadataOn }
    val badge = fresh.clean() ?: memory.clean().takeIf { metadataOn }
    return if (badge == null && p == null && d == null) null else Lines(badge, p, d)
  }
}
