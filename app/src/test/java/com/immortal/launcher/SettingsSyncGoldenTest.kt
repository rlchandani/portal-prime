/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.SettingsSyncCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Replays the household settings sync scenarios in `sync/sync_scenarios.json` through the real
 * [SettingsSync] and [SettingsSyncNet] ([SyncGoldenHarness]) and compares everything they did with
 * `sync/sync_golden.txt`, recorded before the sync was split. Re-record only on purpose:
 * `GOLDEN_RECORD=1 ./gradlew :app:testDebugUnitTest --tests '*SettingsSyncGoldenTest*'`.
 *
 * The Portal under test has no household key unless a scenario provisions one, so nothing leaves
 * the JVM: bus sends and peer POSTs stop at the key check, as they do on a Portal not yet paired.
 */
class SettingsSyncGoldenTest {
  @Test fun everyRecordedScenarioReplaysTheSame() {
    val spec = JSONObject(ControlCenterEngineGoldenTest.resource("sync/sync_scenarios.json"))
    val scenarios = spec.getJSONArray("scenarios")
    val got = LinkedHashMap<String, List<String>>()
    for (i in 0 until scenarios.length()) {
      val sc = scenarios.getJSONObject(i)
      got[sc.getString("name")] = SyncGoldenRun(spec.getJSONObject("data"), sc).run()
    }
    if (System.getenv("GOLDEN_RECORD") == "1") { GoldenText.write(File(RECORD_PATH), got); return }
    val want = GoldenText.parse(ControlCenterEngineGoldenTest.resource("sync/sync_golden.txt"))
    assertTrue("fixture has too few scenarios", want.size >= MIN_SCENARIOS)
    val diffs = GoldenText.diff(want, got)
    if (diffs.isNotEmpty()) fail(diffs.joinToString("\n", "${diffs.size} scenario(s) differ:\n"))
  }

  private companion object {
    const val RECORD_PATH = "src/test/resources/sync/sync_golden.txt"
    const val MIN_SCENARIOS = 10
  }
}

private class SyncGoldenRun(private val data: JSONObject, private val sc: JSONObject) {
  private val dir = Files.createTempDirectory("sync-golden").toFile()
  private val h = SyncGoldenHarness(dir)
  private var seen = HashMap<String, String>()
  private var nonce = 0

  fun run(): List<String> = try {
    sc.optJSONObject("prefs")?.let(::putPrefs)
    sc.optJSONObject("ledger")?.let { h.prefs("immortal_settings_sync").edit().putString("ledger", ledgerText(it)).apply() }
    note()
    val steps = sc.getJSONArray("steps")
    for (i in 0 until steps.length()) {
      step(steps.getJSONObject(i))
      h.drain()
      note()
    }
    h.out.toList()
  } catch (t: Throwable) {
    h.out + "harness error ${t.javaClass.simpleName}: ${t.message}"
  } finally {
    h.close()
    dir.deleteRecursively()
  }

  /** Notes each view whenever it changed since the last step. */
  private fun note() {
    for ((kind, line) in listOf("ledger" to h.ledger(), "values" to h.values(), "status" to h.status())) {
      if (seen[kind] != line) { h.out += line; seen[kind] = line }
    }
  }

  private fun putPrefs(all: JSONObject) {
    for (name in all.keys()) {
      val values = all.getJSONObject(name)
      val ed = h.prefs(name).edit()
      for (k in values.keys()) when (val v = values.get(k)) {
        is Boolean -> ed.putBoolean(k, v)
        is Int -> ed.putInt(k, v)
        else -> ed.putString(k, v.toString())
      }
      ed.apply()
    }
  }

  /** A stored ledger, written the way [SettingsSyncStore] does. */
  private fun ledgerText(o: JSONObject): String {
    val records = records(o.optJSONArray("records")).associateBy { it.key }
    val known = o.optJSONObject("lastKnown")?.let { k -> k.keys().asSequence().associateWith { k.get(it) } }.orEmpty()
    val l = SyncLedger(phase = SyncPhase.valueOf(o.getString("phase")), clock = o.optLong("clock"), records = records,
        lastKnown = SettingsSyncCatalog.household.defaults() + known)
    return SettingsSyncStore.encode(l).toString()
  }

  private fun id(name: String) = when (name) {
    "hi" -> SyncGoldenHarness.PEER_HI; "lo" -> SyncGoldenHarness.PEER_LO; "self" -> SyncGoldenHarness.SELF; else -> name
  }

  private fun records(a: JSONArray?): List<SyncRecord> = (0 until (a?.length() ?: 0)).map { i ->
    val r = a!!.getJSONObject(i)
    SyncRecord(r.getString("key"), r.get("value"), SyncVersion(r.getLong("v"), id(r.getString("by"))), r.optLong("ts", 1000L))
  }

  private fun recordJson(r: JSONObject): JSONObject = SettingsSyncWire.recordJson(records(JSONArray().put(r)).single())

  private fun digest(spec: String): String = when (spec) {
    "\$empty" -> SettingsSyncEngine.digest(emptyMap())
    "\$mine" -> SettingsSync.statusJson().getString("digest")
    else -> spec
  }

  private fun message(type: String, from: String, body: JSONObject) =
      HouseholdMessage(type, id(from), System.currentTimeMillis(), "n${nonce++}", body)

  private fun payload(o: JSONObject): String =
      SettingsSyncWire.payload(o.optString("name", "Living"), o.optBoolean("joining"), records(o.optJSONArray("records")))

  private fun step(step: JSONObject) {
    val what = step.getString("do")
    h.out += "do $what" + JSONObject(step.toString()).apply { remove("do") }.takeIf { it.length() > 0 }?.let { " ${FleetGoldenHarness.canonical(it)}" }.orEmpty()
    val c = h.context
    when (what) {
      "install" -> SettingsSync.install(c)
      "changed" -> h.busHandler(SettingsSyncWire.CHANGED_TYPE).onMessage(c,
          message(SettingsSyncWire.CHANGED_TYPE, step.getString("from"), step.optJSONObject("raw") ?: recordJson(step.getJSONObject("record"))))
      "signal" -> h.busHandler(SettingsSyncWire.SIGNAL_TYPE).onMessage(c, message(SettingsSyncWire.SIGNAL_TYPE, step.getString("from"),
          step.optJSONObject("raw") ?: SettingsSyncWire.signalBody(SettingsSyncWire.Signal(digest(step.getString("digest")), PEER_HOST,
              step.optInt("port", SyncGoldenHarness.PORT), step.optBoolean("joining")))))
      "peer" -> h.out += "peer -> " + SettingsSync.handlePeer(c, id(step.getString("from")), step.optString("raw").ifBlank { payload(step.getJSONObject("payload")) })
      "route" -> route(step)
      "local" -> { val v = step.getJSONObject("values"); SettingsSyncCatalog.household.apply(c, v.keys().asSequence().associateWith { v.get(it) }) }
      "fireMain" -> { val due = synchronized(h.posted) { h.posted.toList().also { h.posted.clear() } }; due.forEach { it.first.run() } }
      "resolveJoin" -> SettingsSync.resolveJoin(step.getBoolean("keepMine"))
      "dismissAdoptCard" -> SettingsSync.dismissAdoptCard()
      "pause" -> SettingsSync.setPaused(step.getBoolean("on"))
      "rejoin" -> SettingsSync.rejoin()
      "values" -> h.out += "valuesJson " + FleetGoldenHarness.canonical(SettingsSync.valuesJson()).let { v ->
        JSONObject(v).let { o -> step.getJSONArray("keys").let { k -> (0 until k.length()).joinToString(",") { "${k.getString(it)}=${o.opt(k.getString(it))}" } } }
      }
      "key" -> HouseholdKey.set(c, KEY)
      else -> error("unknown step $what")
    }
  }

  /** `/remote/settingspeer` as a peer would call it, signed (or not) as the step says. */
  private fun route(step: JSONObject) {
    val body = step.optString("body").ifBlank { step.optJSONObject("payload")?.let(::payload).orEmpty() }
    val from = id(step.optString("from", "hi"))
    val n = step.optString("nonce").ifBlank { "nonce${nonce++}" }
    val headers = HashMap<String, String>()
    when (step.optString("auth", "good")) {
      "good" -> headers[SettingsSyncWire.HEADER] = SettingsSyncWire.requestHeader(KEY, from, n, System.currentTimeMillis(), body)!!
      "otherKey" -> headers[SettingsSyncWire.HEADER] = SettingsSyncWire.requestHeader(OTHER_KEY, from, n, System.currentTimeMillis(), body)!!
      "otherBody" -> headers[SettingsSyncWire.HEADER] = SettingsSyncWire.requestHeader(KEY, from, n, System.currentTimeMillis(), "$body ")!!
      "stale" -> headers[SettingsSyncWire.HEADER] = SettingsSyncWire.requestHeader(KEY, from, n, System.currentTimeMillis() - STALE_MS, body)!!
      "none" -> Unit
    }
    val bytes = body.toByteArray(Charsets.UTF_8)
    val length = if (step.has("contentLength")) step.getInt("contentLength") else bytes.size
    val req = FleetHttpServer.Request(step.optString("method", "POST"), SettingsSyncNet.PATH, "", headers, ByteArrayInputStream(bytes), length)
    SettingsSyncNet.handle(h.context, req).use { resp ->
      val outBytes = ByteArrayOutputStream().also { o -> resp.jsonBytes?.let(o::write) ?: resp.streamWriter?.invoke(o) }
      val json = runCatching { JSONObject(outBytes.toString("UTF-8")) }.getOrNull()
      val signed = json?.optString("sig")?.takeIf { it.isNotEmpty() }?.let { sig ->
        if (SettingsSyncWire.verifyResponse(KEY, n, json.optString("payload"), sig)) "sig=valid" else "sig=INVALID"
      } ?: "unsigned"
      json?.remove("sig")
      h.out += "route -> ${resp.status} $signed " + (json?.let { FleetGoldenHarness.canonical(it) } ?: outBytes.toString("UTF-8"))
    }
  }

  private companion object {
    val KEY = ByteArray(32) { (it * 7 + 1).toByte() }
    val OTHER_KEY = ByteArray(32) { (it * 5 + 3).toByte() }
    /** A made-up private-LAN peer address (built here so no address literal sits in the fixtures). */
    val PEER_HOST = listOf(10, 0, 0, 7).joinToString(".")
    const val STALE_MS = 10 * 60_000L
  }
}
