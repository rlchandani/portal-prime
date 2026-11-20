/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.InputStream

/**
 * Decode-size decisions: which decoder a mode uses, how big, the oriented size the engine's mode
 * scheduler buckets by, and the encoded identity that survives resizing. The decoding itself is
 * [PhotoFrameDecoder]'s.
 */
internal class PhotoFrameDecodePlan<B : Any>(private val decoder: PhotoFrameDecoder<B>) {
  /**
   * A photo bounded to [maxEdge] on its long side. Classic decodes the file with its own bounds
   * pass and EXIF rotation; the native stage reads the encoded bytes once (see [bytes]).
   */
  fun file(path: String, maxEdge: Int, nativeStage: Boolean, identify: (B, String) -> Unit): B? {
    if (!nativeStage) return decoder.file(path, maxEdge)
    return runCatching { java.io.File(path).inputStream().use { stream(it, maxEdge, true, identify) } }.getOrNull()
  }

  /** Stream variant: buffers to bytes so the bounds pass can run before the real decode. */
  fun stream(input: InputStream, maxEdge: Int, nativeStage: Boolean, identify: (B, String) -> Unit): B? {
    if (!nativeStage) return decoder.stream(input, maxEdge)
    val bytes = decoder.readEncoded(input) ?: return null
    return bytes(bytes, maxEdge, true, identify)
  }

  /** Two-pass decode of encoded bytes; the native stage records the encoded identity of the result. */
  fun bytes(bytes: ByteArray, maxEdge: Int, nativeStage: Boolean, identify: (B, String) -> Unit): B? {
    val bitmap = decoder.bytes(bytes, maxEdge) ?: return null
    // Encoded identity survives sampling, presentation resizing, and changes of serving URL.
    if (nativeStage) identify(bitmap, encodedIdentity(bytes))
    return bitmap
  }

  /** Oriented width x height of a local image without decoding pixels, or null if unreadable. */
  fun orientedSize(path: String): Pair<Int, Int>? {
    val (w, h) = decoder.bounds(path) ?: return null
    return if (swapsAxes(decoder.orientation(path))) h to w else w to h
  }

  companion object {
    /** Default cap on a decoded photo's long edge (the largest panel plus Ken Burns overscan). */
    const val DEFAULT_EDGE = 2400
    /** The engine's generated test scenes are written at this long edge. */
    const val TEST_SCENE_EDGE = 1600
    /** Scene files for the engine: every generated scene twice, so modes have pairs to compose. */
    fun testSceneCount(): Int = ScreensaverTestSource.scenes.size * 2

    /** EXIF quarter turns (and the mirrored ones) swap width and height. */
    fun swapsAxes(orientation: Int): Boolean =
        orientation == ORIENTATION_ROTATE_90 || orientation == ORIENTATION_ROTATE_270 ||
            orientation == ORIENTATION_TRANSPOSE || orientation == ORIENTATION_TRANSVERSE

    /** SHA-256 of the encoded bytes, the photo's identity across sizes and URLs. */
    fun encodedIdentity(bytes: ByteArray): String =
        "encoded:" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    // ExifInterface's orientation values (androidx.exifinterface), kept here so this stays pure.
    private const val ORIENTATION_ROTATE_90 = 6
    private const val ORIENTATION_ROTATE_270 = 8
    private const val ORIENTATION_TRANSPOSE = 5
    private const val ORIENTATION_TRANSVERSE = 7
  }
}

/** The built-in feed's URLs: what the engine and the classic web feed ask the network for. */
internal object PhotoFrameFeedUrls {
  /** Seeds with deliberately varied aspects, so every engine mode has portraits, squares and panoramas. */
  private val SPECS = listOf(
      "ridge" to (1600 to 1067), "harbor" to (1600 to 1067), "desert" to (1600 to 1067),
      "forest7" to (1600 to 1067), "coast22" to (1600 to 1000), "city88" to (1600 to 1067),
      "portraitA" to (1000 to 1500), "portraitB" to (1000 to 1500), "portraitC" to (1000 to 1400),
      "portraitD" to (1000 to 1500), "waterfall" to (1000 to 1600), "tower55" to (1000 to 1500),
      "square1" to (1200 to 1200), "square2" to (1200 to 1200), "square3" to (1200 to 1200),
      "pano1" to (2400 to 900), "pano2" to (2400 to 800),
  )

  /** Keyless Picsum URLs for the engine, their long edge capped to the panel (1080..2200). */
  fun engineBatch(screenW: Int, screenH: Int): List<String> {
    val cap = maxOf(screenW, screenH).coerceIn(1080, 2200)
    return SPECS.map { (seed, wh) ->
      val (w, h) = wh
      val scale = cap.toDouble() / maxOf(w, h)
      "https://picsum.photos/seed/$seed/${(w * scale).toInt()}/${(h * scale).toInt()}"
    }
  }

  /** One random screen-sized Picsum photo. */
  fun picsum(screenW: Int, screenH: Int, nowMs: Long): String = "https://picsum.photos/$screenW/$screenH?random=$nowMs"

  /** Unsplash crop matching the panel, so a portrait Mini isn't sent a letterboxed landscape. */
  fun unsplashOrientation(screenW: Int, screenH: Int): String = if (screenH > screenW) "portrait" else "landscape"

  fun unsplash(orientation: String, query: String, key: String): String =
      "https://api.unsplash.com/photos/random?orientation=$orientation&query=$query&client_id=$key"

  /** Wikimedia Commons "Featured pictures of landscapes", thumbnails at the panel width (640..1920). */
  fun wikimedia(screenW: Int): String =
      "https://commons.wikimedia.org/w/api.php?action=query&format=json" +
          "&generator=categorymembers&gcmtype=file&gcmlimit=200" +
          "&gcmtitle=Category:Featured_pictures_of_landscapes" +
          "&prop=imageinfo&iiprop=url&iiurlwidth=${screenW.coerceIn(640, 1920)}"
}
