/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale

/**
 * Decides when Google has refused sign-in inside the embedded WebView. Detection only: the pane
 * then shows its fallback ("Sign in on the Portal browser"). Nothing here tries to avoid the
 * restriction — the WebView keeps its stock user agent.
 */
object YtMusicSignInDetector {
  private const val ACCOUNTS_HOST = "accounts.google.com"
  private const val DISALLOWED_UA = "error=disallowed_useragent"
  private val textMarkers = listOf(
      "this browser or app may not be secure",
      "couldn't sign you in",
  )

  /** BLOCKED from the navigation alone: Google's rejection pages and OAuth's webview error. */
  fun isBlockedUrl(url: String?): Boolean {
    val uri = YtMusicUrls.httpsUri(url) ?: return false
    if (YtMusicUrls.host(uri) != ACCOUNTS_HOST) return false
    val path = uri.rawPath.orEmpty().lowercase(Locale.ROOT)
    val rejected = path.endsWith("/rejected") || path.contains("/rejected/")
    val query = uri.rawQuery.orEmpty().lowercase(Locale.ROOT)
    return rejected || query.split('&').any { it == DISALLOWED_UA }
  }

  /** BLOCKED from the navigation plus the visible page text (only trusted on Google's host). */
  fun isBlockedPage(url: String?, pageText: String?): Boolean {
    if (isBlockedUrl(url)) return true
    val uri = YtMusicUrls.httpsUri(url) ?: return false
    if (YtMusicUrls.host(uri) != ACCOUNTS_HOST || pageText.isNullOrBlank()) return false
    val text = pageText.replace('’', '\'').lowercase(Locale.ROOT)
    return textMarkers.any { text.contains(it) }
  }
}
