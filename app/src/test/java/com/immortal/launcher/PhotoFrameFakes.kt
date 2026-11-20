/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.InputStream

/** A settable clock: wall time and uptime move independently, as on a Portal. */
internal class FakePhotoFrameClock(var wall: Long = 1_700_000_000_000L, var uptime: Long = 50_000L) : PhotoFrameClock {
  override fun wallMs() = wall
  override fun uptimeMs() = uptime
}

/** Household settings and the operator's test source, in memory; counts what was read. */
internal class FakePhotoFramePrefs(
    var settings: ScreensaverConfig.Settings = ScreensaverConfig.Settings(),
    var testSource: Boolean = false,
    var pin: ScreensaverTestSource.Pin? = null,
    var forcedNight: Boolean = false,
) : PhotoFramePrefs {
  val reads = ArrayList<String>()
  override fun household() = settings.also { reads += "household" }
  override fun saved() = settings.also { reads += "saved" }
  override fun testSourceActive() = testSource.also { reads += "testSource" }
  override fun testSourcePin() = pin.also { reads += "pin" }
  override fun testSourceForcedNight() = forcedNight.also { reads += "forcedNight" }
}

/** Decodes into strings that say how they were made; headers and EXIF come from maps. */
internal class FakePhotoFrameDecoder(
    val sizes: Map<String, Pair<Int, Int>> = emptyMap(),
    val orientations: Map<String, Int> = emptyMap(),
) : PhotoFrameDecoder<String> {
  val calls = ArrayList<String>()
  var bytesResult: (ByteArray, Int) -> String? = { b, edge -> "bytes:${b.size}@$edge" }
  override fun bounds(path: String) = sizes[path].also { calls += "bounds $path" }
  override fun orientation(path: String) = (orientations[path] ?: 1).also { calls += "orientation $path" }
  override fun file(path: String, maxEdge: Int) = "file:$path@$maxEdge".also { calls += "file $path $maxEdge" }
  override fun stream(input: InputStream, maxEdge: Int) = "stream@$maxEdge".also { calls += "stream $maxEdge" }
  override fun readEncoded(input: InputStream): ByteArray? = input.readBytes().also { calls += "read ${it.size}" }
  override fun bytes(bytes: ByteArray, maxEdge: Int) = bytesResult(bytes, maxEdge).also { calls += "bytes ${bytes.size} $maxEdge" }
}
