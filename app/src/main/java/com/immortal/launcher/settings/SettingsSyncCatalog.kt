/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import android.content.Context
import org.json.JSONObject

/**
 * The household settings sync may carry: every value spec declared [SettingScope.HOUSEHOLD], keyed
 * `domain.key`. Pure (no Context) except [read] / [apply]. Incoming values are checked against the
 * spec's own schema by [validate] — the same constraints the phone remote's apply enforces, but
 * stricter: an enum must be one of its options exactly (no coercion) and an int must be in range
 * even for time-of-day fields. A key that isn't here is never accepted from a peer.
 */
internal class SettingsSyncCatalog(domains: List<SettingsDomain<*>>) {

  class Entry(val domain: SettingsDomain<*>, val spec: SettingSpec<*>) {
    val key: String = "${domain.id}.${spec.key}"
  }

  val entries: Map<String, Entry> = buildMap {
    domains.forEach { domain ->
      domain.specs.forEach { spec ->
        if (spec.effectiveScope.synced && spec.isValueSpec()) {
          val entry = Entry(domain, spec)
          check(entry.key !in NEVER_SYNC) { "${entry.key} must stay on this Portal" }
          check(entry.key.length <= MAX_KEY) { "${entry.key} is too long to sync" }
          put(entry.key, entry)
        }
      }
    }
  }

  val keys: Set<String> get() = entries.keys

  /**
   * [raw] normalised to [key]'s stored type (Boolean, Int or String) if it is a well-formed value —
   * a known household key, the right type, and within the size limits — else null. A well-formed
   * value may be kept (so every Portal's versions converge) even when [validate] refuses to apply
   * it here, e.g. an option only a newer build knows.
   */
  fun wellFormed(key: String, raw: Any?): Any? {
    val entry = entries[key] ?: return null
    return when (entry.spec) {
      is BoolSpec<*> -> raw as? Boolean
      is IntSpec<*> -> raw.exactInt()
      is EnumSpec<*>, is StringSpec<*> -> (raw as? String)?.takeIf { it.length <= MAX_STRING && it.none(Char::isISOControl) }
      else -> null
    }
  }

  /**
   * [raw] normalised to the stored type if it is a value this Portal may apply for [key], else
   * null: unknown key, wrong type, out of range, not an option, too long, a control character or
   * refused by the spec's own apply guard.
   */
  fun validate(key: String, raw: Any?): Any? {
    val entry = entries[key] ?: return null
    return when (val spec = entry.spec) {
      is BoolSpec<*> -> raw as? Boolean
      is IntSpec<*> -> raw.exactInt()?.takeIf { it in spec.min..spec.max }
      is EnumSpec<*> -> (raw as? String)?.takeIf { v ->
        v.length <= MAX_STRING && spec.options.any { it.first == v } && spec.coerce(v) == v
      }
      is StringSpec<*> -> (raw as? String)?.takeIf { v ->
        !spec.secret && v.length <= MAX_STRING && v.none(Char::isISOControl) && spec.applyWhen(v)
      }
      else -> null
    }
  }

  /** This Portal's current household values (`domain.key` → value). Reads only. */
  fun read(c: Context): Map<String, Any> = flat { it.flatJson(c) }

  /** Each household key's default value, for the domains that declare defaults. */
  fun defaults(): Map<String, Any> = flat { domain -> domain.defaults?.let { domain.flatOf(it()) } }

  private fun flat(read: (SettingsDomain<*>) -> JSONObject?): Map<String, Any> {
    val out = LinkedHashMap<String, Any>()
    entries.values.map { it.domain }.distinct().forEach { domain ->
      val json = read(domain) ?: return@forEach
      entries.values.filter { it.domain === domain }.forEach { entry ->
        wellFormed(entry.key, json.opt(entry.spec.key))?.let { out[entry.key] = it }
      }
    }
    return out
  }

  /**
   * Writes validated [values] through each domain's normal apply (so its side effects — screensaver
   * reaffirm, chime reschedule, Control Center reload — run once per domain). Returns the keys the
   * registry accepted.
   */
  fun apply(c: Context, values: Map<String, Any>): Set<String> {
    val applied = LinkedHashSet<String>()
    values.entries.groupBy { entries[it.key]?.domain }.forEach { (domain, group) ->
      if (domain == null) return@forEach
      val body = JSONObject()
      group.forEach { (key, value) -> validate(key, value)?.let { body.put(entries.getValue(key).spec.key, it) } }
      if (body.length() == 0) return@forEach
      domain.apply(c, body).forEach { applied.add("${domain.id}.$it") }
    }
    return applied
  }

  companion object {
    const val MAX_KEY = 64
    const val MAX_STRING = 2048

    /**
     * Never synced, whatever their declared scope: the catalog refuses to build if one is marked
     * household. Drop-ins and the drop-in room grid open this Portal's camera and microphone;
     * Kids mode and Shared room describe the room; the name identifies it; MQTT holds its own
     * connection and credentials.
     */
    val NEVER_SYNC: Set<String> = setOf(
        "calling.dropInsOn", "calling.dropInRooms",
        "controlcenter.portalMode", "controlcenter.sharedRoom",
        "fleet.name",
        "mqtt.enabled", "mqtt.host", "mqtt.port", "mqtt.username", "mqtt.password", "mqtt.useTls", "mqtt.validateCert",
        "immortal.maPassword", "immortal.maApiToken", "immortal.maUsername", "immortal.maPlayerId",
        "calendar.url",
    )

    val household: SettingsSyncCatalog by lazy { SettingsSyncCatalog(SettingsDomains.all + SettingsDomains.syncOnly) }

    private fun SettingSpec<*>.isValueSpec(): Boolean =
        this is BoolSpec<*> || this is IntSpec<*> || this is EnumSpec<*> || this is StringSpec<*>

    @Suppress("UNCHECKED_CAST")
    private fun <S> SettingsDomain<S>.flatOf(snapshot: Any?): JSONObject = flatJson(snapshot as S)

    private fun Any?.exactInt(): Int? = when (this) {
      is Int -> this
      is Long -> if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null
      is Short -> toInt()
      is Byte -> toInt()
      is Double -> if (isFinite() && this % 1.0 == 0.0 && this >= Int.MIN_VALUE && this <= Int.MAX_VALUE) toInt() else null
      is Float -> toDouble().exactInt()
      else -> null
    }
  }
}
