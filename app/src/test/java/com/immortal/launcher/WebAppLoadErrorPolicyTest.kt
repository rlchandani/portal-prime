/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.webkit.WebViewClient
import org.junit.Assert.*
import org.junit.Test

class WebAppLoadErrorPolicyTest {
  @Test fun unknownMainFrameFailureOffersRecoveryForProviderEmptyResponse() {
    assertTrue(WebAppLoadErrorPolicy.shouldRecover(true, WebViewClient.ERROR_UNKNOWN))
  }
  @Test fun connectionFailuresOfferMainFrameRecovery() {
    for (code in listOf(WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT,
        WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO)) {
      assertTrue(WebAppLoadErrorPolicy.shouldRecover(true, code))
    }
  }
  @Test fun failedImagesAndFramesNeverReplaceUsablePage() {
    for (code in listOf(WebViewClient.ERROR_UNKNOWN, WebViewClient.ERROR_HOST_LOOKUP,
        WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO)) {
      assertFalse(WebAppLoadErrorPolicy.shouldRecover(false, code))
    }
  }
  @Test fun certificateAuthenticationAndUnsupportedRequestsKeepPlatformHandling() {
    for (code in listOf(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
        WebViewClient.ERROR_AUTHENTICATION, WebViewClient.ERROR_UNSUPPORTED_SCHEME,
        WebViewClient.ERROR_BAD_URL, WebViewClient.ERROR_FILE_NOT_FOUND)) {
      assertFalse(WebAppLoadErrorPolicy.shouldRecover(true, code))
    }
  }
}
