/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Replays the recorded request/response pairs in `fleet/fleet_routes_golden.json` through the real
 * [FleetRoutes] (a mocked Context backed by in-memory preferences and a temp directory). The file
 * was recorded before FleetRoutes was split, so any difference here is a wire change.
 */
class FleetRoutesGoldenTest {
  private val tmp: File = Files.createTempDirectory("fleet-golden").toFile().canonicalFile
  private val harness = FleetGoldenHarness(tmp)

  @After fun cleanUp() { tmp.deleteRecursively() }

  @Test fun everyRecordedRequestGetsItsRecordedResponse() {
    val failures = mutableListOf<String>()
    val cases = goldenCases()
    assertTrue("fixture file is empty", cases.length() > 100)
    for (i in 0 until cases.length()) {
      val case = cases.getJSONObject(i)
      val name = case.getString("name")
      // Each case starts from the same device state.
      val h = FleetGoldenHarness(Files.createTempDirectory("fleet-golden-case").toFile().canonicalFile)
      try {
        runCatching { h.check(case) }.onFailure { failures += "$name: ${it.message}" }
      } finally {
        h.dir.deleteRecursively()
      }
    }
    if (failures.isNotEmpty()) fail(failures.joinToString("\n", "${failures.size} golden case(s) differ:\n"))
  }

  @Test fun theBearerGateRunsBeforeAnyRouteWork() {
    // A refused write must not create the file, read the body or touch storage.
    val case = JSONObject().put("method", "POST").put("path", "/fs/write").put("query", "path=${tmp.path}/x")
        .put("auth", "none").put("body", "secret")
    val resp = harness.call(case)
    assertEquals(401, resp.status)
    assertTrue(!File(tmp, "x").exists())
  }

  companion object {
    fun goldenCases(): JSONArray {
      val text = requireNotNull(FleetRoutesGoldenTest::class.java.classLoader!!
          .getResourceAsStream("fleet/fleet_routes_golden.json")).bufferedReader().readText()
      return JSONObject(text).getJSONArray("cases")
    }
  }
}

/** A Portal with in-memory preferences, a fixed token and a scratch directory for file routes. */
internal class FleetGoldenHarness(val dir: File) {
  private val prefs = HashMap<String, ScreensaverTestPrefs>()
  val context: Context = mock(Context::class.java).also { c ->
    `when`(c.applicationContext).thenReturn(c)
    `when`(c.getSharedPreferences(anyString(), anyInt())).thenAnswer { prefs(it.getArgument(0)) }
    // FleetConfig mirrors its manifest here; never into the working directory.
    `when`(c.getExternalFilesDir(org.mockito.ArgumentMatchers.any())).thenReturn(File(dir, ".external"))
    `when`(c.cacheDir).thenReturn(File(dir, ".cache"))
  }
  private val routes = FleetRoutes(context)

  init {
    prefs("fleet_agent").edit().putString("token", TOKEN).apply()
    File(dir, "sub").mkdirs()
    File(dir, "hello.txt").writeText("hello fleet")
    File(dir, "sub").setLastModified(FIXED_MTIME)
    File(dir, "hello.txt").setLastModified(FIXED_MTIME)
  }

  fun prefs(name: String): ScreensaverTestPrefs = prefs.getOrPut(name) { ScreensaverTestPrefs() }

  private fun expand(s: String) = s.replace("\${TOKEN}", TOKEN).replace("\${TMP}", dir.path)

  fun call(case: JSONObject): FleetHttpServer.Response {
    val setup = case.optJSONArray("setup")
    for (i in 0 until (setup?.length() ?: 0)) when (val step = setup!!.getString(i)) {
      "devMode" -> prefs("immortal_dev").edit().putBoolean("enabled", true).apply()
      else -> error("unknown setup step $step")
    }
    val body = expand(case.optString("body")).toByteArray(Charsets.UTF_8)
    val headers = HashMap<String, String>()
    when (val auth = case.optString("auth", "good")) {
      "none" -> Unit
      "good" -> headers["authorization"] = "Bearer $TOKEN"
      else -> headers["authorization"] = expand(auth)
    }
    val length = if (case.has("contentLength")) case.getInt("contentLength") else body.size
    val req = FleetHttpServer.Request(case.getString("method"), case.getString("path"), expand(case.optString("query")),
        headers, ByteArrayInputStream(body), length)
    return routes.handle(req)
  }

  fun check(case: JSONObject) {
    val expect = case.getJSONObject("expect")
    call(case).use { resp ->
      val out = ByteArrayOutputStream()
      resp.jsonBytes?.let(out::write) ?: resp.streamWriter?.invoke(out)
      val text = out.toString("UTF-8")
      val got = "${resp.status} $text"
      check(resp.status == expect.getInt("status")) { "status: got $got" }
      expect.optJSONObject("json")?.let { want ->
        val wantJson = JSONObject(expand(want.toString()))
        check(resp.contentType == "application/json; charset=utf-8") { "content type ${resp.contentType}" }
        check(canonical(wantJson) == canonical(JSONObject(text))) { "body: got $text, want $wantJson" }
      }
      if (expect.has("contentType")) check(resp.contentType == expect.getString("contentType")) { "type ${resp.contentType}" }
      if (expect.has("length")) check(resp.streamLength == expect.getLong("length")) { "length ${resp.streamLength}" }
      expect.optJSONObject("headers")?.let { want ->
        for (k in want.keys()) check(resp.extraHeaders[k] == want.getString(k)) { "header $k: ${resp.extraHeaders[k]}" }
      }
      if (expect.has("text")) check(text == expect.getString("text")) { "text: $text" }
      expect.optJSONObject("file")?.let { f ->
        val file = File(expand(f.getString("path")))
        check(file.isFile && file.readText() == f.getString("text")) { "file ${file.path}" }
      }
    }
  }

  companion object {
    /** Key-sorted rendering, so two JSON values compare structurally (numbers by value). */
    fun canonical(v: Any?): String = when (v) {
      is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canonical(v.get(it)) }
      is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
      is String -> JSONObject.quote(v)
      null, JSONObject.NULL -> "null"
      else -> v.toString()
    }

    const val TOKEN = "0123456789abcdef0123456789abcdef"
    const val FIXED_MTIME = 1_700_000_000_000L
  }
}
