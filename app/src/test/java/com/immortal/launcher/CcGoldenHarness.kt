/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.TimeZone
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * One Portal's Control Center engine with every Android edge replaced: in-memory preferences and a
 * scratch files dir behind a mocked Context, a virtual clock and executor ([VirtualScheduler]),
 * and fake Home Assistant sockets. Everything the engine does that leaves it (socket traffic, logs,
 * state it publishes, callbacks, files) is appended to [out] as one line each, so a scenario's
 * whole conversation can be compared with a recording.
 */
internal class CcGoldenHarness(private val dir: File, private val data: JSONObject) : AutoCloseable {
  val sched = VirtualScheduler(T0)
  val out = ArrayList<String>()
  private val prefs = HashMap<String, ScreensaverTestPrefs>()
  var online = true
  val sockets = ArrayList<FakeSocket>()
  private val logs: MockedStatic<Log> = Mockito.mockStatic(Log::class.java) { inv ->
    if (inv.method.returnType == Int::class.javaPrimitiveType) {
      val args = inv.arguments
      val tag = args.getOrNull(0) as? String ?: ""
      if (tag.startsWith("Hearthpane")) logLine(inv.method.name, tag, args.getOrNull(1)?.toString().orEmpty(), args.getOrNull(2) as? Throwable)
      0
    } else Mockito.RETURNS_DEFAULTS.answer(inv)
  }
  private val loopers: MockedStatic<Looper> = Mockito.mockStatic(Looper::class.java)
  private val handlers: MockedConstruction<Handler> = Mockito.mockConstruction(Handler::class.java) { h, _ ->
    // The facade's main handler delivers at once; a banner's overlay work is only noted.
    val banner = Thread.currentThread().stackTrace.any { it.className.startsWith("com.immortal.launcher.ControlCenterBanners") }
    `when`(h.post(Mockito.any())).thenAnswer { if (banner) out += "banner main.post" else (it.arguments[0] as Runnable).run(); true }
  }
  private val zone = TimeZone.getDefault()

  val context: Context = mock(Context::class.java).also { c ->
    `when`(c.applicationContext).thenReturn(c)
    `when`(c.getSharedPreferences(anyString(), anyInt())).thenAnswer { prefs(it.getArgument(0)) }
    `when`(c.filesDir).thenReturn(dir)
    `when`(c.cacheDir).thenReturn(File(dir, ".cache"))
    val info = mock(NetworkInfo::class.java)
    `when`(info.isConnected).thenAnswer { online }
    val cm = mock(ConnectivityManager::class.java)
    `when`(cm.activeNetworkInfo).thenAnswer { info }
    `when`(c.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)
  }

  lateinit var engine: CcEngine
    private set

  init {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    // Quiet hours follow the wall clock; off, so banner decisions don't depend on when the test runs.
    prefs("immortal_chime").edit().putBoolean("quiet_hours_on", false).apply()
    resetAlertRelay()
  }

  fun prefs(name: String): ScreensaverTestPrefs = prefs.getOrPut(name) { ScreensaverTestPrefs() }

  /** Builds the engine (after the scenario's preferences are in place) and makes it the facade's. */
  fun start() {
    engine = CcGoldenEngine.create(context, sched, ::openSocket)
    val f = ControlCenter::class.java.getDeclaredField("engine")
    f.isAccessible = true
    f.set(null, engine)
    CcAlertRelay.clock = { sched.now }
  }

  override fun close() {
    runCatching {
      val f = ControlCenter::class.java.getDeclaredField("engine"); f.isAccessible = true; f.set(null, null)
    }
    CcAlertRelay.clock = System::currentTimeMillis
    resetAlertRelay()
    TimeZone.setDefault(zone)
    handlers.close(); loopers.close(); logs.close()
  }

  /** The relay's gates are process-wide; every scenario starts with fresh ones. */
  private fun resetAlertRelay() {
    val c = CcAlertRelay::class.java
    c.getDeclaredField("gate").apply { isAccessible = true }.set(null, null)
    c.getDeclaredField("dryGate").apply { isAccessible = true }.set(null, CcAlertGate())
  }

  private fun logLine(level: String, tag: String, msg: String, t: Throwable?) {
    // The Home dot is process-wide state shared with other suites; its transitions aren't the engine's.
    if (msg.startsWith("home control dot")) return
    out += "log $level $tag: $msg" + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
  }

  fun rel(ms: Long): String = if (ms == 0L) "0" else (ms - T0).let { if (it >= 0) "+$it" else "$it" }

  // --- sockets ------------------------------------------------------------------------------

  inner class FakeSocket(val index: Int, val url: String, val listener: WebSocketListener) : WebSocket {
    val sent = ArrayList<JSONObject>()
    override fun request(): Request = Request.Builder().url(url).build()
    override fun queueSize(): Long = 0L
    override fun send(text: String): Boolean {
      val json = runCatching { JSONObject(text) }.getOrNull()
      if (json != null) sent += json
      out += "send#$index " + (json?.let { FleetGoldenHarness.canonical(it) } ?: text)
      return true
    }
    override fun send(bytes: ByteString): Boolean = error("binary frames are never sent")
    override fun close(code: Int, reason: String?): Boolean { out += "close#$index $code"; return true }
    override fun cancel() { out += "cancel#$index" }
  }

  private fun openSocket(request: Request, listener: WebSocketListener): WebSocket {
    val s = FakeSocket(sockets.size, request.url.toString(), listener)
    sockets += s
    out += "open#${s.index} ${s.url}"
    return s
  }

  fun socket(step: JSONObject): FakeSocket =
      if (step.has("socket")) sockets[step.getInt("socket")] else sockets.lastOrNull() ?: error("no socket opened")

  // --- Home Assistant side ------------------------------------------------------------------

  /** Resolves "$name" references to the scenario data block (registries, plans). */
  fun resolve(v: Any?): Any? = when {
    v is String && v.startsWith("$") -> data.opt(v.substring(1)) ?: error("no data $v")
    v is JSONObject -> JSONObject().also { o -> v.keys().forEach { k -> o.put(k, resolve(v.get(k))) } }
    v is JSONArray -> JSONArray().also { a -> (0 until v.length()).forEach { a.put(resolve(v.get(it))) } }
    else -> v
  }

  /** The id of the last request of [type] this socket sent. */
  private fun idOf(s: FakeSocket, type: String): Int =
      s.sent.lastOrNull { it.optString("type") == type }?.getInt("id") ?: error("no $type sent on #${s.index}")

  fun haMessage(step: JSONObject): String {
    val s = socket(step)
    val msg = when {
      step.has("ha") -> step.get("ha").let { if (it is JSONObject) it.toString() else it.toString() }
      step.has("reply") -> JSONObject().put("id", idOf(s, step.getString("reply"))).put("type", "result")
          .put("success", step.optBoolean("success", true)).also { r ->
            if (step.has("result")) r.put("result", resolve(step.get("result")))
            if (step.has("error")) r.put("error", step.get("error"))
          }.toString()
      step.has("pong") -> JSONObject().put("id", idOf(s, "ping")).put("type", "pong").toString()
      step.has("event") -> JSONObject().put("id", idOf(s, step.getString("event"))).put("type", "event")
          .put("event", resolve(step.get("data"))).toString()
      else -> error("not a Home Assistant step: $step")
    }
    val pad = step.optInt("padTo", 0)
    return if (pad > 0) msg.replace(PAD, "x".repeat((pad - msg.length + PAD.length).coerceAtLeast(0))) else msg
  }

  fun deliver(step: JSONObject) {
    val s = socket(step)
    val text = haMessage(step)
    out += "ha#${s.index} " + if (text.length > MAX_ECHO) "<${text.length} chars>" else text
    s.listener.onMessage(s, text)
    sched.runDue()
  }

  fun closed(step: JSONObject) {
    val s = socket(step)
    s.listener.onClosed(s, step.getInt("closed"), "")
    sched.runDue()
  }

  fun failed(step: JSONObject) {
    val s = socket(step)
    s.listener.onFailure(s, java.io.IOException("reset"), null)
    sched.runDue()
  }

  // --- what the engine shows ----------------------------------------------------------------

  fun summary(): String {
    val s = engine.flow.value
    val c = s.connection
    val ents = s.entities.values.sortedBy { it.id }
    val shown = ents.take(MAX_LISTED).joinToString(",") { "${it.id}=${it.state}" } + if (ents.size > MAX_LISTED) ",…" else ""
    val reg = s.registry?.let { "${it.entities.size}e/${it.fetchedClass.size}c" } ?: "-"
    val plan = s.plan?.let { p -> "${p.floors.size}f/${p.floors.sumOf { it.rooms.size }}r@${rel(p.updatedAt)}" } ?: "-"
    val toast = s.toast?.let { "'${it.text}'" + (if (it.undo) "+undo" else "") + (if (it.error) "!" else "") } ?: "-"
    val alerts = s.alerts().joinToString(",") { "${it.entityId}:${it.severity.name.lowercase()}" }
    val scene = s.sceneRun?.let { "${it.name}/${it.rooms.size}r" } ?: "-"
    val charge = s.chargeWeek?.let { "charge" } ?: "-"
    return "state ${c.phase} '${c.step}' live=${rel(c.lastLiveAt)} retry=${rel(c.retryAt)} v=${s.entitiesVersion} " +
        "ents=${ents.size}[$shown] pending=${s.pending.sorted()} reg=$reg plan=$plan/${s.planSource} toast=$toast " +
        "debug=${s.debug ?: "-"} alerts=[$alerts] scene=$scene $charge dismissed=${s.dismissed.keys.sorted()}"
  }

  fun files(): String {
    val plan = File(dir, "control_center_plan.json").takeIf { it.exists() }?.readText()?.let { JSONObject(it) }
    val test = File(dir, "control_center_test_plan.json").takeIf { it.exists() }?.readText()?.let { JSONObject(it) }
    val dry = prefs("hearthpane_control_center_debug").getBoolean("dry_run", false)
    fun brief(o: JSONObject?) = o?.let { "${it.optJSONArray("floors")?.length() ?: 0}f@${rel(it.optLong("updatedAt"))}" } ?: "-"
    return "files plan=${brief(plan)} testPlan=${brief(test)} dryRunPref=$dry"
  }

  companion object {
    const val T0 = 1_700_000_000_000L
    const val PAD = "<PAD>"
    private const val MAX_ECHO = 4_000
    private const val MAX_LISTED = 14

    fun arr(vararg o: Any?): JSONArray = JSONArray(o.toList())
  }
}

/** Builds a [CcEngine] whose executor, clock and sockets are the harness's. */
internal object CcGoldenEngine {
  fun create(context: Context, sched: VirtualScheduler, open: (Request, WebSocketListener) -> WebSocket): CcEngine {
    val e = CcEngine(context)
    val real = e.exec
    set(e, "exec", sched)
    real.shutdownNow()
    set(e, "client\$delegate", lazyOf(FakeOkHttp(open)))
    e.clock = { sched.now }
    return e
  }

  private fun set(target: Any, name: String, value: Any) {
    val f = target.javaClass.getDeclaredField(name)
    f.isAccessible = true
    f.set(target, value)
  }

  private class FakeOkHttp(private val open: (Request, WebSocketListener) -> WebSocket) : OkHttpClient() {
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket = open(request, listener)
  }
}
