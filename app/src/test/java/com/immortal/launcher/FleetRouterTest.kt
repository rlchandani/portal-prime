/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fleet API's front door: the bearer gate, `/remote/` first, exact paths and per-route methods. */
class FleetRouterTest {
  private val host = FleetTestHost()
  private val files = FleetTestFiles()
  private val catalog = FleetCatalog()
  private val delegatedHits = ArrayList<String>()
  private var remoteHits = 0

  private val delegatedPaths = FleetPaths.PROBES + OtaRoutes.FLEET_PATH + FleetBackupRoutes.PATHS
  private val router = FleetRouter(
      token = { host.token() },
      remote = { remoteHits++; FleetHttpServer.Response(200, "{\"remote\":true}") },
      routes = FleetRouteTable.build(
          FleetDeviceApi(host, host, host, catalog),
          FleetInstallApi(host, host, files, catalog),
          FleetFileApi(files),
          FleetActionApi(host),
          FleetPerfApi(host, host),
          host,
          delegatedPaths.associateWith { path -> { _: FleetHttpServer.Request -> delegatedHits += path; FleetHttpServer.Response(200, "{\"delegated\":\"$path\"}") } }))

  private fun authed(method: String, path: String, body: String = "", query: String = "") =
      FleetReq.of(method, path, body, query, auth = "Bearer ${host.deviceToken}")

  @Test fun theRouteTableIsExactlyTheDocumentedApi() {
    val own = setOf("/info", "/apps", "/install", "/update", "/dev", "/config", "/calendar", "/screensaver",
        "/action", "/fs/list", "/fs/read", "/fs/write", "/logcat", "/diag", "/perf", "/perf/state",
        "/perf/filmstrip-render", "/perf/recent-apps", "/perf/webapp-startup", "/perf/tools-discovery",
        "/perf/remote-input")
    assertEquals(own + delegatedPaths, router.paths)
    assertEquals(13, FleetPaths.PROBES.size)
    assertEquals(setOf("/backup/key", "/backup/export", "/backup/import"), FleetBackupRoutes.PATHS)
    assertEquals("/update/ota", OtaRoutes.FLEET_PATH)
  }

  @Test fun everyRouteIsBehindTheBearerGate() {
    for (path in router.paths) for (method in listOf("GET", "POST")) {
      for (auth in listOf(null, "", "Bearer ", "Bearer x", "Bearer ${host.deviceToken}x", "bearer ${host.deviceToken}",
          "Basic ${host.deviceToken}", "Bearer ${host.deviceToken.dropLast(1)}")) {
        val resp = router.handle(FleetReq.of(method, path, auth = auth))
        assertEquals("$method $path [$auth]", "401 unauthorized", FleetReq.outcome(resp))
      }
    }
    // Nothing behind the gate ran: no route, no delegate, no remote.
    assertTrue(delegatedHits.isEmpty())
    assertEquals(0, remoteHits)
    assertTrue(host.calls.all { it == "token" })
  }

  @Test fun unauthorizedRequestsDoNoWorkAndReadNoBody() {
    val req = FleetReq.of("POST", "/fs/write", "secret", "path=/tmp/x", auth = "Bearer nope")
    assertEquals("401 unauthorized", FleetReq.outcome(router.handle(req)))
    // The whole body is still unread (the server drains it after replying).
    assertEquals("secret", req.bodyText())
  }

  @Test fun theTokenIsReadPerRequestSoARotationTakesEffectAtOnce() {
    val old = host.deviceToken
    assertEquals(200, router.handle(authed("GET", "/info")).status)
    host.deviceToken = "rotated-token-0000000000000000"
    assertEquals(401, router.handle(FleetReq.of("GET", "/info", auth = "Bearer $old")).status)
    assertEquals(200, router.handle(FleetReq.of("GET", "/info", auth = "Bearer rotated-token-0000000000000000")).status)
  }

  @Test fun aBareTokenOrPaddedBearerIsAccepted() {
    assertEquals(200, router.handle(FleetReq.of("GET", "/info", auth = host.deviceToken)).status)
    assertEquals(200, router.handle(FleetReq.of("GET", "/info", auth = "Bearer   ${host.deviceToken}  ")).status)
  }

  @Test fun theRemoteIsRoutedBeforeTheGate() {
    for (path in listOf("/remote/", "/remote/pair", "/remote/ota", "/remote/x/y")) {
      assertEquals(200, router.handle(FleetReq.of("GET", path)).status)
    }
    assertEquals(4, remoteHits)
    // Only the exact prefix: "/remote" and "/remotex" are ordinary (gated, unknown) paths.
    assertEquals("401 unauthorized", FleetReq.outcome(router.handle(FleetReq.of("GET", "/remote"))))
    assertEquals("404 not_found", FleetReq.outcome(router.handle(authed("GET", "/remotex"))))
    assertEquals(4, remoteHits)
  }

  @Test fun unknownPathsAre404OnlyAfterAuth() {
    for (path in listOf("/", "", "/nope", "/info/", "/INFO", "/fs", "/fs/", "/fs/delete", "/backup", "/perf/", "/info?x=1")) {
      assertEquals(path, "404 not_found", FleetReq.outcome(router.handle(authed("GET", path))))
    }
  }

  @Test fun delegatedRoutesGetTheRequestAsIsAndCheckTheirOwnMethods() {
    for (path in delegatedPaths) for (method in listOf("GET", "POST", "PUT", "DELETE")) {
      assertEquals("$method $path", 200, router.handle(authed(method, path)).status)
    }
    assertEquals(delegatedPaths.size * 4, delegatedHits.size)
  }

  @Test fun eachOwnRouteAnswersOnlyItsMethods() {
    val allowed = mapOf(
        "/info" to setOf("GET"), "/apps" to setOf("GET"), "/install" to setOf("POST"), "/update" to setOf("POST"),
        "/dev" to setOf("GET", "POST"), "/config" to setOf("POST"), "/calendar" to setOf("GET", "POST"),
        "/screensaver" to setOf("GET", "POST"), "/action" to setOf("POST"), "/fs/list" to setOf("GET"),
        "/fs/read" to setOf("GET"), "/fs/write" to setOf("POST"), "/logcat" to setOf("GET"), "/diag" to setOf("GET"),
        "/perf" to setOf("GET", "POST"), "/perf/state" to setOf("GET"), "/perf/filmstrip-render" to setOf("GET"),
        "/perf/recent-apps" to setOf("GET"), "/perf/webapp-startup" to setOf("GET"),
        "/perf/tools-discovery" to setOf("GET"), "/perf/remote-input" to setOf("GET"))
    assertEquals(router.paths - delegatedPaths, allowed.keys)
    for ((path, methods) in allowed) for (method in listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "get")) {
      val resp = router.handle(authed(method, path))
      if (method in methods) assertFalse("$method $path", resp.status == 405)
      else assertEquals("$method $path", "405 method_not_allowed", FleetReq.outcome(resp))
    }
  }

  @Test fun aTableThatWouldShadowTheRemoteOrClaimARouteTwiceIsRefused() {
    val ok: FleetHandler = { FleetHttpServer.Response(200, "{}") }
    assertTrue(runCatching { FleetRouter({ "t" }, ok, mapOf("/remote/x" to ok)) }.isFailure)
    assertTrue(runCatching {
      FleetRouteTable.build(FleetDeviceApi(host, host, host, catalog), FleetInstallApi(host, host, files, catalog),
          FleetFileApi(files), FleetActionApi(host), FleetPerfApi(host, host), host, mapOf("/info" to ok))
    }.isFailure)
  }

  @Test fun theRouterCopiesItsTable() {
    val ok: FleetHandler = { FleetHttpServer.Response(200, "{}") }
    val table = HashMap<String, FleetHandler>(mapOf("/a" to ok))
    val r = FleetRouter({ "t" }, ok, table)
    table["/b"] = ok
    assertEquals(setOf("/a"), r.paths)
    assertEquals(200, r.handle(FleetReq.of("GET", "/a", auth = "t")).status)
  }

  @Test fun constantTimeCompareChecksEveryCharacter() {
    val token = "0123456789abcdef0123456789abcdef"
    assertTrue(FleetAuth.constantTimeEquals(token, token))
    // A difference at any position, first or last, is caught.
    for (i in token.indices) {
      val wrong = token.substring(0, i) + (if (token[i] == '0') '1' else '0') + token.substring(i + 1)
      assertFalse("position $i", FleetAuth.constantTimeEquals(wrong, token))
    }
    assertFalse(FleetAuth.constantTimeEquals(token + "0", token))
    assertFalse(FleetAuth.constantTimeEquals("", token))
    // Characters beyond ASCII compare by code unit.
    assertTrue(FleetAuth.constantTimeEquals("é✓", "é✓"))
    assertFalse(FleetAuth.constantTimeEquals("é✓", "e✓"))
  }

  @Test fun authorizedNeedsAHeaderAndTheExactToken() {
    assertFalse(FleetAuth.authorized(null, "t"))
    assertFalse(FleetAuth.authorized("Bearer", "t"))
    assertTrue(FleetAuth.authorized("Bearer t", "t"))
    assertTrue(FleetAuth.authorized("t", "t"))
    assertFalse(FleetAuth.authorized(" Bearer t ", "t")) // the scheme must lead
    assertFalse(FleetAuth.authorized("Bearer Bearer t", "t"))
    // An empty device token never matches a real header... except an empty one, which the
    // server can't produce (FleetConfig always generates a token).
    assertFalse(FleetAuth.authorized("Bearer x", ""))
  }

  @Test fun jsonBodiesOverOneMebibyteAreRefusedAs413BeforeParsing() {
    val cap = 1024 * 1024
    for ((method, path) in listOf("POST" to "/install", "POST" to "/update", "POST" to "/dev", "POST" to "/config",
        "POST" to "/calendar", "POST" to "/screensaver", "POST" to "/action", "POST" to "/perf")) {
      val untouchable = object : java.io.InputStream() { override fun read(): Int = error("body read") }
      val req = FleetHttpServer.Request(method, path, "", mapOf("authorization" to "Bearer ${host.deviceToken}"), untouchable, cap + 1)
      val thrown = runCatching { router.handle(req) }.exceptionOrNull()
      assertTrue("$method $path: $thrown", thrown is FleetHttpServer.RequestBodyTooLarge)
      // The server turns that into the wire error.
      assertEquals("413 body_too_large", FleetReq.outcome(FleetHttpServer.requestBodyError(thrown!!)!!))
    }
    // Exactly at the cap is read.
    val atCap = FleetReq.of("POST", "/action", " ".repeat(cap), auth = "Bearer ${host.deviceToken}")
    assertEquals("400 unknown_action", FleetReq.outcome(router.handle(atCap)))
    assertTrue(host.calls.all { it == "token" })
  }
}
