/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * A photo source backed by a self-hosted [Immich](https://immich.app) server — the most
 * requested screensaver source. Lists image *preview* URLs (and, when the "Play videos" setting
 * is on, video *playback* URLs) for a chosen album (or the whole library); the screensaver then
 * fetches each through the shared remote path, sending the same `x-api-key` header (see
 * [authHeaders]).
 *
 * Verified against Immich **v2.5.2** and **v3.0.1**:
 *  - auth: header `x-api-key: <key>` (no key → 401)
 *  - albums (picker): `GET  /api/albums`          → `[{id, albumName, assetCount}]`
 *  - assets:        `POST /api/search/metadata`   → `{assets: {items: [{id, type}], nextPage}}`,
 *    filtered by `albumIds` for a single album or unfiltered for the whole library. (Immich 3.0
 *    dropped the `assets` array that `GET /api/albums/{id}` used to embed, so both paths now go
 *    through search — see #135.)
 *  - image bytes:   `GET  /api/assets/{id}/thumbnail?size=preview` → JPEG sized to the screen
 *  - video stream:  `GET  /api/assets/{id}/video/playback` → the (server-transcoded) clip
 *
 * Everything is best-effort: any failure returns null and the caller falls back to the default
 * feed, so the frame is never blank — but every failure is logged under [TAG], because a silent
 * fallback is undiagnosable from a user report (issue #142).
 */
object ImmichSource {
  private const val TAG = "ImmortalImmich"

  /** A library album, for the connection picker. */
  data class Album(val id: String, val name: String, val count: Int)

  /** One playable asset: a preview-image URL, or a video-playback URL when [isVideo]. */
  data class Media(val url: String, val isVideo: Boolean)

  /** Header(s) every Immich request (including image downloads) must carry. */
  fun authHeaders(apiKey: String): Map<String, String> = mapOf("x-api-key" to apiKey.trim())

  /** Normalise a user-entered base URL to `scheme://host:port` with no trailing slash or /api. */
  fun normalizeBase(raw: String): String {
    var s = raw.trim().trimEnd('/')
    if (s.endsWith("/api", ignoreCase = true)) s = s.dropLast(4)
    return s
  }

  /** The preview-image URL for an asset (sized to the screen; ~150KB JPEG). */
  fun previewUrl(base: String, assetId: String): String =
      "${normalizeBase(base)}/api/assets/$assetId/thumbnail?size=preview"

  /** The streaming-playback URL for a video asset (the server transcodes as needed). */
  fun playbackUrl(base: String, assetId: String): String =
      "${normalizeBase(base)}/api/assets/$assetId/video/playback"

  /** Confirm the server is reachable and the key is valid (`GET /api/users/me` → 200). */
  fun testConnection(base: String, apiKey: String): Boolean =
      runCatching {
            httpGet("${normalizeBase(base)}/api/users/me", authHeaders(apiKey)) != null
          }
          .getOrDefault(false)

  /** List the user's albums (for the picker). Null on any failure. */
  fun listAlbums(base: String, apiKey: String): List<Album>? =
      runCatching {
            val body = httpGet("${normalizeBase(base)}/api/albums", authHeaders(apiKey)) ?: return null
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
              val o = arr.getJSONObject(i)
              Album(o.getString("id"), o.optString("albumName", "Album"), o.optInt("assetCount", 0))
            }
          }
          .onFailure { Log.w(TAG, "album list failed", it) }
          .getOrNull()

  /**
   * Playable media for [albumId] (or the whole library when null/blank), capped at [cap] items:
   * preview URLs for images, playback URLs for videos when [includeVideo] is on (the "Play
   * videos" setting — videos stream through the same auth'd remote path). Null on failure.
   */
  fun listMedia(
      base: String,
      apiKey: String,
      albumId: String?,
      includeVideo: Boolean = false,
      cap: Int = 1000,
  ): List<Media>? =
      runCatching {
            val b = normalizeBase(base)
            val headers = authHeaders(apiKey)
            val assets = searchAssets(b, headers, cap, albumId?.takeIf { it.isNotBlank() }, includeVideo)
            if (assets.isEmpty()) {
              Log.w(TAG, "search returned no assets (album=${albumId ?: "library"})")
            }
            assets.map { (id, isVideo) ->
              Media(if (isVideo) playbackUrl(b, id) else previewUrl(b, id), isVideo)
            }
          }
          .onFailure { Log.w(TAG, "media list failed (album=${albumId ?: "library"})", it) }
          .getOrNull()

  /**
   * Asset (id, isVideo) pairs via the paged `POST /api/search/metadata` endpoint. When [albumId]
   * is given the search is filtered to that album (`albumIds`), otherwise it covers the whole
   * library. This one path replaces the old album `GET /api/albums/{id}` embed, which stopped
   * returning an `assets` array in Immich 3.0 (#135). Images only searches keep the server-side
   * `type: IMAGE` filter; with [includeVideo] the type filter is dropped and IMAGE/VIDEO are
   * accepted client-side, preserving the library's natural ordering.
   */
  private fun searchAssets(
      base: String,
      headers: Map<String, String>,
      cap: Int,
      albumId: String?,
      includeVideo: Boolean,
  ): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    var page = 1
    while (out.size < cap) {
      val req = JSONObject().put("size", 1000).put("page", page)
      if (!includeVideo) req.put("type", "IMAGE")
      if (albumId != null) req.put("albumIds", JSONArray().put(albumId))
      val body = httpPostJson("$base/api/search/metadata", headers, req.toString()) ?: break
      val assets = JSONObject(body).optJSONObject("assets") ?: break
      val items = assets.optJSONArray("items") ?: break
      if (items.length() == 0) break
      out.addAll(assetsFrom(items, cap - out.size, includeVideo))
      val next = assets.opt("nextPage")
      if (next == null || next == JSONObject.NULL) break
      page = (next as? String)?.toIntOrNull() ?: (next as? Int) ?: break
    }
    return out
  }

  private fun assetsFrom(
      assets: JSONArray,
      limit: Int,
      includeVideo: Boolean,
  ): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    var i = 0
    while (i < assets.length() && out.size < limit) {
      val o = assets.getJSONObject(i)
      when (o.optString("type")) {
        "IMAGE" -> out.add(o.getString("id") to false)
        "VIDEO" -> if (includeVideo) out.add(o.getString("id") to true)
      }
      i++
    }
    return out
  }

  // --- HTTP (mirrors the app's other best-effort fetchers) --------------------
  private fun httpGet(spec: String, headers: Map<String, String>): String? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 10000
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    return if (c.responseCode in 200..299) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    else null.also { logHttpFailure("GET", spec, c) }
  }

  private fun httpPostJson(spec: String, headers: Map<String, String>, json: String): String? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 12000
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json")
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    return if (c.responseCode in 200..299) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    else null.also { logHttpFailure("POST", spec, c) }
  }

  /** A rejected request is the one clue a user report can carry — keep the server's own words. */
  private fun logHttpFailure(method: String, spec: String, c: HttpURLConnection) {
    val detail =
        runCatching { c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8).take(300) } }
            .getOrNull()
    Log.w(TAG, "$method $spec -> HTTP ${c.responseCode}${detail?.let { ": $it" } ?: ""}")
  }
}
