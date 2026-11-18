/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The one Home Assistant websocket the Control Center keeps per Portal. Everything below runs on
 * one thread ([exec]); the socket's callbacks hop onto it, so state is never shared. Screens read
 * [flow]; [ControlCenter] is the facade. Each socket belongs to a generation and callbacks from an
 * older one are ignored, so a restart can never leave two sockets. Logs never include the
 * address, token, entity names or plan contents.
 */
internal class CcEngine(val app: Context) {
  val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hearthpane-control").apply { isDaemon = true } }
  val flow = MutableStateFlow(CcUiState(settings = ControlCenterConfig.load(app)))
  var ui: CcUiState = flow.value
  var live = CcLive()
  private var publishedLive: CcLive? = null
  var protocol: CcProtocol? = null
  var socket: WebSocket? = null
  var generation = 0L
  var attempt = 0
  var retry: ScheduledFuture<*>? = null
  var heartbeat: ScheduledFuture<*>? = null
  var flushQueued = false
  var visible = 0
  var hadLive = false
  var subscribed: Set<String>? = null
  var subscribedForeground = false
  val parts = HashMap<CcProtocol.Kind, Any?>()
  var planResolved = false
  val calls = HashMap<String, CcCallInfo>()
  var callSeq = 0L
  var toastSeq = 0L
  var lastUndo: CcCall? = null
  var seenAlerts: Set<String>? = null
  /** Operator test switches (Fleet `cc_debug`); see [CcDebugStore]. */
  var dryRun = CcDebugStore.dryRun(app)
  var testPlan: CcPlan? = CcDebugStore.testPlan(app)
  @Volatile var clock: () -> Long = System::currentTimeMillis
  /** Floor models for the plan, kept between snapshots (engine thread only). */
  private val planModels = CcPlanModelCache()

  private val client by lazy { OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(CONNECT_S, TimeUnit.SECONDS).build() }

  val wantSocket: Boolean get() = visible > 0 || (ui.settings.bannersListen && HaConfig.isConfigured(app))

  init {
    ui = ui.copy(debug = CcTestPlan.label(dryRun, testPlan != null))
    flow.value = ui
  }

  /** The only path that writes the plan to Home Assistant; never while a test plan or dry run is on. */
  fun sendPlanToHa(plan: CcPlan): Boolean {
    if (testPlan != null || dryRun) return false
    val ws = socket ?: return false
    val p = protocol ?: return false
    if (!CcPlanJson.fits(plan)) return false
    ws.send(p.setPlan(CcPlanJson.encode(plan)).text)
    return true
  }

  fun post(block: () -> Unit) = exec.execute { runCatching(block).onFailure { Log.w(TAG, "control center task failed", it) } }

  // --- lifecycle -------------------------------------------------------------------------

  /** Opens (or re-targets) the socket for the current visibility; closes it when nobody needs it. */
  fun ensure() {
    if (!wantSocket) { disconnect(); return }
    if (!HaConfig.isConfigured(app)) { disconnect(); setPhase(CcPhase.NOT_CONFIGURED, ""); return }
    if (socket == null && retry?.isDone != false) connect() else if (ui.connection.phase == CcPhase.LIVE) retarget()
  }

  fun restart() { disconnect(); attempt = 0; ensure() }

  fun disconnect() {
    generation++
    retry?.cancel(false); retry = null
    heartbeat?.cancel(false); heartbeat = null
    socket?.close(CLOSE_NORMAL, null); socket = null
    protocol = null; subscribed = null; parts.clear(); planResolved = false
    calls.values.forEach { it.onResult?.invoke(false, null) }
    calls.clear()
  }

  private fun connect() {
    val gen = ++generation
    retry = null
    val url = DoorbellUrls.websocket(HaConfig.baseUrl(app))
    if (url == null) { setPhase(CcPhase.NOT_CONFIGURED, ""); return }
    if (!online()) { scheduleRetry(gen, CcPhase.OFFLINE); return }
    setPhase(if (hadLive) CcPhase.STALE else CcPhase.CONNECTING, "Connecting")
    val p = CcProtocol(HaConfig.token(app)).also { protocol = it }
    val request = runCatching { Request.Builder().url(url).build() }.getOrElse { setPhase(CcPhase.NOT_CONFIGURED, ""); return }
    socket = client.newWebSocket(request, Listener(gen, p))
  }

  private inner class Listener(val gen: Long, val p: CcProtocol) : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, text: String) = post { if (gen == generation) handle(webSocket, p, text) }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post { if (gen == generation) dropped("closed $code") }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post { if (gen == generation) dropped(t.javaClass.simpleName) }
  }

  private fun dropped(why: String) {
    Log.i(TAG, "control center socket $why")
    abandonSocket()
    if (!wantSocket) return
    scheduleRetry(generation, if (hadLive && attempt == 0) CcPhase.STALE else CcPhase.UNREACHABLE)
  }

  /** Forget the current socket; its late callbacks belong to an old generation and are ignored. */
  private fun abandonSocket() {
    generation++
    socket?.cancel(); socket = null
    protocol = null; subscribed = null; parts.clear(); planResolved = false
    heartbeat?.cancel(false); heartbeat = null
    calls.values.forEach { it.onResult?.invoke(false, null) }
    calls.clear()
  }

  fun scheduleRetry(gen: Long, phase: CcPhase, delay: Long = CcBackoff.delayMs(attempt)) {
    if (gen != generation) return
    attempt++
    val at = clock() + delay
    ui = ui.copy(connection = ui.connection.copy(phase = phase, retryAt = at, step = ""))
    publish()
    retry = exec.schedule({ post { if (gen == generation && wantSocket) connect() } }, delay, TimeUnit.MILLISECONDS)
  }

  private fun online(): Boolean = runCatching {
    @Suppress("DEPRECATION")
    (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.isConnected == true
  }.getOrDefault(true)

  // --- the conversation ------------------------------------------------------------------

  private fun handle(ws: WebSocket, p: CcProtocol, text: String) {
    for (out in p.onMessage(text)) when (out) {
      is CcProtocol.Out.Send -> ws.send(out.text)
      CcProtocol.Out.AuthOk -> authOk(ws, p)
      CcProtocol.Out.AuthFailed -> { Log.w(TAG, "control center token refused"); abandonSocket()
        scheduleRetry(generation, CcPhase.AUTH_FAILED, CcBackoff.AUTH_MS); return }
      is CcProtocol.Out.Entities -> entities { live.applyCompressed(out.event, clock()) }
      is CcProtocol.Out.Snapshot -> entities { live.snapshot(out.entities) }
      is CcProtocol.Out.Changed -> entities { live.update(mapOf(out.entityId to out.entity), clock()) }
      CcProtocol.Out.Subscribed -> setPhase(ui.connection.phase, "Waiting for the first update")
      CcProtocol.Out.Pong -> Unit
      is CcProtocol.Out.Result -> result(ws, p, out)
    }
  }

  private fun authOk(ws: WebSocket, p: CcProtocol) {
    setPhase(ui.connection.phase, "Loading rooms")
    (p.registry() + p.getPlan() + p.energyPrefs()).forEach { ws.send(it.text) }
    heartbeat?.cancel(false)
    heartbeat = exec.scheduleWithFixedDelay({ post(::beat) }, PING_S, PING_S, TimeUnit.SECONDS)
  }

  private fun beat() {
    val p = protocol ?: return
    val ws = socket ?: return
    val sent = p.pingSentAt
    if (sent > 0 && clock() - sent > PONG_TIMEOUT_MS) { Log.i(TAG, "control center: no pong; reconnecting"); dropped("stale"); return }
    ws.send(p.ping(clock()).text)
    ui = ui.copy(now = clock())
    publish(alertsToo = true)
  }

  private fun entities(change: () -> CcLive) {
    live = change()
    if (ui.connection.phase != CcPhase.LIVE) {
      attempt = 0; hadLive = true
      ui = ui.copy(connection = CcConnection(CcPhase.LIVE, "", clock(), 0L))
    } else ui = ui.copy(connection = ui.connection.copy(lastLiveAt = clock()))
    scheduleFlush()
  }

  private fun result(ws: WebSocket, p: CcProtocol, r: CcProtocol.Out.Result) {
    when (r.req.kind) {
      CcProtocol.Kind.FLOORS, CcProtocol.Kind.AREAS, CcProtocol.Kind.DEVICES, CcProtocol.Kind.ENTITIES -> {
        parts[r.req.kind] = if (r.ok) r.result else null
        if (parts.size == REGISTRY_PARTS) registryReady(ws, p)
      }
      CcProtocol.Kind.PLAN_GET -> planLoaded(ws, p, if (r.ok) (r.result as? org.json.JSONObject)?.optJSONObject("value") else null)
      CcProtocol.Kind.PLAN_SET -> planSaved(r.ok)
      CcProtocol.Kind.CALL -> callResult(r)
      CcProtocol.Kind.ENERGY_PREFS -> { ui = ui.copy(energyPrefs = if (r.ok) CcEnergy.prefs(r.result) else CcEnergyPrefs()); publish() }
      CcProtocol.Kind.STATS -> statsResult(r)
      else -> Unit
    }
  }

  private fun registryReady(ws: WebSocket, p: CcProtocol) {
    val floors = CcRegistryParser.floors(parts[CcProtocol.Kind.FLOORS] as? org.json.JSONArray)
    val areas = CcRegistryParser.areas(parts[CcProtocol.Kind.AREAS] as? org.json.JSONArray)
    val devices = CcRegistryParser.deviceAreas(parts[CcProtocol.Kind.DEVICES] as? org.json.JSONArray)
    val ents = parts[CcProtocol.Kind.ENTITIES] as? org.json.JSONArray
    ui = ui.copy(registry = if (ents == null) null else CcRegistryParser.registry(floors, areas, devices, ents))
    Log.i(TAG, "control center registry: ${floors.size} floors, ${areas.size} areas, ${ui.registry?.entities?.size ?: 0} entities")
    setPhase(ui.connection.phase, "Loading devices")
    if (planResolved) subscribe(ws, p)
  }

  private fun planLoaded(ws: WebSocket, p: CcProtocol, value: org.json.JSONObject?) {
    val ha = CcPlanJson.decode(value)
    val local = CcPlanStore.load(app)
    val chosen = CcPlanStore.newest(ha, local)
    val source = when { chosen == null -> CcPlanSource.NONE; chosen === ha -> CcPlanSource.HOME_ASSISTANT; else -> CcPlanSource.THIS_PORTAL }
    if (chosen != null && chosen === local) sendPlanToHa(chosen)
    if (chosen === ha && ha != null) CcPlanStore.save(app, ha)
    val test = testPlan
    ui = if (test != null) ui.copy(plan = test, planSource = CcPlanSource.THIS_PORTAL)
    else ui.copy(plan = chosen ?: CcPlan.EMPTY, planSource = source)
    planResolved = true
    if (parts.size == REGISTRY_PARTS) subscribe(ws, p)
    publish()
  }

  private fun subscribe(ws: WebSocket, p: CcProtocol) {
    val ids = interest()
    subscribed = ids
    subscribedForeground = visible > 0
    // Nothing worth watching in the background (no registry, no plan): stay quiet, no subscription.
    if (ids != null && ids.isEmpty()) { setPhase(CcPhase.LIVE, ""); return }
    ws.send(p.subscribe(ids).text)
  }

  /** Visibility changed while live: swap the subscription without reconnecting. */
  private fun retarget() {
    val ws = socket ?: return
    val p = protocol ?: return
    if (!planResolved || parts.size != REGISTRY_PARTS) return
    val ids = interest()
    if (ids == subscribed && subscribedForeground == (visible > 0)) return
    if (visible == 0) live = live.snapshot(live.raw.values.filter { ids == null || it.id in ids })
    subscribed = ids
    subscribedForeground = visible > 0
    p.resubscribe(ids).forEach { ws.send(it.text) }
  }

  private fun interest(): Set<String>? =
      if (visible > 0) CcInterest.foreground(ui.registry, ui.plan)
      else CcInterest.background(ui.registry) ?: ui.plan?.entityIds.orEmpty()

  // --- publishing --------------------------------------------------------------------------

  fun setPhase(phase: CcPhase, step: String) {
    ui = ui.copy(connection = ui.connection.copy(phase = phase, step = step))
    publish()
  }

  /** Coalesces websocket bursts: the UI sees at most one update per [FLUSH_MS]. */
  fun scheduleFlush() {
    if (flushQueued) return
    flushQueued = true
    exec.schedule({ post { flushQueued = false; publish(alertsToo = true) } }, FLUSH_MS, TimeUnit.MILLISECONDS)
  }

  fun publish(alertsToo: Boolean = false) {
    // Reuse the same entity map until the live state actually changes, so Compose readers skip.
    if (live !== publishedLive) {
      publishedLive = live
      ui = ui.copy(entities = live.view(), pending = live.pending.keys, appliancesDone = live.done, entitiesVersion = ui.entitiesVersion + 1)
    }
    // Derive what every screen reads once, here on the engine thread, not inside a frame.
    ui = ui.copy(now = clock())
    ui = ui.copy(planModels = if (visible > 0) planModels.of(ui) else emptyMap()).warm()
    if (visible == 0) planModels.clear()
    flow.value = ui
    if (alertsToo && ui.connection.phase == CcPhase.LIVE) presentNewAlerts()
  }

  private fun presentNewAlerts() {
    val active = ui.alerts()
    val seen = seenAlerts
    seenAlerts = active.map { it.id }.toSet() + (seen ?: emptySet())
    val fresh = active.filter { seen == null && it.severity == CcSeverity.CRITICAL || seen != null && it.id !in seen }
    fresh.forEach { ControlCenterBanners.offer(app, it, ui.settings, appVisible = visible > 0, dryRun = dryRun) }
  }

  companion object {
    const val TAG = "HearthpaneControl"
    private const val CLOSE_NORMAL = 1000
    private const val CONNECT_S = 8L
    const val PING_S = 30L
    const val PONG_TIMEOUT_MS = 10_000L
    private const val REGISTRY_PARTS = 4
    /** Websocket bursts are batched for this long before the UI sees them (~10 updates/s max). */
    const val FLUSH_MS = 100L
  }
}

/** A call in flight: what it was, the entity before it, and who wants the answer. */
internal data class CcCallInfo(val call: CcCall, val before: CcEntity?, val undoable: Boolean, val onResult: ((Boolean, String?) -> Unit)?)
