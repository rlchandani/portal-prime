/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.lang.reflect.InvocationTargetException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Actual default store transport over TCP, generated public metadata only. */
class StoreMetadataTransportTest {
  @Test fun declaredOversizedVersionDoesNotOfferAnUpdate() = versionCase(true)
  @Test fun chunkedOversizedVersionDoesNotOfferAnUpdate() = versionCase(false)

  private fun versionCase(known: Boolean) = withServer(known, oversized = true) { url, requests ->
    assertNull("A valid JSON prefix must not allow an oversized response", StoreCatalog.latestVersionCode(app(url)))
    assertEquals(listOf("GET /version.json"), requests.toList())
  }

  @Test fun ordinaryVersionCanRecoverAfterRejectedOversize() = withServer(true, oversized = true) { url, _ ->
    assertNull(StoreCatalog.latestVersionCode(app(url)))
    assertEquals(42L, StoreCatalog.latestVersionCode(app(url.replace("version.json", "small.json"))))
  }

  @Test fun ordinaryChunkedUtf8VersionRetainsItsMeaning() = withServer(false, oversized = false) { url, requests ->
    assertEquals(42L, StoreCatalog.latestVersionCode(app(url)))
    assertEquals(listOf("GET /version.json"), requests.toList())
  }

  @Test(expected = AssertionError::class)
  fun handlerAssertionsCannotBeMistakenForSuccessfulRejection() {
    withServer(true, oversized = true, expectedUserAgent = "deliberately incorrect fixture header") { url, _ ->
      StoreCatalog.latestVersionCode(app(url))
    }
  }

  @Test fun declaredOversizedCatalogCannotReachTheParser() = catalogCase(true)
  @Test fun chunkedOversizedCatalogCannotReachTheParser() = catalogCase(false)

  private fun catalogCase(known: Boolean) = withServer(known, oversized = true,
      prefixText = """{"categories":[{"name":"Generated","apps":[{"name":"Café 日本 🌙","packageName":"com.example.generated"}]}]}""") { url, _ ->
    try {
      readMetadata(url)
      fail("Oversized catalog metadata must fail before parsing")
    } catch (_: IOException) {}
    val recovered = StoreCatalog.parse(readMetadata(url.replace("version.json", "small.json")))
    assertEquals("Café 日本 🌙", recovered.single().name)
  }

  @Test fun completeJsonPrefixWithIncompleteDeclaredBodyCannotOfferAnUpdate() {
    ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
      val bodySent = AtomicBoolean()
      val workerFailure = AtomicReference<Throwable?>()
      val worker = Thread {
        try {
          server.accept().use { socket ->
            socket.soTimeout = 2_000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            var consumed = 0
            while (true) {
              val line = reader.readLine() ?: break
              consumed += line.length
              check(consumed < 8192)
              if (line.isEmpty()) break
            }
            val body = """{"versionCode":42}""".toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size + 100}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII) + body)
            bodySent.set(true)
          }
        } catch (error: Throwable) { workerFailure.set(error) }
      }
      worker.isDaemon = true
      worker.start()
      try {
        assertNull(StoreCatalog.latestVersionCode(app("http://127.0.0.1:${server.localPort}/version.json")))
      } finally {
        server.close()
        worker.join(2_000)
        assertFalse("Owned fixture worker must finish", worker.isAlive)
        assertNull("Generated response must have been sent without a fixture fault", workerFailure.get())
        assertTrue("Rejection must follow a real incomplete response", bodySent.get())
      }
    }
  }

  private fun readMetadata(url: String): String {
    val method = StoreCatalog::class.java.getDeclaredMethod("httpGet", String::class.java)
    method.isAccessible = true
    return try { method.invoke(StoreCatalog, url) as String }
    catch (error: InvocationTargetException) { throw error.targetException }
  }

  private fun app(url: String) = CatalogApp("Generated", "com.example.generated", "url", null,
      "https://example.invalid/generated.apk", null, url, "", "Generated")

  private fun withServer(known: Boolean, oversized: Boolean,
      prefixText: String = """{"versionCode":42,"title":"Café 日本 🌙"}""",
      expectedUserAgent: String = "PortalStore/1.0",
      check: (String, CopyOnWriteArrayList<String>) -> Unit) {
    val requests = CopyOnWriteArrayList<String>()
    val handlerFailures = CopyOnWriteArrayList<Throwable>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      try {
        requests.add("${exchange.requestMethod} ${exchange.requestURI.path}")
        assertEquals(expectedUserAgent, exchange.requestHeaders.getFirst("User-Agent"))
        val prefix = prefixText.toByteArray(Charsets.UTF_8)
        val pad = if (oversized && exchange.requestURI.path != "/small.json") LIMIT + 1 - prefix.size else 0
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, if (known) (prefix.size + pad).toLong() else 0L)
        try {
          exchange.responseBody.use { out ->
            out.write(prefix)
            val spaces = ByteArray(8192) { 32 }
            var remaining = pad
            while (remaining > 0) {
              val count = minOf(remaining, spaces.size)
              out.write(spaces, 0, count)
              remaining -= count
            }
          }
        } catch (_: java.io.IOException) { /* Client rejects the oversized reply early. */ }
      } catch (error: Throwable) {
        handlerFailures.add(error)
      } finally { exchange.close() }
    }
    server.start()
    try { check("http://127.0.0.1:${server.address.port}/version.json", requests) }
    finally { server.stop(0) }
    handlerFailures.firstOrNull()?.let { throw it }
  }

  private companion object { const val LIMIT = 4 * 1024 * 1024 }
}
