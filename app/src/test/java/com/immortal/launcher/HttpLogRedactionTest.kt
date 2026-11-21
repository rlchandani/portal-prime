/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fleet logcat showed ImmortalHttp warnings carrying whole request URLs: a household's latitude
 * and longitude in weather queries, and the same code path serves private calendar feeds and
 * shared-album links whose path or query *is* the secret. Logs keep only scheme, host and port.
 */
class HttpLogRedactionTest {
  @Test fun queryAndPathAreDropped() {
    assertEquals("https://api.open-meteo.com/…",
        Http.logSafeUrl("https://api.open-meteo.com/v1/forecast?latitude=10.5&longitude=-20.25&daily=sunrise"))
    assertEquals("https://calendar.example.invalid/…",
        Http.logSafeUrl("https://calendar.example.invalid/calendar/ical/someone/private-0123abcd/basic.ics"))
  }

  @Test fun credentialsAndFragmentsNeverSurviveButPortsDo() {
    assertEquals("http://nas.example.invalid:8080/…",
        Http.logSafeUrl("http://user:secret@nas.example.invalid:8080/share#token=abc"))
  }

  @Test fun bareHostStaysBareAndJunkIsNotEchoed() {
    assertEquals("https://example.invalid", Http.logSafeUrl("https://example.invalid"))
    assertEquals("https://example.invalid", Http.logSafeUrl("https://example.invalid/"))
    assertEquals("<url>", Http.logSafeUrl("not a url with private-0123abcd"))
  }

  @Test fun exceptionTextLosesAnEchoedUrl() {
    val url = "https://photos.example.invalid/album/SECRET123?key=abc"
    assertEquals("https://photos.example.invalid/…",
        Http.logSafeMessage("$url", url))
    assertEquals("Unable to resolve host \"photos.example.invalid\"",
        Http.logSafeMessage("Unable to resolve host \"photos.example.invalid\"", url))
    assertEquals("null", Http.logSafeMessage(null, url))
  }
}
