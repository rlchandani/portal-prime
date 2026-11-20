/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import java.io.InputStream
import org.json.JSONObject

/**
 * The built-in feed's sources, tried in turn by [PhotoFrameWebChain]. The default feed (and the
 * fallback for any source that's unset, empty or unreachable) walks:
 *   1. Unsplash  — only when an API key was supplied (richest, but rate-limited)
 *   2. Picsum    — keyless; the historical default
 *   3. Wikimedia — Commons "Featured pictures of landscapes"; keyless, and on infrastructure
 *                  independent of Picsum (the point of having it)
 *   4. Bundled   — CC0/public-domain photos shipped in the APK; can't fail, so a fresh device
 *                  with no network yet — or a day every web source is down — is never blank.
 */
internal class PhotoFrameWebFeed(
    private val context: Context,
    private val transport: PhotoFrameTransport,
    private val clock: PhotoFrameClock,
    private val unsplashKey: String,
    private val unsplashQuery: String,
    /** A bounded photo download for a request of the given generation. */
    private val download: (String, Long) -> Bitmap?,
    private val decodeStream: (InputStream) -> Bitmap?,
    /** The test source is on: every photo is a generated scene ([testPhoto]). */
    private val testSource: () -> Boolean,
    private val testPhoto: () -> Bitmap,
) {
  // Wikimedia Commons featured-landscape image list: fetched once per session, then cycled.
  private var wikimediaUrls: List<String> = emptyList()
  private var wikimediaIdx = 0
  // Bundled offline photos (assets/[FALLBACK_DIR]): shuffled once, then cycled.
  private var bundledNames: List<String> = emptyList()
  private var bundledIdx = 0

  /** The chain in order, each fetch for a request generation. */
  val sources: List<Pair<String, (Long) -> Bitmap?>> by lazy {
    buildList {
      if (unsplashKey.isNotBlank()) add("unsplash" to ::unsplash)
      add("picsum" to ::picsum)
      add("wikimedia" to ::wikimedia)
      add("bundled" to { generation: Long -> if (transport.current(generation)) bundled() else null })
    }
  }

  private fun picsum(generation: Long): Bitmap? {
    val m = context.resources.displayMetrics
    return download(PhotoFrameFeedUrls.picsum(m.widthPixels, m.heightPixels, clock.wallMs()), generation)
  }

  private fun unsplash(generation: Long): Bitmap? {
    val m = context.resources.displayMetrics
    // Match the Unsplash crop to the screen aspect so portrait panels (e.g. Portal
    // Mini at 800x1280) don't get a letterboxed/upscaled landscape shot.
    val orientation = PhotoFrameFeedUrls.unsplashOrientation(m.widthPixels, m.heightPixels)
    val json = transport.httpGet(PhotoFrameFeedUrls.unsplash(orientation, unsplashQuery, unsplashKey), generation)
    return download(JSONObject(json).getJSONObject("urls").getString("regular"), generation)
  }

  /**
   * Keyless Wikimedia Commons feed: the curated "Featured pictures of landscapes"
   * category. The file list is fetched once per session (then shuffled and cycled),
   * so steady state is just a thumbnail download from Wikimedia's upload CDN.
   */
  private fun wikimedia(generation: Long): Bitmap? {
    if (wikimediaUrls.isEmpty()) wikimediaUrls = fetchWikimediaUrls(generation)
    if (!transport.current(generation)) return null
    if (wikimediaUrls.isEmpty()) return null
    val url = wikimediaUrls[wikimediaIdx % wikimediaUrls.size]
    wikimediaIdx++
    return download(url, generation)
  }

  private fun fetchWikimediaUrls(generation: Long): List<String> =
      wikimediaThumbnails(transport.httpGet(PhotoFrameFeedUrls.wikimedia(context.resources.displayMetrics.widthPixels), generation))
          .toMutableList().apply { shuffle() }

  /** Terminal fallback: CC0/public-domain photos bundled in the APK — never fails. */
  fun bundled(): Bitmap? {
    if (testSource()) return testPhoto()
    if (bundledNames.isEmpty()) {
      bundledNames =
          runCatching {
                context.assets.list(FALLBACK_DIR)?.filter { it.endsWith(".jpg", ignoreCase = true) }
              }
              .getOrNull()
              .orEmpty()
              .shuffled()
    }
    if (bundledNames.isEmpty()) return null
    val name = bundledNames[bundledIdx % bundledNames.size]
    bundledIdx++
    return runCatching {
          context.assets.open("$FALLBACK_DIR/$name").use { decodeStream(it) }
        }
        .getOrNull()
  }

  companion object {
    // Bundled fallback photos live under app/src/main/assets/<FALLBACK_DIR>/.
    const val FALLBACK_DIR = "photoframe_fallback"

    /** The thumbnail (else original) URLs in a Commons category listing, in listing order. */
    fun wikimediaThumbnails(json: String): List<String> {
      val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
      val urls = ArrayList<String>()
      for (key in pages.keys()) {
        val info = pages.getJSONObject(key).optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
        val url = info.optString("thumburl").ifBlank { info.optString("url") }
        if (url.isNotBlank()) urls.add(url)
      }
      return urls
    }
  }
}
