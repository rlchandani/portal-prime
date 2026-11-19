/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.Log

/**
 * The bundled Hearth type pair (assets/hearth, SIL OFL 1.1): Plus Jakarta Sans as the display
 * face, instanced at an exact weight from its variable file, and Instrument Serif. Loaded only
 * by the new screensaver modes and the digital clock screensaver; clock faces resolve fonts by
 * name through [AssetResolver] and never see these files.
 */
internal object HearthFonts {
  private const val DISPLAY = "hearth/PlusJakartaSans.ttf"
  private const val SERIF = "hearth/InstrumentSerif.ttf"
  private const val SERIF_ITALIC = "hearth/InstrumentSerif-Italic.ttf"
  private val cache = HashMap<String, Typeface>()

  fun display(context: Context, weight: Int): Typeface = synchronized(cache) {
    val w = weight.coerceIn(200, 800)
    cache.getOrPut("d$w") { load(context, DISPLAY, w) ?: fallback(Typeface.SANS_SERIF, w) }
  }

  fun serif(context: Context, italic: Boolean = false): Typeface = synchronized(cache) {
    cache.getOrPut(if (italic) "si" else "s") {
      load(context, if (italic) SERIF_ITALIC else SERIF, 400)
          ?: Typeface.create(Typeface.SERIF, if (italic) Typeface.ITALIC else Typeface.NORMAL)
    }
  }

  private fun load(context: Context, path: String, weight: Int): Typeface? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Typeface.Builder(context.assets, path)
          .setFontVariationSettings("'wght' $weight")
          .setWeight(weight)
          .build()
    } else Typeface.createFromAsset(context.assets, path)
  }.onFailure { Log.w("HearthFonts", "bundled font unavailable: $path", it) }.getOrNull()

  private fun fallback(base: Typeface, weight: Int): Typeface =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, false)
      else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
}
