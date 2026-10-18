/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Companion-owned metadata only. Device settings stay in their native registry/stores. */
internal object RemoteCompanionStore {
  val mutationLock = Any()
  private fun file(c: Context) = AtomicFile(File(c.noBackupFilesDir, "remote-companion.json"))
  private fun read(c: Context): JSONObject = runCatching { JSONObject(file(c).openRead().use { it.readBytes().toString(Charsets.UTF_8) }) }.getOrDefault(JSONObject())
  fun id(c: Context): String = synchronized(mutationLock) {
    val saved = read(c).optString("deviceId")
    if (runCatching { UUID.fromString(saved).toString() == saved }.getOrDefault(false)) saved
    else UUID.randomUUID().toString().also { update(c, "deviceId", it) }
  }
  fun scenes(c: Context): JSONArray = synchronized(mutationLock) { read(c).optJSONArray("scenes") ?: JSONArray() }
  fun history(c: Context): JSONArray = synchronized(mutationLock) { read(c).optJSONArray("operations") ?: JSONArray() }
  fun update(c: Context, key: String, value: Any) = synchronized(mutationLock) {
    val out = read(c).put(key, value)
    val f = file(c)
    val stream = f.startWrite()
    try { stream.write(out.toString().toByteArray(Charsets.UTF_8)); f.finishWrite(stream) }
    catch (e: Exception) { f.failWrite(stream); throw e }
  }
}

/** Persistent, bounded results. Installer uncertainty survives process death and history pruning. */
internal class RemoteCompanionHistory(saved: JSONArray = JSONArray(), private val persist: (JSONArray) -> Unit = {}) {
  private val entries = mutableListOf<JSONObject>()
  init {
    for (i in 0 until minOf(saved.length(), 50)) saved.optJSONObject(i)?.let {
      val item = JSONObject(it.toString())
      val restarted = item.optString("state") == "running"
      val install = item.optString("type") in setOf("install", "update")
      val uncertain = item.optBoolean("installUncertain") || (install &&
          (item.optBoolean("backendMayContinue") || restarted && item.optString("installPhase") != "preparing"))
      if (restarted) item.put("state", "interrupted").put("error", "process_restarted")
      item.put("installUncertain", uncertain).put("backendMayContinue", uncertain).put("cancelSupported", false)
      val reconciliation = item.optJSONObject("reconciliation")
      val results = item.optJSONArray("results") ?: JSONArray()
      val pendingResult = (0 until results.length()).any {
        results.optJSONObject(it)?.let { r -> r.optBoolean("backendMayContinue") || r.optBoolean("installUncertain") } == true
      }
      // Older polls cleared the fence but left timeout/interruption results behind. Native
      // success while the worker was running still permits a completed readback mismatch.
      if (install && !uncertain && reconciliation != null && reconciliation.opt("ok") is Boolean &&
          reconciliation.optString("evidence").isNotEmpty() &&
          (reconciliation.optString("evidence") != "native_installer_success" ||
              item.optString("state") == "interrupted" || pendingResult)) {
        reconcileTerminalOutcome(item, reconciliation)
      }
      item.put("retrySupported", retryable(item))
      entries += item
    }
    save()
  }
  private fun retryable(e: JSONObject) = e.optString("type") != "preset" && e.optString("state") in setOf("failed", "interrupted") &&
      !e.optBoolean("backendMayContinue") && !e.optBoolean("installConfirmed")
  private fun save() { persist(JSONArray(entries)) }
  private fun append(e: JSONObject) {
    entries += e
    while (entries.size > 50) {
      val index = entries.indexOfFirst { it !== e && !it.optBoolean("installUncertain") && it.optString("state") != "running" }
      check(index >= 0) { "protected_history_full" }
      entries.removeAt(index)
    }
    save()
  }
  private fun entry(type: String, target: JSONObject): JSONObject {
    val now = System.currentTimeMillis()
    return JSONObject().put("id", UUID.randomUUID().toString()).put("type", type).put("target", JSONObject(target.toString()))
        .put("state", "running").put("createdAt", now).put("updatedAt", now).put("cancelSupported", type == "preset")
        .put("retrySupported", false).put("backendMayContinue", false).put("installUncertain", false).put("results", JSONArray())
        .apply { if (type in setOf("install", "update")) put("installPhase", "preparing") }
  }
  @Synchronized fun list(): JSONArray = JSONArray(entries.map { JSONObject(it.toString()) }.reversed())
  @Synchronized fun find(id: String): JSONObject? = entries.firstOrNull { it.optString("id") == id }?.let { JSONObject(it.toString()) }
  @Synchronized fun hasUncertainInstall(): Boolean = entries.any { it.optBoolean("installUncertain") }
  @Synchronized fun start(type: String, target: JSONObject): JSONObject? {
    if (entries.any { it.optString("state") == "running" } || type in setOf("install", "update") && hasUncertainInstall()) return null
    val e = entry(type, target)
    append(e)
    return JSONObject(e.toString())
  }
  /** A scheduled busy run is a visible failure; it must not replace the active worker. */
  @Synchronized fun recordFailure(type: String, target: JSONObject, code: String, trigger: String): JSONObject {
    val e = entry(type, target).put("state", "failed").put("trigger", trigger).put("retrySupported", true)
        .put("results", JSONArray().put(JSONObject().put("ok", false).put("result", code)))
    append(e)
    return JSONObject(e.toString())
  }
  /** Persist BEFORE the helper can hand work to Android or the shell daemon. */
  @Synchronized fun installPending(id: String, mode: String, pkg: String, version: Long, at: Long) {
    val e = entries.first { it.optString("id") == id }
    e.put("installPhase", "handed_off").put("installUncertain", true).put("backendMayContinue", true).put("retrySupported", false)
        .put("installEvidence", JSONObject().put("mode", mode).put("packageName", pkg).put("versionCode", version).put("handedOffAt", at))
    save()
  }
  /** Only a confirmed native terminal result releases the durable install fence. */
  @Synchronized fun reconcileInstall(id: String, evidence: String, succeeded: Boolean) {
    val e = entries.firstOrNull { it.optString("id") == id } ?: return
    if (!e.optBoolean("installUncertain")) return
    val now = System.currentTimeMillis()
    val reconciliation = JSONObject().put("evidence", evidence).put("ok", succeeded).put("at", now)
    reconcileTerminalOutcome(e, reconciliation)
    e.put("installUncertain", false).put("backendMayContinue", false).put("installConfirmed", succeeded)
        .put("reconciliation", reconciliation).put("updatedAt", now).put("retrySupported", retryable(e))
    save()
  }
  /** Late native evidence replaces an unresolved terminal outcome, retaining the earlier audit. */
  private fun reconcileTerminalOutcome(e: JSONObject, reconciliation: JSONObject) {
    if (e.optString("state") !in setOf("failed", "interrupted")) return
    if (!reconciliation.has("previous")) {
      val previous = JSONObject().put("state", e.optString("state"))
          .put("results", JSONArray((e.optJSONArray("results") ?: JSONArray()).toString()))
          .put("updatedAt", e.optLong("updatedAt"))
          .put("backendMayContinue", e.optBoolean("backendMayContinue"))
          .put("installUncertain", e.optBoolean("installUncertain"))
      if (e.has("error")) previous.put("error", e.get("error"))
      reconciliation.put("previous", previous)
    }
    val succeeded = reconciliation.getBoolean("ok")
    val result = JSONObject().put("ok", succeeded).put("result", if (succeeded) "installed" else "install_failed")
        .put("backendMayContinue", false).put("installUncertain", false)
    e.optJSONObject("installEvidence")?.optString("packageName")?.takeIf { it.isNotEmpty() }
        ?.let { result.put("packageName", it) }
    e.put("state", if (succeeded) "succeeded" else "failed").put("results", JSONArray().put(result))
    e.remove("error")
  }
  /** Requests stopping remaining macro steps; admission stays busy until the worker stops. */
  @Synchronized fun cancelPreset(id: String): JSONObject? {
    val e = entries.firstOrNull { it.optString("id") == id } ?: return null
    if (e.optString("type") != "preset" || e.optString("state") != "running") return null
    if (!e.optBoolean("cancelRequested")) {
      if (!e.optBoolean("cancelSupported")) return null
      e.put("cancelRequested", true).put("cancelSupported", false).put("updatedAt", System.currentTimeMillis())
      save()
    }
    return JSONObject(e.toString())
  }
  @Synchronized fun cancellationRequested(id: String): Boolean {
    val e = entries.firstOrNull { it.optString("id") == id } ?: return true
    return e.optBoolean("cancelRequested") || e.optString("state") != "running"
  }
  @Synchronized fun finish(id: String, results: JSONArray) {
    val e = entries.firstOrNull { it.optString("id") == id } ?: return
    if (e.optString("state") != "running") return
    val continuing = e.optBoolean("installUncertain") || (0 until results.length()).any { results.optJSONObject(it)?.optBoolean("backendMayContinue") == true }
    val success = !e.optBoolean("installUncertain") && !(e.optString("type") == "preset" && continuing) &&
        results.length() > 0 && (0 until results.length()).all { results.optJSONObject(it)?.opt("ok") == true }
    val cancelled = e.optString("type") == "preset" && (0 until results.length()).any {
      results.optJSONObject(it)?.optString("result") == "cancelled"
    }
    e.put("backendMayContinue", continuing).put("results", results).put("cancelSupported", false)
        .put("state", if (cancelled) "cancelled" else if (success) "succeeded" else "failed")
        .put("updatedAt", System.currentTimeMillis()).put("retrySupported", retryable(e))
    save()
  }
  @Synchronized fun backendStopped(id: String) {
    val e = entries.firstOrNull { it.optString("id") == id } ?: return
    if (!e.optBoolean("backendMayContinue") || e.optBoolean("installUncertain")) return
    e.put("backendMayContinue", false).put("retrySupported", retryable(e))
    save()
  }
}
