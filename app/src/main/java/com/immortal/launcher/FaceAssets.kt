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
import java.io.File

/**
 * Resolves face assets (fonts today; widget components / imagery later) by id, with a fixed
 * lookup order so the renderer never assumes a bundled file:
 *
 * ```
 *   1. bundled   assets/fonts/…              (shipped in the APK)
 *   2. cache     filesDir/face-assets/…      (previously downloaded)
 *   3. remote    Supabase Storage            (fetch, cache, then use)  ← built later
 * ```
 *
 * Tiers 1–2 are implemented now; tier 3 is a stub. A new Pro face pack will be able to ship a
 * font the installed APK has never seen without an app update — the descriptor and renderer
 * don't change when tier 3 lands, the resolver just gains a fallback. A missing asset always
 * degrades gracefully (nearest system typeface) rather than crashing or blanking the frame.
 */
class AssetResolver(private val context: Context) {

  private val cacheDir: File by lazy { File(context.filesDir, "face-assets").apply { mkdirs() } }
  private val baseTypefaceCache = HashMap<String, Typeface>()
  private val typefaceCache = HashMap<Pair<String, Int>, Typeface>()

  /**
   * Resolve a font family name (a mantelframe `clockFont` value, or [Face.FONT_SANS_LIGHT])
   * to a [Typeface], requesting its native weight on API 28+ and using normal/bold on older
   * platforms. Always returns *something* — falls back to the nearest system family.
   */
  fun font(name: String, weight: Int = 400): Typeface {
    // Imported face descriptors can contain arbitrary weights; keep the native API drawable.
    val nativeWeight = weight.coerceIn(1, 1000)
    val key = name to nativeWeight
    typefaceCache[key]?.let {
      return it
    }
    // Resolve an asset only once per family, even when one face requests several weights.
    val base = baseTypefaceCache.getOrPut(name) { loadFont(name) ?: systemFallback(name) }
    val tf = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      Typeface.create(base, nativeWeight, false)
    } else {
      Typeface.create(base, if (nativeWeight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }
    typefaceCache[key] = tf
    return tf
  }

  private fun loadFont(name: String): Typeface? {
    // Sentinel: the original overlay's light system face, no TTF needed.
    if (name == Face.FONT_SANS_LIGHT) return Typeface.create("sans-serif-light", Typeface.NORMAL)

    val file = fontFileName(name)

    // Tier 1 — bundled in assets/fonts/.
    runCatching { return Typeface.createFromAsset(context.assets, "fonts/$file") }
        .onFailure { /* not bundled; fall through */ }

    // Tier 2 — previously downloaded into the on-device cache.
    val cached = File(cacheDir, file)
    if (cached.exists()) {
      runCatching { return Typeface.createFromFile(cached) }
          .onFailure { Log.w(TAG, "cached font unreadable: $file", it) }
    }

    // Tier 3 — remote (Supabase Storage). Not yet built; see doc § Asset resolution.
    // fetchRemoteFontInto(cached, name)?.let { return it }

    return null
  }

  /** Map a mantelframe font family to its bundled TTF filename. */
  private fun fontFileName(name: String): String = name.replace(" ", "") + ".ttf"

  /** Closest system typeface when a named family isn't available anywhere. */
  private fun systemFallback(name: String): Typeface =
        when (name) {
          "Courier New" -> Typeface.MONOSPACE
          "Georgia",
          "Playfair Display" -> Typeface.SERIF
          else -> Typeface.SANS_SERIF
        }

  private companion object {
    const val TAG = "FaceAssets"
  }
}
