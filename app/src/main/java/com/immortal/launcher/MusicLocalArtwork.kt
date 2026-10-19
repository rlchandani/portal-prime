/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.InputStream
import java.net.URI
import java.util.Locale

internal enum class MusicArtworkRoute { LOCAL, NETWORK, UNSUPPORTED }

/** Local cover reads share the network byte/decode limits, without a second artwork cache. */
internal object MusicLocalArtwork {
  data class DecodePlan(val maxEdge: Int, val sampleSize: Int)

  /** Unknown/malformed URIs never fall through to a provider or network request. */
  fun routeFor(uri: String): MusicArtworkRoute {
    val scheme = try {
      URI(uri).scheme?.lowercase(Locale.ROOT)
    } catch (_: Exception) {
      null
    }
    return when (scheme) {
      "content", "file", "android.resource" -> MusicArtworkRoute.LOCAL
      "http", "https" -> MusicArtworkRoute.NETWORK
      else -> MusicArtworkRoute.UNSUPPORTED
    }
  }

  /** Production's provider-open seam; close on success, excess bytes and provider failure. */
  fun readLocal(uri: String, open: (String) -> InputStream?): ByteArray? {
    if (routeFor(uri) != MusicArtworkRoute.LOCAL) return null
    return try {
      open(uri)?.use { MusicArtworkPolicy.readBounded(it, MusicArtworkPolicy.MAX_BODY_BYTES) }
    } catch (_: Exception) {
      // Missing grants/files and failed providers leave the regular artwork placeholder.
      null
    }
  }

  /** Bound decode dimensions before allocating pixels, even if a caller requests more. */
  fun decodePlan(width: Int, height: Int, maxEdge: Int): DecodePlan? {
    val edge = minOf(maxEdge, 1024)
    val sample = MusicArtworkPolicy.sampleSizeFor(width, height, edge) ?: return null
    return DecodePlan(edge, sample)
  }

  /** Blocking: invoke off-main. All device-local schemes go through ContentResolver. */
  fun fetch(resolver: ContentResolver, uri: String, maxEdge: Int = 384): Bitmap? {
    if (maxEdge <= 0) return null
    val raw = readLocal(uri) { resolver.openInputStream(Uri.parse(it).normalizeScheme()) } ?: return null
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
      val plan = decodePlan(bounds.outWidth, bounds.outHeight, maxEdge) ?: return null
      val options = BitmapFactory.Options().apply {
        inSampleSize = plan.sampleSize
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
      val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
      if (bitmap.width !in 1..plan.maxEdge || bitmap.height !in 1..plan.maxEdge ||
          bitmap.allocationByteCount.toLong() > plan.maxEdge.toLong() * plan.maxEdge * 4) {
        // This is an owned decode, never the borrowed media-session bitmap.
        bitmap.recycle()
        null
      } else bitmap
    } catch (_: Exception) {
      null
    }
  }
}
