/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.webkit.WebViewClient

/** Chromium can map empty responses to UNKNOWN; only main-page failures need recovery. */
internal object WebAppLoadErrorPolicy {
  fun shouldRecover(isMainFrame: Boolean, code: Int): Boolean = isMainFrame && when (code) {
    WebViewClient.ERROR_UNKNOWN, WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT,
    WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO -> true
    else -> false
  }
}
