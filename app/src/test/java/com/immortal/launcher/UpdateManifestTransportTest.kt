/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Real self-update transport; generated loopback manifests never reach installation. */
class UpdateManifestTransportTest {
  @Test fun declaredOversizeCannotReachTheManifestParser() = oversizedCase("/declared")
  @Test fun chunkedOversizeCannotReachTheManifestParser() = oversizedCase("/chunked")

  private fun oversizedCase(path: String) = withServer { base, requests ->
    expectIOException { readManifest("$base$path") }
    assertEquals(listOf("GET $path"), requests.toList())
  }

  @Test fun ordinaryDeclaredUtf8ManifestRetainsAllFields() = ordinaryCase("/small")
  @Test fun ordinaryChunkedUtf8ManifestRetainsAllFields() = ordinaryCase("/small-chunked")

  private fun ordinaryCase(path: String) = withServer { base, requests ->
    val result = readManifest("$base$path")
    assertEquals(42L, result.versionCode)
    assertEquals("Generated", result.versionName)
    assertEquals("https://example.invalid/immortal.apk", result.apkUrl)
    assertEquals("Café 日本 🌙", result.notes)
    assertEquals(listOf("GET $path"), requests.toList())
  }

  @Test fun rejectedOversizeDoesNotPreventTheNextManifestCheck() = withServer { base, requests ->
    expectIOException { readManifest("$base/chunked") }
    assertEquals(42L, readManifest("$base/small").versionCode)
    assertEquals(listOf("GET /chunked", "GET /small"), requests.toList())
  }

  @Test fun normalRedirectPolicyRemainsSupported() = withServer { base, requests ->
    assertEquals(42L, readManifest("$base/redirect").versionCode)
    assertEquals(listOf("GET /redirect", "GET /small"), requests.toList())
  }

  @Test fun httpFailureDoesNotPreventTheNextManifestCheck() = withServer { base, requests ->
    expectIOException { readManifest("$base/missing") }
    assertEquals(42L, readManifest("$base/small").versionCode)
    assertEquals(listOf("GET /missing", "GET /small"), requests.toList())
  }

  @Test(expected = AssertionError::class)
  fun serverAssertionsCannotMasqueradeAsResponseRejection() {
    withServer(expectedAgent = "deliberately incorrect fixture header") { base, _ ->
      expectIOException { readManifest("$base/declared") }
    }
  }

  @Test fun completeJsonPrefixWithIncompleteDeclaredBodyCannotReachTheParser() {
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
      val sent = AtomicBoolean()
      val fault = AtomicReference<Throwable?>()
      val worker = Thread {
        try {
          server.accept().use { socket ->
            socket.soTimeout = 2_000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            var size = 0
            while (true) {
              val line = reader.readLine() ?: break
              size += line.length
              check(size < 8192)
              if (line.isEmpty()) break
            }
            val body = MANIFEST.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size + 100}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII) + body)
            sent.set(true)
          }
        } catch (error: Throwable) { fault.set(error) }
      }.apply { isDaemon = true; start() }
      try {
        expectIOException { readManifest("http://127.0.0.1:${server.localPort}/manifest") }
      } finally {
        server.close()
        worker.join(2_000)
        assertFalse("Owned worker must terminate", worker.isAlive)
        assertNull("Fixture faults cannot establish transport rejection", fault.get())
        assertTrue("The incomplete body must have been sent", sent.get())
      }
    }
  }

  private fun readManifest(url: String): UpdateInfo {
    val method = UpdateManager::class.java.getDeclaredMethod("httpGet", String::class.java)
    method.isAccessible = true
    val text = try { method.invoke(UpdateManager, url) as String }
        catch (error: InvocationTargetException) { throw error.targetException }
    return UpdateManager.parseManifest(text)
  }

  private fun expectIOException(block: () -> Unit) {
    try { block(); fail("An unbounded or incomplete manifest must not reach parsing") }
    catch (_: IOException) {}
  }

  private fun withServer(expectedAgent: String = "Immortal/1.0",
      check: (String, CopyOnWriteArrayList<String>) -> Unit) {
    val requests = CopyOnWriteArrayList<String>()
    val faults = CopyOnWriteArrayList<Throwable>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      try {
        val path = exchange.requestURI.path
        requests.add("${exchange.requestMethod} $path")
        assertEquals(expectedAgent, exchange.requestHeaders.getFirst("User-Agent"))
        if (path == "/redirect") {
          exchange.responseHeaders.set("Location", "/small")
          exchange.sendResponseHeaders(302, -1)
        } else if (path == "/missing") {
          exchange.sendResponseHeaders(404, -1)
        } else {
          val prefix = MANIFEST.toByteArray(Charsets.UTF_8)
          val padding = if (path in setOf("/declared", "/chunked")) LIMIT + 1 - prefix.size else 0
          exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
          exchange.sendResponseHeaders(200,
              if (path in setOf("/chunked", "/small-chunked")) 0 else (prefix.size + padding).toLong())
          try {
            exchange.responseBody.use { out ->
              out.write(prefix)
              val spaces = ByteArray(8192) { 32 }
              var remaining = padding
              while (remaining > 0) {
                val count = minOf(remaining, spaces.size)
                out.write(spaces, 0, count)
                remaining -= count
              }
            }
          } catch (_: IOException) { /* Expected when the client rejects the oversized body early. */ }
        }
      } catch (error: Throwable) { faults.add(error) }
      finally { exchange.close() }
    }
    server.start()
    try { check("http://127.0.0.1:${server.address.port}", requests) }
    finally {
      server.stop(0)
      faults.firstOrNull()?.let { throw it }
    }
  }

  private companion object {
    const val LIMIT = 4 * 1024 * 1024
    const val MANIFEST = """{"versionCode":42,"versionName":"Generated","apkUrl":"https://example.invalid/immortal.apk","notes":"Café 日本 🌙"}"""
  }
}
