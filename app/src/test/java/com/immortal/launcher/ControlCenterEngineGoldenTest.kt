/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Replays the Home Assistant conversations in `cc/cc_engine_scenarios.json` through the real
 * Control Center engine and its facade ([CcGoldenHarness]) and compares everything the engine did
 * with `cc/cc_engine_golden.txt`, recorded before the engine was split. Any difference is a
 * behaviour change. Re-record only on purpose: `GOLDEN_RECORD=1 ./gradlew :app:testDebugUnitTest
 * --tests '*ControlCenterEngineGoldenTest*'`.
 */
class ControlCenterEngineGoldenTest {
  @Test fun everyRecordedConversationReplaysTheSame() {
    val spec = JSONObject(resource("cc/cc_engine_scenarios.json"))
    val scenarios = spec.getJSONArray("scenarios")
    val data = spec.getJSONObject("data")
    val got = LinkedHashMap<String, List<String>>()
    for (i in 0 until scenarios.length()) {
      val sc = scenarios.getJSONObject(i)
      got[sc.getString("name")] = CcGoldenRun(data, sc).run()
    }
    if (System.getenv("GOLDEN_RECORD") == "1") { GoldenText.write(File(RECORD_PATH), got); return }
    val want = GoldenText.parse(resource("cc/cc_engine_golden.txt"))
    assertTrue("fixture has too few scenarios", want.size >= MIN_SCENARIOS)
    val diffs = GoldenText.diff(want, got)
    if (diffs.isNotEmpty()) fail(diffs.joinToString("\n", "${diffs.size} scenario(s) differ:\n"))
  }

  companion object {
    private const val RECORD_PATH = "src/test/resources/cc/cc_engine_golden.txt"
    private const val MIN_SCENARIOS = 12

    fun resource(path: String): String =
        requireNotNull(ControlCenterEngineGoldenTest::class.java.classLoader!!.getResourceAsStream(path)) { "missing $path" }
            .bufferedReader().readText()
  }
}

/** One scenario: preferences and files first, then the steps against a fresh engine. */
private class CcGoldenRun(private val data: JSONObject, private val sc: JSONObject) {
  private val dir = Files.createTempDirectory("cc-golden").toFile()
  private val h = CcGoldenHarness(dir, data)
  private var last = ""
  private var results = 0

  fun run(): List<String> = try {
    setup()
    h.start()
    h.sched.runDue()
    note()
    steps(sc.getJSONArray("steps"))
    h.out.toList()
  } catch (t: Throwable) {
    h.out + "harness error ${t.javaClass.simpleName}: ${t.message}"
  } finally {
    h.close()
    dir.deleteRecursively()
  }

  private fun setup() {
    sc.optJSONObject("prefs")?.let(::putPrefs)
    sc.opt("localPlan")?.let { File(dir, "control_center_plan.json").writeText(h.resolve(it).toString()) }
  }

  private fun putPrefs(all: JSONObject) {
    for (name in all.keys()) {
      val values = h.resolve(all.get(name)) as JSONObject
      val ed = h.prefs(name).edit()
      for (k in values.keys()) when (val v = values.get(k)) {
        is Boolean -> ed.putBoolean(k, v)
        is Int -> ed.putInt(k, v)
        else -> ed.putString(k, v.toString())
      }
      ed.apply()
    }
  }

  private fun steps(list: JSONArray) {
    for (i in 0 until list.length()) {
      val step = list.getJSONObject(i)
      if (step.has("macro")) { steps(data.getJSONObject("macros").getJSONArray(step.getString("macro"))); continue }
      step(step)
      h.sched.runDue()
      note()
    }
  }

  /** Publishes are what screens see: note the state whenever it changed. */
  private fun note() {
    val s = h.summary()
    if (s != last) { h.out += s; last = s }
  }

  private fun step(step: JSONObject) {
    when {
      step.has("advance") -> { h.out += "advance ${step.getLong("advance")} -> t=${h.rel(h.sched.now + step.getLong("advance"))}"; h.sched.advance(step.getLong("advance")) }
      step.has("fail") -> { h.out += "fail#${h.socket(step).index}"; h.failed(step) }
      step.has("closed") -> { h.out += "closed#${h.socket(step).index} ${step.getInt("closed")}"; h.closed(step) }
      step.has("do") -> act(step)
      else -> h.deliver(step)
    }
  }

  private fun entity(id: String): CcEntity = h.engine.flow.value.entity(id) ?: CcEntity(id, "unknown", emptyMap())

  private fun call(step: JSONObject): CcCall {
    val e = entity(step.getString("entity"))
    return when (val kind = step.getString("call")) {
      "power" -> CcCalls.power(e, step.getBoolean("on"))
      "unlock" -> CcCalls.unlock(e)
      "valveClose" -> CcCalls.valveClose(e)
      "coverOpen" -> CcCalls.coverOpen(e)
      "raw" -> CcCall(step.getString("domain"), step.getString("service"), e.id)
      else -> error("unknown call $kind")
    }
  }

  private fun recorder(tag: String): (Boolean, String?) -> Unit {
    val n = results++
    return { ok, err -> h.out += "$tag#$n ok=$ok err=$err" }
  }

  private fun act(step: JSONObject) {
    val c = h.context
    val what = step.getString("do")
    h.out += "do $what" + step.toString().let { j -> JSONObject(j).apply { remove("do") }.takeIf { it.length() > 0 }?.let { " ${FleetGoldenHarness.canonical(it)}" } ?: "" }
    when (what) {
      "visible" -> ControlCenter.setVisible(c, step.getBoolean("on"))
      "install" -> ControlCenter.install(c)
      "restart" -> ControlCenter.restart(c)
      "retryNow" -> ControlCenter.retryNow(c)
      "reloadSettings" -> ControlCenter.reloadSettings(c)
      "online" -> h.online = step.getBoolean("on")
      "prefs" -> putPrefs(step.getJSONObject("prefs"))
      "pending" -> h.out += "timers ${h.sched.pendingDelays()}"
      "state" -> h.out += h.summary()
      "files" -> h.out += h.files()
      "send" -> h.out += "send -> " + ControlCenter.send(c, call(step), onResult = recorder("result"), confirmed = step.optBoolean("confirmed"))
      "phone" -> h.out += "phone -> " + ControlCenter.sendFromPhone(c, call(step), recorder("phoneResult"))
      "alarm" -> ControlCenter.alarm(c, entity(step.getString("entity")), CcAlarmMode.valueOf(step.getString("mode")),
          step.optString("code"), recorder("alarmResult"))
      "undo" -> ControlCenter.undo(c)
      "dismissToast" -> h.engine.flow.value.toast?.let { ControlCenter.dismissToast(c, it.id) }
      "runScene" -> h.out += "runScene -> " + ControlCenter.runScene(c, entity(step.getString("entity")), step.optBoolean("confirmed"))
      "clearScene" -> ControlCenter.clearSceneRun(c)
      "debug" -> h.out += "debug -> " + ControlCenter.debug(c,
          if (step.has("dryRun")) step.getBoolean("dryRun") else null,
          step.optString("testPlan").ifBlank { null },
          step.optString("testAlert").ifBlank { null },
          if (step.has("haUnreachable")) step.getBoolean("haUnreachable") else null,
          step.optJSONObject("fake")?.let { CcFakeState.parse(it) ?: error("bad fake $it") })
      "savePlan" -> ControlCenter.savePlan(c, plan(step))
      "stream" -> ControlCenter.cameraStream(c, step.getString("entity")) { h.out += "stream -> $it" }
      "chargeWeek" -> ControlCenter.loadChargeWeek(c)
      "remoteWatch" -> ControlCenter.remoteWatch(c)
      "dismissAlert" -> {
        val alert = h.engine.flow.value.alerts().first { it.entityId == step.getString("entity") }
        ControlCenter.dismissAlert(c, alert, CcGoldenHarness.T0 + step.getLong("until"))
      }
      else -> error("unknown action $what")
    }
  }

  private fun plan(step: JSONObject): CcPlan {
    if (step.has("plan")) return requireNotNull(CcPlanJson.decode(h.resolve(step.get("plan")) as JSONObject))
    // A plan far over HA's user-data ceiling: many rooms with long names.
    val rooms = (0 until step.getInt("bigRooms")).map { i ->
      CcRoom("r$i", "Room $i " + "n".repeat(BIG_NAME), CcRect(i % 40, i / 40, 1, 1), false, emptyList())
    }
    return CcPlan(listOf(CcFloor("ground", "Ground", rooms)), 0L)
  }

  private companion object {
    const val BIG_NAME = 180
  }
}

/** The recorded transcripts: `## name` then one line per thing that happened. */
internal object GoldenText {
  fun write(file: File, all: Map<String, List<String>>) {
    file.parentFile.mkdirs()
    file.writeText(all.entries.joinToString("") { (name, lines) -> "## $name\n" + lines.joinToString("") { "$it\n" } + "\n" })
  }

  fun parse(text: String): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    var current: MutableList<String>? = null
    for (line in text.lines()) when {
      line.startsWith("## ") -> current = mutableListOf<String>().also { out[line.removePrefix("## ")] = it }
      line.isEmpty() -> Unit
      else -> current?.add(line)
    }
    return out
  }

  fun diff(want: Map<String, List<String>>, got: Map<String, List<String>>): List<String> {
    val diffs = mutableListOf<String>()
    (want.keys + got.keys).forEach { name ->
      val w = want[name]; val g = got[name]
      if (w == null) { diffs += "$name: not in the recording"; return@forEach }
      if (g == null) { diffs += "$name: not replayed"; return@forEach }
      val at = (0 until maxOf(w.size, g.size)).firstOrNull { w.getOrNull(it) != g.getOrNull(it) } ?: return@forEach
      diffs += "$name: line ${at + 1}\n  want: ${w.getOrNull(at)}\n  got:  ${g.getOrNull(at)}"
    }
    return diffs
  }
}
