/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small key → text store for cached API responses and the search budget. Holds no credentials.
 * Best effort: read failures look like misses, write failures are dropped (the cache is optional).
 */
interface YtCacheStore {
  fun read(key: String): String?
  fun write(key: String, text: String)
  fun delete(key: String)
  /** Deletes every key starting with [prefix] (e.g. one account's library on sign-out). */
  fun deletePrefix(prefix: String)
}

/**
 * One file per key in [dir]. File names are the key restricted to `[A-Za-z0-9._-]` plus a short
 * hash of the full key, so arbitrary ids can't traverse paths and distinct keys never collide;
 * the sanitized prefix is kept so [deletePrefix] can match by name.
 */
class YtFileCacheStore(private val dir: File) : YtCacheStore {
  override fun read(key: String): String? =
      try {
        fileFor(key).takeIf { it.isFile }?.readText()
      } catch (e: IOException) {
        null
      }

  override fun write(key: String, text: String) {
    try {
      if (!dir.isDirectory && !dir.mkdirs()) return
      val target = fileFor(key)
      val temp = File(dir, target.name + ".tmp")
      temp.writeText(text)
      if (!temp.renameTo(target)) temp.delete()
    } catch (e: IOException) {
      // Cache is optional; a failed write is a future miss.
    }
  }

  override fun delete(key: String) {
    fileFor(key).delete()
  }

  override fun deletePrefix(prefix: String) {
    val namePrefix = sanitize(prefix)
    dir.listFiles()?.filter { it.name.startsWith(namePrefix) }?.forEach { it.delete() }
  }

  private fun fileFor(key: String) = File(dir, sanitize(key).take(MAX_NAME) + "~" + hash(key))

  private fun sanitize(text: String) =
      text.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-") it else '_' }.joinToString("")

  private fun hash(key: String): String =
      MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

  private companion object {
    const val MAX_NAME = 100
  }
}

/** JSON codecs for the cached model lists. Decoding never throws; bad data → null. */
internal object YtModelCodec {
  fun encodeTracks(tracks: List<YtTrack>): String = array(tracks) {
    JSONObject().put("v", it.videoId).put("t", it.title).put("a", it.artist).putOpt("i", it.thumbnailUrl)
        .putOpt("d", it.durationMs)
  }

  fun decodeTracks(text: String): List<YtTrack>? = objects(text) {
    val id = it.optString("v").ifEmpty { return@objects null }
    YtTrack(id, it.optString("t"), it.optString("a"), opt(it, "i"), if (it.has("d")) it.optLong("d") else null)
  }

  fun encodePlaylists(list: List<YtPlaylist>): String = array(list) {
    JSONObject().put("id", it.id).put("t", it.title).putOpt("i", it.thumbnailUrl).putOpt("n", it.itemCount)
  }

  fun decodePlaylists(text: String): List<YtPlaylist>? = objects(text) {
    val id = it.optString("id").ifEmpty { return@objects null }
    YtPlaylist(id, it.optString("t"), opt(it, "i"), if (it.has("n")) it.optInt("n") else null)
  }

  fun encodeArtists(list: List<YtArtist>): String = array(list) {
    JSONObject().put("id", it.channelId).put("t", it.name).putOpt("i", it.thumbnailUrl)
  }

  fun decodeArtists(text: String): List<YtArtist>? = objects(text) {
    val id = it.optString("id").ifEmpty { return@objects null }
    YtArtist(id, it.optString("t"), opt(it, "i"))
  }

  private fun opt(json: JSONObject, key: String): String? = if (json.has(key)) json.optString(key) else null

  private inline fun <T> array(items: List<T>, encode: (T) -> JSONObject): String =
      JSONArray().also { array -> items.forEach { array.put(encode(it)) } }.toString()

  private inline fun <T> objects(text: String, decode: (JSONObject) -> T?): List<T>? {
    val array = try {
      JSONArray(text)
    } catch (e: org.json.JSONException) {
      return null
    }
    return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(decode) }
  }
}
