/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.net.URLStreamHandler
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** Actual fetch, HTTP reader and raw cache with generated responses; no provider requests. */
class CryptoCachePublicationTest {
  @Test fun generatedResponsesPreserveCachePublicationContract() {
    val paths = mutableSetOf<String>()
    paths.addAll(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
    var loader: ClassLoader? = javaClass.classLoader
    while (loader != null) {
      if (loader is URLClassLoader) paths.addAll(loader.urLs.map { java.io.File(it.toURI()).path })
      loader = loader.parent
    }
    val log = java.io.File.createTempFile("crypto-cache-fixture-", ".log")
    val process = ProcessBuilder(
        java.io.File(System.getProperty("java.home"), "bin/java").path,
        "-cp", paths.joinToString(java.io.File.pathSeparator),
        "com.immortal.launcher.CryptoCachePublicationProcess")
        .redirectErrorStream(true).redirectOutput(log).start()
    try {
      assertTrue("Generated child exceeded its deadline", process.waitFor(30, TimeUnit.SECONDS))
      val output = log.readText()
      System.out.println(output)
      assertEquals(output, 0, process.exitValue())
      assertTrue(output, output.contains("CRYPTO_CASES tests=10 failures=0"))
    } finally {
      if (process.isAlive) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
      }
      log.delete()
    }
  }

  /** Isolated JVM owns its URL factory; no global transport change reaches other suites. */
  class GeneratedCases {
    private val coins = listOf(CryptoConfig.Coin("bitcoin", "XBT"))
    private val key = "crypto_markets:bitcoin"
    private val oldBody = """[{"id":"bitcoin","symbol":"btc","current_price":123.0}]"""
    private val oldQuote = CryptoFeed.Quote("bitcoin", "XBT", 123.0, null)

    private fun prefs(body: String? = oldBody, stamp: Long = 1L): ScreensaverTestPrefs {
      val prefs =
          ScreensaverTestPrefs(
              if (body == null) emptyMap() else mapOf("v:$key" to body, "t:$key" to stamp))
      Mockito.`when`(prefs.context.getSharedPreferences("immortal_http_cache", Context.MODE_PRIVATE))
          .thenReturn(prefs)
      return prefs
    }

    private fun response(
        body: String?,
        block: (HttpURLConnection, MutableList<String>) -> Unit,
    ) {
      val connection = Mockito.mock(HttpURLConnection::class.java)
      if (body == null) {
        Mockito.`when`(connection.responseCode).thenThrow(IOException("Generated offline response"))
      } else {
        Mockito.`when`(connection.responseCode).thenReturn(200)
        Mockito.`when`(connection.inputStream)
            .thenAnswer { ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) }
      }
      val requests = mutableListOf<String>()
      Mockito.mockStatic(Log::class.java).use {
        CryptoCachePublicationProcess.connection = connection
        CryptoCachePublicationProcess.requests = requests
        try {
          block(connection, requests)
        } finally {
          CryptoCachePublicationProcess.connection = null
        }
      }
    }

    private fun preservesOld(body: String) {
      val prefs = prefs()
      response(body) { connection, requests ->
        assertEquals(listOf(oldQuote), CryptoFeed.fetch(prefs.context, coins))
        assertEquals(oldBody, prefs.getString("v:$key", null))
        assertEquals(1L, prefs.getLong("t:$key", 0L))
        assertEquals(0, prefs.writeCount)
        assertEquals(
            listOf(
                "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&ids=bitcoin" +
                    "&sparkline=true&price_change_percentage=24h"),
            requests)
        Mockito.verify(connection).disconnect()
      }
      response(null) { _, requests ->
        assertEquals(listOf(oldQuote), CryptoFeed.fetch(prefs.context, coins))
        assertEquals(1, requests.size)
        assertEquals(oldBody, prefs.getString("v:$key", null))
        assertEquals(1L, prefs.getLong("t:$key", 0L))
        assertEquals(0, prefs.writeCount)
      }
    }

    fun html200PreservesPriorPricesAndOfflineFallback() {
      preservesOld("<html><body>Generated upstream error</body></html>")
    }

    fun truncated200PreservesPriorPricesAndOfflineFallback() {
      preservesOld("""[{"id":"bitcoin","current_price":456""")
    }

    fun object200PreservesPriorPricesAndOfflineFallback() {
      preservesOld("""{"error":"Generated rate limit"}""")
    }

    fun emptyBody200PreservesPriorPricesAndOfflineFallback() {
      preservesOld("")
    }

    fun firstInvalidResponseDoesNotPublishValueOrTimestamp() {
      val prefs = prefs(body = null)
      response("""{"error":"Generated error"}""") { _, requests ->
        assertTrue(CryptoFeed.fetch(prefs.context, coins).isEmpty())
        assertEquals(1, requests.size)
        assertNull(prefs.getString("v:$key", null))
        assertEquals(0L, prefs.getLong("t:$key", 0L))
        assertEquals(0, prefs.writeCount)
      }
    }

    fun validReplacementPublishesAndRetainsConfiguredLabels() {
      val prefs = prefs()
      val fresh = """[{"id":"bitcoin","symbol":"btc","current_price":456.0}]"""
      response(fresh) { _, requests ->
        assertEquals(
            listOf(CryptoFeed.Quote("bitcoin", "XBT", 456.0, null)),
            CryptoFeed.fetch(prefs.context, coins))
        assertEquals(1, requests.size)
        assertEquals(fresh, prefs.getString("v:$key", null))
        assertTrue(prefs.getLong("t:$key", 0L) > 1L)
        assertEquals(1, prefs.writeCount)
      }
    }

    private fun publishesEmpty(body: String) {
      val prefs = prefs()
      response(body) { _, requests ->
        assertTrue(CryptoFeed.fetch(prefs.context, coins).isEmpty())
        assertEquals(1, requests.size)
        assertEquals(body, prefs.getString("v:$key", null))
        assertTrue(prefs.getLong("t:$key", 0L) > 1L)
        assertEquals(1, prefs.writeCount)
      }
      response(null) { _, requests ->
        assertTrue(CryptoFeed.fetch(prefs.context, coins).isEmpty())
        assertTrue(requests.isEmpty())
        assertEquals(body, prefs.getString("v:$key", null))
        assertEquals(1, prefs.writeCount)
      }
    }

    fun validEmptyArrayReplacesOldPrices() {
      publishesEmpty("[]")
    }

    fun validArrayOmittingConfiguredCoinReplacesOldPrices() {
      publishesEmpty("""[{"id":"ethereum","symbol":"eth","current_price":789.0}]""")
    }

    fun freshCacheSkipsTransportAndPublication() {
      val stamp = System.currentTimeMillis()
      val prefs = prefs(stamp = stamp)
      response(null) { connection, requests ->
        assertEquals(listOf(oldQuote), CryptoFeed.fetch(prefs.context, coins))
        assertTrue(requests.isEmpty())
        assertEquals(stamp, prefs.getLong("t:$key", 0L))
        assertEquals(0, prefs.writeCount)
        Mockito.verifyNoInteractions(connection)
      }
    }

    fun ordinaryOfflineFetchPreservesExpiredCache() {
      val prefs = prefs()
      response(null) { connection, requests ->
        assertEquals(listOf(oldQuote), CryptoFeed.fetch(prefs.context, coins))
        assertEquals(1, requests.size)
        assertEquals(oldBody, prefs.getString("v:$key", null))
        assertEquals(1L, prefs.getLong("t:$key", 0L))
        assertEquals(0, prefs.writeCount)
        Mockito.verify(connection).disconnect()
      }
    }
  }

}

/** Separate process keeps the one-shot URL factory out of the Gradle worker and other tests. */
object CryptoCachePublicationProcess {
  var connection: HttpURLConnection? = null
  var requests: MutableList<String> = mutableListOf()

  @JvmStatic fun main(args: Array<String>) {
    URL.setURLStreamHandlerFactory { protocol ->
      if (protocol != "https") null else object : URLStreamHandler() {
        override fun openConnection(url: URL): java.net.URLConnection {
          check(url.toString().startsWith(
              "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&ids=bitcoin&"))
          requests.add(url.toString())
          return checkNotNull(connection) { "No generated transport admitted" }
        }
      }
    }
    val cases = CryptoCachePublicationTest.GeneratedCases()
    val names = listOf(
        "html200PreservesPriorPricesAndOfflineFallback",
        "truncated200PreservesPriorPricesAndOfflineFallback",
        "object200PreservesPriorPricesAndOfflineFallback",
        "emptyBody200PreservesPriorPricesAndOfflineFallback",
        "firstInvalidResponseDoesNotPublishValueOrTimestamp",
        "validReplacementPublishesAndRetainsConfiguredLabels",
        "validEmptyArrayReplacesOldPrices",
        "validArrayOmittingConfiguredCoinReplacesOldPrices",
        "freshCacheSkipsTransportAndPublication",
        "ordinaryOfflineFetchPreservesExpiredCache")
    var failures = 0
    names.forEach { name ->
      try {
        cases.javaClass.getMethod(name).invoke(cases)
        System.out.println("CRYPTO_CASE $name PASS")
      } catch (error: java.lang.reflect.InvocationTargetException) {
        failures++
        System.out.println("CRYPTO_CASE $name FAIL")
        error.cause!!.printStackTrace(System.out)
      }
    }
    System.out.println("CRYPTO_CASES tests=${names.size} failures=$failures")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
  }
}
