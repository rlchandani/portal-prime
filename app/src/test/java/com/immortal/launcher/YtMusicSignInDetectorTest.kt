/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicSignInDetectorTest {
  @Test fun rejectedSignInUrls_areBlocked() {
    assertTrue(YtMusicSignInDetector.isBlockedUrl(
        "https://accounts.google.com/signin/rejected?rrk=46&hl=en"))
    assertTrue(YtMusicSignInDetector.isBlockedUrl(
        "https://accounts.google.com/v3/signin/rejected?continue=https%3A%2F%2Fmusic.youtube.com%2F&dsh=S1:17&flowName=GlifWebSignIn"))
    assertTrue(YtMusicSignInDetector.isBlockedUrl(
        "https://accounts.google.com/ServiceLogin/signinchooser/rejected?service=youtube"))
    assertTrue(YtMusicSignInDetector.isBlockedUrl(
        "https://accounts.google.com/o/oauth2/auth?error=disallowed_useragent&client_id=1"))
    assertTrue(YtMusicSignInDetector.isBlockedUrl(
        "HTTPS://Accounts.Google.com/SignIn/Rejected"))
  }

  @Test fun normalSignInAndMusicPages_areNotBlocked() {
    assertFalse(YtMusicSignInDetector.isBlockedUrl(
        "https://accounts.google.com/v3/signin/identifier?continue=https%3A%2F%2Fwww.youtube.com%2Fsignin&service=youtube"))
    assertFalse(YtMusicSignInDetector.isBlockedUrl(
        "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com/"))
    assertFalse(YtMusicSignInDetector.isBlockedUrl("https://music.youtube.com/watch?v=abc&list=RDAMVM"))
    assertFalse(YtMusicSignInDetector.isBlockedUrl("https://music.youtube.com/library"))
    assertFalse(YtMusicSignInDetector.isBlockedUrl(null))
    assertFalse(YtMusicSignInDetector.isBlockedUrl(""))
    assertFalse(YtMusicSignInDetector.isBlockedUrl("not a url"))
  }

  @Test fun markersOnOtherHosts_areNotBlocked() {
    assertFalse(YtMusicSignInDetector.isBlockedUrl(
        "https://evil.example/signin/rejected?u=accounts.google.com"))
    assertFalse(YtMusicSignInDetector.isBlockedUrl(
        "https://music.youtube.com/search?q=signin%2Frejected"))
    assertFalse(YtMusicSignInDetector.isBlockedUrl(
        "https://accounts.google.com.evil.example/signin/rejected"))
    assertFalse(YtMusicSignInDetector.isBlockedUrl("http://accounts.google.com/signin/rejected"))
  }

  @Test fun rejectionPageText_onAccountsHost_isBlocked() {
    val url = "https://accounts.google.com/v3/signin/challenge/pwd?TL=abc"
    assertTrue(YtMusicSignInDetector.isBlockedPage(url,
        "Couldn't sign you in\nThis browser or app may not be secure. Learn more"))
    assertTrue(YtMusicSignInDetector.isBlockedPage(url,
        "Couldn’t sign you in. Try using a different browser."))
    assertFalse(YtMusicSignInDetector.isBlockedPage(url, "Welcome\nEnter your password"))
    assertFalse(YtMusicSignInDetector.isBlockedPage(url, null))
  }

  @Test fun rejectionText_elsewhere_isIgnored() {
    assertFalse(YtMusicSignInDetector.isBlockedPage("https://music.youtube.com/watch?v=x",
        "This browser or app may not be secure"))
  }

  @Test fun blockedUrl_isBlockedPageRegardlessOfText() {
    assertTrue(YtMusicSignInDetector.isBlockedPage("https://accounts.google.com/signin/rejected", ""))
  }
}
