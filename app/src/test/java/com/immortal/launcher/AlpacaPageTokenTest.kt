/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.net.URLDecoder
import java.net.URLStreamHandler
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Actual options fetch and HTTP reader; generated HTTPS is confined to a child JVM. */
class AlpacaPageTokenTest {
  @Test fun generatedContinuationRequestsPreserveOpaqueTokens() {
    val paths = mutableSetOf<String>()
    paths.addAll(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
    var loader: ClassLoader? = javaClass.classLoader
    while (loader != null) {
      if (loader is URLClassLoader) paths.addAll(loader.urLs.map { java.io.File(it.toURI()).path })
      loader = loader.parent
    }
    val log = java.io.File.createTempFile("alpaca-token-fixture-", ".log")
    val process = ProcessBuilder(
        java.io.File(System.getProperty("java.home"), "bin/java").path,
        "-cp", paths.joinToString(java.io.File.pathSeparator),
        "com.immortal.launcher.AlpacaPageTokenProcess")
        .redirectErrorStream(true).redirectOutput(log).start()
    try {
      assertTrue("Generated child exceeded its deadline", process.waitFor(30, TimeUnit.SECONDS))
      val output = log.readText()
      System.out.println(output)
      assertEquals(output, 0, process.exitValue())
      assertTrue(output, output.contains("ALPACA_TOKEN_CASES tests=6 failures=0"))
    } finally {
      if (process.isAlive) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
      }
      log.delete()
    }
  }

  class GeneratedCases {
    fun plusTokenRoundTrips() = fetch("A+B/C==")
    fun ampersandTokenCannotChangeFeed() = fetch("A&feed=opra")
    fun literalPercentTokenIsNotDecodedTwice() = fetch("A%2FB")
    fun fragmentTokenRoundTrips() = fetch("A#B")
    fun ordinaryTokenKeepsExistingRequest() = fetch("abc123")
    fun onePageLimitDoesNotFollowContinuation() = fetch("A+B", maxPages = 1)

    private fun fetch(token: String, maxPages: Int = 5) {
      val prefs = ScreensaverTestPrefs(mapOf("key_id" to "generated-id", "key_secret" to "generated-secret"))
      Mockito.`when`(prefs.context.getSharedPreferences("immortal_alpaca", Context.MODE_PRIVATE))
          .thenReturn(prefs)
      AlpacaPageTokenProcess.token = token
      AlpacaPageTokenProcess.requests.clear()
      AlpacaPageTokenProcess.disconnections = 0
      val contracts = AlpacaClient.fetchOptionChain(
          prefs.context, " spy ", "call", "2026-11-06", "2026-12-06", maxPages)
      val expected = listOf(
          AlpacaClient.OptionContract("SPY-FIRST", .25, .3, 1.0, 2.0, 1.5),
          AlpacaClient.OptionContract("SPY-SECOND", .25, .3, 1.0, 2.0, 1.5))
      assertEquals(expected.take(if (maxPages == 1) 1 else 2), contracts)
      val requests = AlpacaPageTokenProcess.requests
      assertEquals(if (maxPages == 1) 1 else 2, requests.size)
      assertEquals(requests.size, AlpacaPageTokenProcess.disconnections)
      assertEquals(0, prefs.writeCount)
      assertEquals(
          "https://data.alpaca.markets/v1beta1/options/snapshots/SPY" +
              "?feed=indicative&type=call&expiration_date_gte=2026-11-06" +
              "&expiration_date_lte=2026-12-06&limit=1000",
          requests.first().toString())
      if (maxPages == 1) return
      val url = requests.last()
      assertNull("Token must not become a URL fragment", url.ref)
      val params = url.query.split('&').map {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
      }
      assertEquals(listOf(
          "feed" to "indicative", "type" to "call",
          "expiration_date_gte" to "2026-11-06", "expiration_date_lte" to "2026-12-06",
          "limit" to "1000", "page_token" to token), params)
    }
  }
}

/** No generated request escapes to a provider, and the global factory dies with this process. */
object AlpacaPageTokenProcess {
  var token = ""
  val requests = mutableListOf<URL>()
  var disconnections = 0

  @JvmStatic fun main(args: Array<String>) {
    URL.setURLStreamHandlerFactory { protocol ->
      if (protocol != "https") null else object : URLStreamHandler() {
        override fun openConnection(url: URL): java.net.URLConnection {
          check(url.host == "data.alpaca.markets" && url.path == "/v1beta1/options/snapshots/SPY")
          requests.add(url)
          val first = requests.size == 1
          val body = JSONObject().put("snapshots", JSONObject().put(
              if (first) "SPY-FIRST" else "SPY-SECOND",
              JSONObject("""{"greeks":{"delta":0.25},"impliedVolatility":0.3,"latestQuote":{"bp":1,"ap":2},"latestTrade":{"p":1.5}}""")))
              .apply { if (first) put("next_page_token", token) }.toString()
          return object : HttpURLConnection(url) {
            override fun getResponseCode(): Int {
              assertEquals("GET", requestMethod)
              assertEquals("generated-id", getRequestProperty("APCA-API-KEY-ID"))
              assertEquals("generated-secret", getRequestProperty("APCA-API-SECRET-KEY"))
              return 200
            }
            override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
            override fun disconnect() { disconnections++ }
            override fun connect() {}
            override fun usingProxy() = false
          }
        }
      }
    }
    val cases = AlpacaPageTokenTest.GeneratedCases()
    val names = listOf(
        "plusTokenRoundTrips", "ampersandTokenCannotChangeFeed",
        "literalPercentTokenIsNotDecodedTwice", "fragmentTokenRoundTrips",
        "ordinaryTokenKeepsExistingRequest", "onePageLimitDoesNotFollowContinuation")
    var failures = 0
    names.forEach { name ->
      try {
        cases.javaClass.getMethod(name).invoke(cases)
        println("ALPACA_TOKEN_CASE $name PASS")
      } catch (error: java.lang.reflect.InvocationTargetException) {
        failures++
        println("ALPACA_TOKEN_CASE $name FAIL")
        error.cause!!.printStackTrace(System.out)
      }
    }
    println("ALPACA_TOKEN_CASES tests=${names.size} failures=$failures")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
  }
}
