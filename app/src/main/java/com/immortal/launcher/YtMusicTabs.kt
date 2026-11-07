/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.URI
import java.util.Locale

/** Pure URL helpers for the embedded YouTube Music pane (JVM-tested; no android.net.Uri). */
object YtMusicUrls {
  const val ORIGIN = "https://music.youtube.com"
  const val HOME = "$ORIGIN/"
  const val LIBRARY = "$ORIGIN/library"
  const val SEARCH = "$ORIGIN/search"
  private const val MUSIC_HOST = "music.youtube.com"
  private const val MAX_URL_CHARS = 4096

  /** Google sign-in hosts the pane may show so the user can attempt to sign in once. */
  private val signInHosts = setOf(
      "accounts.google.com", "consent.google.com", "www.google.com", "myaccount.google.com")

  /** Parsed https URL with a host, or null for anything else (untrusted input). */
  internal fun httpsUri(url: String?): URI? {
    if (url.isNullOrBlank() || url.length > MAX_URL_CHARS) return null
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (!"https".equals(uri.scheme, ignoreCase = true)) return null
    if (uri.rawUserInfo != null) return null
    if (uri.port != -1 && uri.port != 443) return null
    if (uri.host.isNullOrBlank()) return null
    return uri
  }

  internal fun host(uri: URI): String = uri.host.lowercase(Locale.ROOT)

  fun isMusicPage(url: String?): Boolean = httpsUri(url)?.let { host(it) == MUSIC_HOST } == true

  /** True when [url] is the YouTube Music player page (where the Up next queue lives). */
  fun isPlayerPage(url: String?): Boolean {
    val uri = httpsUri(url) ?: return false
    return host(uri) == MUSIC_HOST && uri.rawPath == "/watch"
  }

  /** Top-level navigations the pane keeps in place; everything else is refused. */
  fun isAllowedInPane(url: String?): Boolean {
    val uri = httpsUri(url) ?: return false
    val h = host(uri)
    return h == "youtube.com" || h.endsWith(".youtube.com") || h in signInHosts
  }

  /** Secondary caption beside "YouTube Music page (embedded)". */
  fun caption(url: String?): String {
    val uri = httpsUri(url) ?: return MUSIC_HOST
    return if (isPlayerPage(url)) "$MUSIC_HOST, player page" else host(uri)
  }
}

/** The pane's native tabs and where each one lands on the YouTube Music page. */
enum class YtMusicTab(val label: String, val pageKey: String) {
  QUEUE("Queue", "queue"),
  LIBRARY("Library", "library"),
  SEARCH("Search", "search");

  /**
   * The URL to load when in-page navigation is unavailable. Queue keeps the current player page
   * (YouTube Music's own Up next); without one it opens the home page.
   */
  fun targetUrl(currentUrl: String?): String = when (this) {
    QUEUE -> currentUrl?.trim()?.takeIf { YtMusicUrls.isPlayerPage(it) } ?: YtMusicUrls.HOME
    LIBRARY -> YtMusicUrls.LIBRARY
    SEARCH -> YtMusicUrls.SEARCH
  }

  companion object {
    /** The tab that matches the page now showing, or null for pages with no tab (e.g. home). */
    fun forUrl(url: String?): YtMusicTab? {
      if (!YtMusicUrls.isMusicPage(url)) return null
      val path = YtMusicUrls.httpsUri(url)?.rawPath.orEmpty()
      return when {
        path == "/watch" -> QUEUE
        path == "/library" || path.startsWith("/library/") -> LIBRARY
        path == "/search" || path.startsWith("/search/") -> SEARCH
        else -> null
      }
    }
  }
}
