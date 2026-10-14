/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** Resolve omitted credentials before any settings mutation; never move a secret to a new peer. */
internal object RemoteCompanionSources {
  data class Prepared(val body: JSONObject? = null, val error: String? = null)
  private fun endpoint(value: String, immich: Boolean = false): String? = runCatching {
    val normalized = if (immich) ImmichSource.normalizeBase(value) else value.trim().trimEnd('/')
    val uri = URI(normalized)
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) return null
    val scheme = uri.scheme.lowercase(Locale.ROOT)
    val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
    "$scheme://${uri.host.lowercase(Locale.ROOT)}:$port${uri.rawPath.orEmpty().trimEnd('/')}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
  }.getOrNull()

  fun prepare(input: JSONObject, stored: ScreensaverConfig.Settings): Prepared {
    val out = JSONObject(input.toString())
    if (out.has("albumUrl")) {
      val album = out.opt("albumUrl")
      if (album !is String) return Prepared(error = "invalid_source_field")
      if (!RemoteAlbum.isSupported(album)) return Prepared(error = "invalid_album_link")
    }
    if (out.has("webUrl")) {
      val value = out.opt("webUrl")
      if (value !is String) return Prepared(error = "invalid_source_field")
      val url = WebPageUrl.normalize(value) ?: return Prepared(error = "invalid_web_url")
      out.put("webUrl", url)
    }
    val credentialFields = setOf("immichUrl", "immichKey", "immichAlbumId", "immichAlbumName", "smbHost", "smbShare", "smbPath", "smbUser", "smbPass", "davUrl", "davUser", "davPass")
    if (credentialFields.any { out.has(it) && out.opt(it) !is String }) return Prepared(error="invalid_source_field")
    if (out.fieldKeys().any { it in setOf("immichUrl", "immichKey", "immichAlbumId", "immichAlbumName") }) {
      val url = if (out.has("immichUrl")) out.getString("immichUrl") else stored.immichUrl.orEmpty()
      val dest = endpoint(url, true) ?: return Prepared(error="invalid_immich_endpoint")
      val same = dest == endpoint(stored.immichUrl.orEmpty(), true)
      val key = if (out.has("immichKey")) out.getString("immichKey") else if (same) stored.immichKey.orEmpty() else ""
      if (key.isBlank()) return Prepared(error="immichKey_required")
      out.put("immichUrl", url).put("immichKey", key)
    }
    if (out.fieldKeys().any { it in setOf("smbHost", "smbShare", "smbPath", "smbUser", "smbPass") }) {
      val host = if (out.has("smbHost")) out.getString("smbHost").trim() else stored.smbHost.orEmpty()
      val share = if (out.has("smbShare")) out.getString("smbShare").trim() else stored.smbShare.orEmpty()
      if (host.isBlank() || share.isBlank()) return Prepared(error="smb_endpoint_required")
      val same = host.equals(stored.smbHost, true) && share == stored.smbShare
      val user = if (out.has("smbUser")) out.getString("smbUser").trim() else if (same) stored.smbUser.orEmpty() else ""
      val sameAccount = same && user == stored.smbUser.orEmpty()
      val password = if (out.has("smbPass")) out.getString("smbPass") else if (sameAccount) stored.smbPass.orEmpty() else ""
      if (!sameAccount && user.isNotBlank() && password.isBlank()) return Prepared(error="smbPass_required")
      out.put("smbHost", host).put("smbShare", share).put("smbUser", user).put("smbPass", password)
      if (!out.has("smbPath") && same) out.put("smbPath", stored.smbPath.orEmpty())
    }
    if (out.fieldKeys().any { it in setOf("davUrl", "davUser", "davPass") }) {
      val url = if (out.has("davUrl")) out.getString("davUrl") else stored.davUrl.orEmpty()
      val dest = endpoint(url) ?: return Prepared(error="invalid_dav_endpoint")
      val same = dest == endpoint(stored.davUrl.orEmpty())
      val user = if (out.has("davUser")) out.getString("davUser").trim() else if (same) stored.davUser.orEmpty() else ""
      val sameAccount = same && user == stored.davUser.orEmpty()
      val password = if (out.has("davPass")) out.getString("davPass") else if (sameAccount) stored.davPass.orEmpty() else ""
      if (!sameAccount && user.isNotBlank() && password.isBlank()) return Prepared(error="davPass_required")
      out.put("davUrl", url).put("davUser", user).put("davPass", password)
    }
    return Prepared(body=out)
  }
}
