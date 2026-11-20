/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * The `/backup/…` routes behind the real [FleetRouter]: the bearer gate runs before the method
 * allow-list and the rate limits, so an unauthenticated flood can't use up the operator's budget.
 */
class FleetBackupGateTest {
  private val dir: File = Files.createTempDirectory("fleet-backup-gate").toFile()
  private val prefs = HashMap<String, ScreensaverTestPrefs>()
  private var now = 10_000_000L
  private val context: Context = mock(Context::class.java).also { c ->
    `when`(c.getSharedPreferences(anyString(), anyInt())).thenAnswer { prefs.getOrPut(it.getArgument(0)) { ScreensaverTestPrefs() } }
    `when`(c.filesDir).thenReturn(File(dir, "files"))
  }
  private val platform = object : FleetBackupRoutes.Platform {
    override fun androidId(c: Context) = "a1b2c3d4e5f60718"
    override fun log(message: String) = Unit
    override fun read(file: File) = file.takeIf { it.isFile }?.readBytes()
    override fun write(file: File, bytes: ByteArray) = file.writeBytes(bytes)
    override fun household(c: Context): Map<String, Any> = emptyMap()
    override fun householdWellFormed(key: String, value: Any?): Any? = null
    override fun applyHousehold(c: Context, values: Map<String, Any>): Set<String> = emptySet()
  }
  private val backup = FleetBackupRoutes(context, clock = { now }, platform = platform)
  private val router = FleetRouter({ TOKEN }, { FleetHttpServer.Response(404, "{}") }, FleetBackupRoutes.PATHS.associateWith { backup::handle })

  @After fun cleanUp() { dir.deleteRecursively() }

  private fun call(method: String, path: String, body: String = "", auth: String? = "Bearer $TOKEN") =
      router.handle(FleetReq.of(method, path, body, auth = auth))

  @Test fun unauthenticatedCallsDoNotSpendTheRateLimit() {
    repeat(100) { assertEquals(401, call("GET", "/backup/key", auth = "Bearer wrong").status) }
    assertEquals(200, call("GET", "/backup/key").status)
  }

  @Test fun theMethodAllowListRunsBeforeTheLimiter() {
    for ((method, path) in listOf("GET" to "/backup/export", "GET" to "/backup/import", "PUT" to "/backup/key", "DELETE" to "/backup/key")) {
      repeat(5) { assertEquals("$method $path", "405 method_not_allowed", FleetReq.outcome(call(method, path))) }
    }
    assertEquals(200, call("GET", "/backup/key").status)
  }

  @Test fun aRouteMayRunOnceEveryTwoSeconds() {
    assertEquals(200, call("GET", "/backup/key").status)
    now += 500
    val limited = call("GET", "/backup/key")
    assertEquals(429, limited.status)
    assertEquals("2", limited.extraHeaders["Retry-After"])
    val o = FleetReq.json(limited)
    assertEquals("backup_rate_limited", o.getString("error"))
    assertEquals(2L, o.getLong("retryAfterSec"))
    // A different route (or method) has its own budget.
    assertEquals("409 backup_key_not_enrolled", FleetReq.outcome(call("POST", "/backup/export")))
    assertEquals("400 bad_public_key", FleetReq.outcome(call("POST", "/backup/key", "{}")))
    now += 1_500
    assertEquals(200, call("GET", "/backup/key").status)
  }

  @Test fun aRouteMayRun30TimesAnHour() {
    repeat(30) {
      assertEquals("call $it", 200, call("GET", "/backup/key").status)
      now += 2_000
    }
    val limited = call("GET", "/backup/key")
    assertEquals(429, limited.status)
    // The oldest call leaves the window 60 minutes after it ran.
    assertEquals(((60 * 60_000L - 60_000L) / 1000).toString(), limited.extraHeaders["Retry-After"])
    now += 60 * 60_000L - 60_000L
    assertEquals(200, call("GET", "/backup/key").status)
  }

  @Test fun importOpsAreAllowListedAndBeginsLimitedOnTheirOwn() {
    assertEquals("400 bad_json", FleetReq.outcome(call("POST", "/backup/import", "nope")))
    for (op in listOf("", "export", "BEGIN")) {
      assertEquals(op, "400 bad_op", FleetReq.outcome(call("POST", "/backup/import", JSONObject().put("op", op).toString())))
    }
    assertEquals(200, call("POST", "/backup/import", "{\"op\":\"begin\"}").status)
    assertEquals(429, call("POST", "/backup/import", "{\"op\":\"begin\"}").status)
    // An apply right after its begin isn't limited by it (it fails here for its own reasons).
    assertEquals("409 import_session_unknown", FleetReq.outcome(call("POST", "/backup/import", "{\"op\":\"apply\",\"session\":\"x\"}")))
  }

  @Test fun theBodyCapsAreEnforced() {
    val big = "{\"publicKey\":\"" + "A".repeat(16 * 1024) + "\"}"
    assertEquals("413 backup_too_large", FleetReq.outcome(call("POST", "/backup/key", big)))
    assertEquals(12 * 1024 * 1024, FleetBackupRoutes.MAX_IMPORT_BODY)
    val tooBig = FleetReq.of("POST", "/backup/import", "{}", auth = "Bearer $TOKEN", contentLength = FleetBackupRoutes.MAX_IMPORT_BODY + 1)
    assertEquals("413 backup_too_large", FleetReq.outcome(router.handle(tooBig)))
  }

  private companion object {
    const val TOKEN = "fleet-token-0123456789abcdef"
  }
}
