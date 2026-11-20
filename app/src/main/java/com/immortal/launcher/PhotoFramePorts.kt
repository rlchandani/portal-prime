/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.InputStream

/*
 * The screensaver's edges, as the pure photo-frame classes see them. [PhotoFrameController] is the
 * only Android caller; [PhotoFrameAndroidPorts] implements each with one platform call, and tests
 * use fakes.
 */

/** Wall time (calendar, cooldowns, touches) and uptime (dwell deadlines; frozen with the host). */
internal interface PhotoFrameClock {
  fun wallMs(): Long
  fun uptimeMs(): Long
}

/** What the household and the operator have configured for the screensaver. */
internal interface PhotoFramePrefs {
  /** Saved screensaver settings, with guest mode's neutral source and calendar applied. */
  fun household(): ScreensaverConfig.Settings
  /** Saved screensaver settings as stored (night controls re-read after a change broadcast). */
  fun saved(): ScreensaverConfig.Settings
  /** The verification-only generated source is on (debug and performance builds). */
  fun testSourceActive(): Boolean
  /** The operator's mode pin while the test source is on. */
  fun testSourcePin(): ScreensaverTestSource.Pin?
  /** The operator forced the dark room on with the test source. */
  fun testSourceForcedNight(): Boolean
}

/** Image decoding into [B] (a Bitmap on a Portal). Edges cap the long side of the decoded image. */
internal interface PhotoFrameDecoder<B : Any> {
  /** Header-only width x height, or null when unreadable. */
  fun bounds(path: String): Pair<Int, Int>?
  /** The EXIF orientation tag (ExifInterface constants), normal when missing. */
  fun orientation(path: String): Int
  /** Classic renderer: decode with its own bounds pass and EXIF rotation. */
  fun file(path: String, maxEdge: Int): B?
  fun stream(input: InputStream, maxEdge: Int): B?
  /** The encoded bytes of [input], or null when unreadable or over the reader's cap. */
  fun readEncoded(input: InputStream): ByteArray?
  /** Native stage: a bounded decode of encoded bytes. */
  fun bytes(bytes: ByteArray, maxEdge: Int): B?
}

/** The WebView engine's page: one script call per command (see [PhotoFrameEngineScript]). */
internal fun interface PhotoFrameWebBridge {
  fun run(script: String)
}

/** Who is in front of the frame, and what that hides. */
internal interface PhotoFramePresence {
  /** Someone is present right now (the engine leans toward photos of people). */
  fun watching(): Boolean
  /** Guest mode hides the household's own photos. */
  fun hidesPhotos(): Boolean
  /** A finger went down on the frame: the dream must not be recovered behind it. */
  fun touched(wallMs: Long)
}
