/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.settings.*
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Inspect registry constraints without calling a setter or a domain side effect. */
internal object RemoteSnapshotPolicy {
  const val SCHEMA_VERSION = 1
  // Other strings contain connection data, usernames, or URLs with embedded credentials.
  private fun safe(domain: String, spec: SettingSpec<*>): Boolean = when (spec) {
    is BoolSpec, is IntSpec, is EnumSpec -> true
    is StringSpec -> !spec.secret && domain == "fleet" && spec.key == "name"
    else -> false
  }

  @Suppress("UNCHECKED_CAST")
  fun values(c: Context, domains: List<SettingsDomain<*>>): JSONObject {
    val out = JSONObject()
    for (raw in domains) {
      val domain = raw as SettingsDomain<Any>
      val values = JSONObject()
      val snapshot = domain.load(c)
      domain.specs.filter { safe(domain.id, it) }.forEach { it.putValue(values, snapshot) }
      out.put(domain.id, values)
    }
    return out
  }

  fun validateValues(domain: SettingsDomain<*>, values: JSONObject): Boolean =
      values.length() <= 256 && values.fieldKeys().all { key ->
        val spec = domain.specs.firstOrNull { it.key == key }
        val v = values.opt(key)
        if (spec == null || !safe(domain.id, spec)) false else when (spec) {
          is BoolSpec -> v is Boolean
          is IntSpec -> v.integer()?.let { it in spec.min.toLong()..spec.max.toLong() } == true
          is EnumSpec -> v is String && spec.options.any { it.first == v } && spec.coerce(v) == v
          is StringSpec -> v is String && v.text(4096) && spec.applyWhen(v)
          else -> false
        }
      }

  fun validateSnapshot(s: JSONObject, deviceId: String, revision: String,
      domains: List<SettingsDomain<*>>, widgets: List<HomeWidgetStore.HomeWidget>, expectedRevision: String? = null): RemoteCompanionValidation {
    val errors = mutableListOf<String>()
    if (!s.only("schemaVersion", "deviceId", "revision", "domains", "layout")) errors += "unknown_snapshot_field"
    if (s.opt("schemaVersion").integer() != SCHEMA_VERSION.toLong()) errors += "unsupported_schema"
    if (s.opt("deviceId") != deviceId) errors += "wrong_device"
    if (s.opt("revision") !is String || !Regex("[0-9a-f]{64}").matches(s.optString("revision"))) errors += "invalid_revision"
    if ((expectedRevision ?: s.opt("revision")) != revision) errors += "stale_revision"
    val values = s.optJSONObject("domains")
    if (values == null || values.length() > domains.size) errors += "domains_required"
    else for (id in values.fieldKeys()) {
      val domain = domains.firstOrNull { it.id == id }
      val v = values.optJSONObject(id)
      if (domain == null || v == null || !validateValues(domain, v)) errors += "invalid_domain:$id"
    }
    val layout = s.optJSONObject("layout")
    if (layout == null) errors += "layout_required" else errors += RemoteLayoutPolicy.validate(layout, widgets).errors
    return RemoteCompanionValidation(errors)
  }

  fun applyVerified(c: Context, domain: SettingsDomain<*>, requested: JSONObject): JSONObject = synchronized(domain) {
    val attempt = runCatching { domain.apply(c, requested) }
    val actual = runCatching { values(c, listOf(domain)).getJSONObject(domain.id) }.getOrDefault(JSONObject())
    fun equal(a: Any?, b: Any?): Boolean = if (a.integer() != null && b.integer() != null) a.integer() == b.integer() else a == b
    val confirmed = requested.fieldKeys().filter { actual.has(it) && equal(requested.opt(it), actual.opt(it)) }
    val mismatches = JSONArray()
    (requested.fieldKeys() - confirmed.toSet()).sorted().forEach { key ->
      mismatches.put(JSONObject().put("key", key).put("requested", requested.opt(key)).put("actual", actual.opt(key) ?: JSONObject.NULL))
    }
    val complete = attempt.isSuccess && attempt.getOrThrow() == requested.fieldKeys() && mismatches.length() == 0
    JSONObject().put("domain", domain.id).put("ok", complete).put("applied", JSONArray(confirmed))
        .put("mismatches", mismatches).apply {
          if (!complete) put("error", if (attempt.isFailure) "apply_failed" else if (mismatches.length() > 0) "readback_mismatch" else "apply_incomplete")
        }
  }

  /** Canonical fingerprint catches external UI/registry changes as well as paired writes. */
  fun revision(domains: JSONObject, layout: JSONObject): String {
    fun canonical(v: Any?): String = when (v) {
      is JSONObject -> v.fieldKeys().sorted().joinToString(prefix="{", postfix="}") { JSONObject.quote(it) + ":" + canonical(v.get(it)) }
      is JSONArray -> (0 until v.length()).joinToString(prefix="[", postfix="]") { canonical(v.get(it)) }
      is String -> JSONObject.quote(v)
      else -> v.toString()
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical(JSONObject().put("domains", domains).put("layout", layout)).toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
  }
}
