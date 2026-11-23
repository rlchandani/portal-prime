/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Versioned JSON for a [MemoryProfile] (house style: hand-rolled JSON, no Room). The plaintext only
 * ever exists in memory: [MemoryProfileStore] seals it before it touches disk.
 *
 * [decode] is strict about the envelope (version, scope) and lenient per item: a fact or topic it
 * can't read (a kind from a newer build, an oversized field) is dropped rather than failing the
 * whole profile, since everything here is rebuildable. Both directions apply [MemoryProfile.normalized].
 */
internal object MemoryProfileCodec {
  const val VERSION = 1
  private const val MAX_LABEL = 120
  private const val MAX_ATTRS = 16
  private const val MAX_ATTR_CHARS = 200
  private const val MAX_TEXT = 200

  class FormatException(message: String) : Exception(message)

  fun encode(profile: MemoryProfile): String {
    val p = profile.normalized()
    return JSONObject()
        .put("v", VERSION)
        .put("scope", p.scope.token)
        .put("facts", JSONArray().apply { p.facts.forEach { put(fact(it)) } })
        .put("topics", JSONArray().apply { p.recentTopics.forEach { put(topic(it)) } })
        .apply { if (p.paused) put("paused", true) }
        .apply { if (p.pausedMs > 0) put("pausedAt", p.pausedMs) }
        .apply { if (p.forgotAllMs > 0) put("forgotAllAt", p.forgotAllMs) }
        .toString()
  }

  /** The profile in [text], which must be for [expected] (a file can't be swapped between people). */
  fun decode(text: String, expected: MemoryScope): MemoryProfile {
    val root = try {
      JSONObject(text)
    } catch (e: JSONException) {
      throw FormatException("not a profile")
    }
    if (root.optInt("v", -1) != VERSION) throw FormatException("unsupported version")
    if (MemoryScope.fromToken(root.optString("scope")) != expected) throw FormatException("scope mismatch")
    val facts = root.optJSONArray("facts").objects().mapNotNull(::readFact)
    val topics = root.optJSONArray("topics").objects().mapNotNull(::readTopic)
    return MemoryProfile(expected, facts, topics, paused = root.optBoolean("paused", false),
        pausedMs = root.optLong("pausedAt", 0), forgotAllMs = root.optLong("forgotAllAt", 0)).normalized()
  }

  private fun fact(f: MemoryFact): JSONObject = JSONObject()
      .put("id", f.id).put("kind", f.kind.name).put("label", f.label)
      .put("attrs", JSONObject().apply { f.attrs.toSortedMap().forEach { (k, v) -> put(k, v) } })
      .put("evidence", JSONArray().apply { f.evidence.forEach { put(evidence(it)) } })
      .put("folded", f.foldedEvidence).put("conf", f.confidence).put("state", f.state.name)
      .put("first", f.firstSeenMs).put("last", f.lastSeenMs).put("sensitive", f.sensitive)
      .apply { if (f.stateMs > 0) put("stateAt", f.stateMs) }

  private fun evidence(e: Evidence): JSONObject = JSONObject()
      .put("src", e.source.name).put("at", e.atMs).put("portal", e.portal)
      .apply { e.conversationId?.let { put("conv", it) } }
      .put("w", e.weight)

  private fun topic(t: RecentTopic): JSONObject = JSONObject()
      .put("topic", t.topic).put("gist", t.gist)
      .apply { t.openQuestion?.let { put("open", it) } }
      .put("at", t.atMs)
      .apply { if (t.shownCount > 0) put("shown", t.shownCount) }

  private fun readFact(o: JSONObject): MemoryFact? = runCatching {
    MemoryFact(
        id = o.text("id", MAX_TEXT),
        kind = MemoryFact.Kind.valueOf(o.getString("kind")),
        label = o.text("label", MAX_LABEL, allowEmpty = true),
        attrs = readAttrs(o.optJSONObject("attrs")),
        evidence = o.optJSONArray("evidence").objects().mapNotNull(::readEvidence),
        foldedEvidence = o.optInt("folded", 0),
        confidence = o.getDouble("conf"),
        state = MemoryFact.State.valueOf(o.getString("state")),
        firstSeenMs = o.getLong("first"),
        lastSeenMs = o.getLong("last"),
        sensitive = o.optBoolean("sensitive", false),
        stateMs = o.optLong("stateAt", 0),
    ).let(::withStateTime)
  }.getOrNull()

  /**
   * A profile written before slice 5 has no `stateAt`: a forgotten fact was suppressed at its last sighting, and a
   * confirmed one at its newest Keep or remember.
   */
  private fun withStateTime(f: MemoryFact): MemoryFact = when {
    f.stateMs > 0 -> f
    f.state == MemoryFact.State.SUPPRESSED || f.state == MemoryFact.State.TOMBSTONE -> f.copy(stateMs = f.lastSeenMs)
    f.state == MemoryFact.State.CONFIRMED -> f.copy(stateMs = f.evidence
        .filter { it.source == Evidence.Source.CONFIRM || it.source == Evidence.Source.EXPLICIT }
        .maxOfOrNull { it.atMs } ?: f.lastSeenMs)
    else -> f
  }

  private fun readEvidence(o: JSONObject): Evidence? = runCatching {
    Evidence(
        source = Evidence.Source.valueOf(o.getString("src")),
        atMs = o.getLong("at"),
        portal = o.text("portal", MAX_TEXT),
        conversationId = if (o.has("conv")) o.text("conv", MAX_TEXT) else null,
        weight = o.getDouble("w"),
    )
  }.getOrNull()

  private fun readTopic(o: JSONObject): RecentTopic? = runCatching {
    RecentTopic(
        topic = o.text("topic", MAX_TEXT),
        gist = o.text("gist", RecentTopic.MAX_GIST, allowEmpty = true),
        openQuestion = if (o.has("open")) o.text("open", MAX_TEXT) else null,
        atMs = o.getLong("at"),
        shownCount = o.optInt("shown", 0),
    )
  }.getOrNull()

  private fun readAttrs(o: JSONObject?): Map<String, String> {
    if (o == null) return emptyMap()
    require(o.length() <= MAX_ATTRS) { "too many attrs" }
    return o.keys().asSequence().associateWith { o.text(it, MAX_ATTR_CHARS, allowEmpty = true) }
  }

  private fun JSONObject.text(name: String, max: Int, allowEmpty: Boolean = false): String {
    val value = get(name) as? String ?: throw FormatException("$name is not text")
    require(value.length <= max && (allowEmpty || value.isNotEmpty())) { "$name out of range" }
    return value
  }

  private fun JSONArray?.objects(): List<JSONObject> =
      if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
