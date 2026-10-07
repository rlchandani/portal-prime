/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved RTSP camera streams for the camera viewer (kitchen Portal showing the
 * driveway/door cam). Stored as a JSON array in SharedPrefs. URLs are plain
 * `rtsp://user:pass@host:554/path` strings, played by Android's built-in MediaPlayer
 * (which supports RTSP natively — no extra library or GMS needed).
 */
object CameraConfig {

  private const val PREFS = "immortal_cameras"
  private const val KEY = "cameras"

  data class Camera(val id: Long, val name: String, val url: String)

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Blocking storage API; Tools dispatches reads and mutations to its own IO worker. */
  @Synchronized
  fun load(context: Context): List<Camera> {
    val raw = prefs(context).getString(KEY, null) ?: return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Camera(o.optLong("id", System.nanoTime()), o.optString("name", "Camera"), o.optString("url", ""))
      }.filter { it.url.isNotBlank() }
    }.getOrDefault(emptyList())
  }

  private fun save(context: Context, cams: List<Camera>) {
    val arr = JSONArray()
    cams.forEach { c -> arr.put(JSONObject().put("id", c.id).put("name", c.name).put("url", c.url)) }
    prefs(context).edit().putString(KEY, arr.toString()).apply()
  }

  fun add(context: Context, name: String, url: String) {
    addAndLoad(context, name, url)
  }

  fun remove(context: Context, id: Long) {
    removeAndLoad(context, id)
  }

  /** One locked read/modify/write, returning the snapshot without parsing the JSON again. */
  @Synchronized
  internal fun addAndLoad(context: Context, name: String, url: String): List<Camera> {
    val cameras = load(context)
    // Serialized rapid adds can still share a millisecond; lazy rows need distinct IDs.
    val ids = cameras.mapTo(HashSet()) { it.id }
    var id = System.currentTimeMillis()
    while (id in ids) id = if (id == Long.MAX_VALUE) Long.MIN_VALUE else id + 1
    val updated = cameras + Camera(id, name.ifBlank { "Camera" }, url.trim())
    save(context, updated)
    return updated
  }

  @Synchronized
  internal fun removeAndLoad(context: Context, id: Long): List<Camera> {
    val updated = load(context).filterNot { it.id == id }
    save(context, updated)
    return updated
  }
}
