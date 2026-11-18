/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.util.Locale

/** Text form of a [MusicFloatPosition]: `v1:<fx>,<fy>`. Anything else decodes to null. Pure. */
internal object MusicFloatPositionCodec {
  private const val PREFIX = "v1:"
  private const val SEPARATOR = ","

  fun encode(p: MusicFloatPosition): String =
      PREFIX + String.format(Locale.US, "%.4f", p.fx.coerceIn(0f, 1f)) + SEPARATOR +
          String.format(Locale.US, "%.4f", p.fy.coerceIn(0f, 1f))

  fun decode(raw: String?): MusicFloatPosition? {
    if (raw == null || !raw.startsWith(PREFIX)) return null
    val parts = raw.removePrefix(PREFIX).split(SEPARATOR)
    if (parts.size != 2) return null
    val fx = parts[0].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
    val fy = parts[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
    return MusicFloatPosition(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
  }
}

/** Which floating player a saved position belongs to: each remembers its own spot. */
internal enum class MusicFloatKind(val key: String) { CARD("cardPosition"), TILE("tilePosition") }

/** This Portal's saved positions of Home's Now Playing card and its minimized tile (device-local). */
internal object MusicFloatPositionStore {
  private const val PREFS = "immortal_music_home_card"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context, kind: MusicFloatKind): MusicFloatPosition =
      MusicFloatPositionCodec.decode(prefs(c).getString(kind.key, null)) ?: MusicFloatPosition.DEFAULT

  fun save(c: Context, kind: MusicFloatKind, p: MusicFloatPosition) {
    prefs(c).edit().putString(kind.key, MusicFloatPositionCodec.encode(p)).apply()
  }
}
