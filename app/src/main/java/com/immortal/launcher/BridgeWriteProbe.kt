/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Fixed diagnostic prefs only; no app launch, bridge state change or microphone activation. */
internal object BridgeWriteProbe {
    private const val PREFS = "immortal_generated_bridge_write_probe"
    private val running = AtomicBoolean(false)
    private var pendingDone: CountDownLatch? = null
    private fun response(status: Int, value: JSONObject) = FleetHttpServer.Response(status, value.toString())
    private fun error(status: Int, code: String) = response(status, JSONObject().put("ok", false).put("error", code))
    private fun cleanup(context: Context): Boolean = context.deleteSharedPreferences(PREFS)

    fun handle(context: Context, request: FleetHttpServer.Request): FleetHttpServer.Response {
        if (!BuildConfig.PORTAL_SETTINGS_UI_DIAGNOSTICS) return error(403, "bridge_write_probe_disabled")
        if (!DevMode.isEnabled(context)) return error(403, "dev_mode_required")
        if (request.method != "GET") return error(405, "method_not_allowed")
        if (request.query.isNotEmpty()) return error(400, "query_not_supported")
        if (Looper.myLooper() == Looper.getMainLooper()) return error(503, "worker_required")
        if (HomeUiDiagnostics.collectorBusy()) return error(409, "bridge_write_perf_busy")
        synchronized(running) {
            if (pendingDone?.count == 0L && cleanup(context)) { pendingDone = null; running.set(false) }
        }
        if (!running.compareAndSet(false, true)) return error(409, "bridge_write_probe_busy")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val done = CountDownLatch(1); val heartbeat = CountDownLatch(1)
        val writes = AtomicInteger(); val callbacks = AtomicInteger()
        val committed = AtomicBoolean(false); val worker = AtomicBoolean(false)
        val onMain = AtomicBoolean(false); val ordered = AtomicBoolean(false); val readback = AtomicBoolean(false)
        var submitted = false
        var result: JSONObject? = null
        var deleted = false
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            submitted = true
            DreamBridgePersistence.submit({
                worker.set(Looper.myLooper() != Looper.getMainLooper()); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                writes.incrementAndGet()
                prefs.edit().putBoolean("generated", true).commit().also { committed.set(it) }
            }) { success ->
                try {
                    callbacks.incrementAndGet(); onMain.set(Looper.myLooper() == Looper.getMainLooper())
                    ordered.set(success && committed.get()); readback.set(prefs.getBoolean("generated", false))
                } finally { done.countDown() }
            }
            check(entered.await(2, TimeUnit.SECONDS))
            val start = SystemClock.elapsedRealtimeNanos()
            check(Handler(Looper.getMainLooper()).post { heartbeat.countDown() })
            val responsive = heartbeat.await(750, TimeUnit.MILLISECONDS) && release.count == 1L && done.count == 1L
            val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
            release.countDown(); check(done.await(3, TimeUnit.SECONDS))
            result = JSONObject().put("ok", true).put("mainHeartbeatWhileWritePaused", responsive)
                .put("completionAfterWrite", ordered.get()).put("persistedReadback", readback.get())
                .put("writeOnWorker", worker.get()).put("callbackOnMain", onMain.get())
                .put("heartbeatMs", elapsed).put("writes", writes.get()).put("callbacks", callbacks.get())
        } catch (_: Exception) { result = null }
        finally {
            release.countDown()
            val finished = !submitted || runCatching { done.await(3, TimeUnit.SECONDS) }.getOrDefault(false)
            if (finished) deleted = cleanup(context)
            if (finished && deleted) running.set(false)
            else synchronized(running) { pendingDone = done }
        }
        return result?.let { response(200, it.put("fixtureDeleted", deleted)) }
            ?: error(503, "bridge_write_probe_failed")
    }
}
