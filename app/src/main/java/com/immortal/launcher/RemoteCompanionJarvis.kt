/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Only finite IPC configuration. No audio probe or credential fields cross this boundary. */
internal object RemoteCompanionJarvis {
  private val uri by lazy { Uri.parse("content://com.portal.assistant.control") }
  private val gate = Semaphore(1)
  private val ipc = Executors.newSingleThreadExecutor { task -> Thread(task, "remote-jarvis-ipc").apply { isDaemon = true } }
  private fun call(c: Context, method: String, extras: Bundle? = null): Bundle? {
    if (!gate.tryAcquire()) return null
    val result = try {
      ipc.submit<Bundle?> {
        try { runCatching { c.contentResolver.call(uri, method, null, extras) }.getOrNull() }
        finally { gate.release() }
      }
    } catch (e: Exception) { gate.release(); return null }
    // A timed-out provider may still be running; keep the gate until the actual call finishes.
    return runCatching { result.get(2, TimeUnit.SECONDS) }.getOrNull()
  }

  fun status(c: Context): JSONObject {
    val installed = StoreCatalog.isInstalled(c, "com.portal.assistant")
    val bundle = call(c, "status")
    if (bundle == null || !bundle.containsKey("model") || !bundle.containsKey("wakeEnabled"))
      return JSONObject().put("installed", installed).put("supported", false).put("reason", if (installed) "olderJarvis" else "notInstalled")
    if (!bundle.getBoolean("supported", false)) return JSONObject().put("installed", installed).put("supported", false).put("reason", "ipcRejected")
    return project(bundle).put("installed", true).put("supported", true)
  }
  internal fun project(b: Bundle): JSONObject {
    val out = JSONObject()
    for (key in listOf("status", "model", "supported", "microphoneGranted", "microphoneMuted", "wakeEnabled", "wakeSupported", "running", "foreground", "serviceRunning", "wakeListening")) {
      @Suppress("DEPRECATION") val value = b.get(key)
      if (value is String || value is Boolean) out.put(key, value)
    }
    b.getStringArrayList("availableModels")?.let { out.put("availableModels", JSONArray(it.take(32))).put("models", JSONArray(it.take(32))) }
    if (!out.has("status")) out.put("status", if (b.getBoolean("running", false) || b.getBoolean("serviceRunning", false)) "running" else "idle")
    return out
  }
  fun validConfiguration(body: JSONObject): Boolean = body.length() in 1..2 && body.only("model", "wakeEnabled") &&
      (!body.has("model") || body.opt("model") is String && Regex("[a-zA-Z0-9._-]{1,100}").matches(body.getString("model"))) &&
      (!body.has("wakeEnabled") || body.opt("wakeEnabled") is Boolean)

  fun configure(c: Context, body: JSONObject): JSONObject {
    if (!validConfiguration(body)) return JSONObject().put("ok", false).put("error", "invalid_configuration")
    val before = status(c)
    if (!before.optBoolean("supported")) return JSONObject().put("ok", false).put("error", "olderJarvis").put("jarvis", before)
    // Validate the exact finite model list advertised by the Jarvis provider.
    val models = before.optJSONArray("availableModels")
    if (body.has("model") && (models == null || (0 until models.length()).none { models.optString(it) == body.getString("model") }))
      return JSONObject().put("ok", false).put("error", "unsupported_model")
    val extras = Bundle()
    if (body.has("model")) extras.putString("model", body.getString("model"))
    if (body.has("wakeEnabled")) extras.putBoolean("wakeEnabled", body.getBoolean("wakeEnabled"))
    val response = call(c, "configure", extras)
        ?: return JSONObject().put("ok", false).put("error", "ipc_failed")
    if (response.containsKey("ok") && !response.getBoolean("ok")) return JSONObject().put("ok", false).put("error", "configuration_rejected")
    val after = status(c)
    val verified = after.optBoolean("supported") && body.fieldKeys().all { body.opt(it) == after.opt(it) }
    return JSONObject().put("ok", verified).put("jarvis", after).apply { if (!verified) put("error", "readback_failed") }
  }
}
