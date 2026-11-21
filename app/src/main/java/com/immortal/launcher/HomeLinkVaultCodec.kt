/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Strict codec for the stored and synced vault. Anything off parses to null. */
internal object HomeLinkVaultCodec {
  private const val VERSION = 1
  private const val MAX_ENTRIES = HomeLinkBook.MAX_LINKS + HomeLinkVault.MAX_TOMBSTONES

  fun encode(v: HomeLinkVault): String {
    val o = JSONObject().put("v", VERSION)
    v.identity?.let { o.put("id", JSONObject().put("pub", it.publicKey).put("priv", it.privateKey).put("at", it.createdAt)
        .apply { if (it.generation > 0) put("g", it.generation) }) }
    o.put("e", JSONArray().apply {
      for (e in v.entries.values.sortedBy { it.id }) {
        put(JSONObject().put("id", e.id).put("r", e.rev).apply { e.link?.let { put("l", HomeLinkBookCodec.linkJson(it)) } })
      }
    })
    if (v.removals.isNotEmpty()) o.put("rm", JSONArray().apply { v.removals.values.sortedBy { it.kid }.forEach { put(PortalRemovals.json(it)) } })
    return o.toString()
  }

  fun parse(text: String?): HomeLinkVault? {
    if (text.isNullOrEmpty()) return null
    return try {
      val o = JSONObject(text)
      if (o.opt("v") != VERSION) return null
      val identity = (o.opt("id") as? JSONObject)?.let { identity(it) ?: return null }
      val arr = o.getJSONArray("e")
      if (arr.length() > MAX_ENTRIES) return null
      val entries = (0 until arr.length()).map { entry(arr.getJSONObject(it)) ?: return null }
      if (entries.map { it.id }.toSet().size != entries.size) return null
      if (entries.count { !it.removed } > HomeLinkBook.MAX_LINKS) return null
      val rm = o.optJSONArray("rm")
      if (rm != null && rm.length() > PortalRemovals.MAX) return null
      val removals = (0 until (rm?.length() ?: 0)).map { PortalRemovals.parse(rm!!.optJSONObject(it)) ?: return null }
      if (removals.map { it.kid }.toSet().size != removals.size) return null
      HomeLinkVault(identity, entries.associateBy { it.id }, removals.associateBy { it.kid })
    } catch (e: JSONException) { null }
  }

  private fun identity(o: JSONObject): HomeLinkStoredIdentity? {
    val pub = o.opt("pub") as? String ?: return null
    val priv = o.opt("priv") as? String ?: return null
    val at = (o.opt("at") as? Number)?.toLong() ?: return null
    val generation = if (o.has("g")) (o.opt("g") as? Number)?.toInt() ?: return null else 0
    return HomeLinkStoredIdentity.parse(pub, priv, at, generation)
  }

  private fun entry(o: JSONObject): HomeLinkEntry? {
    val id = (o.opt("id") as? String)?.takeIf { ID.matches(it) } ?: return null
    val rev = (o.opt("r") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    val linkJson = o.opt("l") ?: return HomeLinkEntry(id, rev, null)
    val link = (linkJson as? JSONObject)?.let(HomeLinkBookCodec::parseLink)?.takeIf { it.id == id } ?: return null
    return HomeLinkEntry(id, rev, link)
  }

  private val ID = Regex("^[0-9a-f]{16}$")
}
