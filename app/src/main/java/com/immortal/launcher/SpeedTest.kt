/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * A keyless network speed test against Cloudflare's open speed endpoints (`speed.cloudflare.com`):
 * serving data-centre, latency + jitter, download and upload. Each leg is wrapped independently, so
 * one failing leaves that metric blank but lets the others complete. Results are published live via
 * the [Result] snapshot-state holder so a UI can show progress; the whole thing runs on IO.
 *
 * Formerly lived inline in ForkHome; extracted here so it has a real home (and is unit-testable).
 */
object SpeedTest {

  enum class Phase {
    PING,
    DOWNLOAD,
    UPLOAD,
    DONE,
  }

  /** Live, observable results. Fields update as each leg progresses. */
  class Result {
    var server by mutableStateOf("")
    var pingMs by mutableStateOf(0.0)
    var jitterMs by mutableStateOf(0.0)
    var downMbps by mutableStateOf(0.0)
    var upMbps by mutableStateOf(0.0)

    fun reset() {
      server = ""
      pingMs = 0.0
      jitterMs = 0.0
      downMbps = 0.0
      upMbps = 0.0
    }
  }

  private const val BUDGET_MS = 10_000L

  /** Runs the full test, emitting phase changes via [onPhase] and live values into [result]. */
  suspend fun run(result: Result, onPhase: (Phase) -> Unit) {
    withContext(Dispatchers.IO) {
      // ---- serving data-centre (cdn-cgi/trace: colo=IATA, loc=country) ----
      runCatching {
        val conn = URL("https://speed.cloudflare.com/cdn-cgi/trace").openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 6_000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val kv =
            text.lineSequence()
                .mapNotNull {
                  val i = it.indexOf('=')
                  if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
                }
                .toMap()
        result.server =
            listOfNotNull("Cloudflare", kv["colo"], kv["loc"]).filter { it.isNotBlank() }.joinToString(" · ")
      }

      // ---- ping: TTFB of a 0-byte download, keep-alive reused; drop the warm-up ----
      onPhase(Phase.PING)
      runCatching {
        val samples = ArrayList<Double>()
        repeat(6) { i ->
          val t0 = System.nanoTime()
          val conn = URL("https://speed.cloudflare.com/__down?bytes=0").openConnection() as HttpURLConnection
          conn.connectTimeout = 5_000
          conn.readTimeout = 5_000
          conn.inputStream.use { it.read() }
          val ms = (System.nanoTime() - t0) / 1_000_000.0
          if (i > 0) samples.add(ms) // first request pays TLS setup
        }
        if (samples.isNotEmpty()) {
          val avg = samples.average()
          result.pingMs = avg
          result.jitterMs = samples.map { kotlin.math.abs(it - avg) }.average()
        }
      }

      // ---- download: Cloudflare caps __down at <100 MB (100 MB → 403), so pull 50 MB
      //      segments back-to-back until the 10s budget is spent. Keeps the pipe full on
      //      fast links where a single segment finishes in well under 1s. ----
      onPhase(Phase.DOWNLOAD)
      runCatching {
        val t0 = System.currentTimeMillis()
        var bytes = 0L
        var lastEmit = 0L
        val buf = ByteArray(65_536)
        while (System.currentTimeMillis() - t0 < BUDGET_MS) {
          val conn =
              URL("https://speed.cloudflare.com/__down?bytes=52428800").openConnection() as HttpURLConnection
          conn.connectTimeout = 8_000
          conn.readTimeout = 20_000
          try {
            conn.inputStream.use { s ->
              while (true) {
                val n = s.read(buf)
                if (n == -1) break
                bytes += n
                val now = System.currentTimeMillis()
                val sec = (now - t0) / 1000.0
                if (now - lastEmit > 200 && sec > 0) {
                  result.downMbps = bytes * 8.0 / 1_000_000.0 / sec
                  lastEmit = now
                }
                if (now - t0 > BUDGET_MS) break
              }
            }
          } finally {
            conn.disconnect()
          }
        }
        val sec = (System.currentTimeMillis() - t0) / 1000.0
        if (sec > 0) result.downMbps = bytes * 8.0 / 1_000_000.0 / sec
      }

      // ---- upload: stream random bytes for up to 10s; measured during the write so a
      //      truncated-response close doesn't lose the reading ----
      onPhase(Phase.UPLOAD)
      runCatching {
        val conn = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        conn.setChunkedStreamingMode(0)
        val chunk = ByteArray(65_536).also { java.util.Random().nextBytes(it) }
        val t0 = System.currentTimeMillis()
        var sent = 0L
        var lastEmit = 0L
        runCatching {
          conn.outputStream.use { o ->
            while (System.currentTimeMillis() - t0 < BUDGET_MS) {
              o.write(chunk)
              sent += chunk.size
              val now = System.currentTimeMillis()
              val sec = (now - t0) / 1000.0
              if (now - lastEmit > 200 && sec > 0) {
                result.upMbps = sent * 8.0 / 1_000_000.0 / sec
                lastEmit = now
              }
            }
            o.flush()
          }
          runCatching { conn.responseCode }
        }
        conn.disconnect()
        val sec = (System.currentTimeMillis() - t0) / 1000.0
        if (sec > 0 && sent > 0) result.upMbps = sent * 8.0 / 1_000_000.0 / sec
      }
    }
  }
}
