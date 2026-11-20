/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The one Home Assistant websocket the Control Center keeps per Portal. Everything below runs on
 * one thread ([sched]); the socket's callbacks hop onto it, so state is never shared. Screens read
 * [flow]; [ControlCenter] is the facade. Each socket belongs to a generation and callbacks from an
 * older one are ignored, so a restart can never leave two sockets. Logs never include the
 * address, token, entity names or plan contents.
 *
 * Pure: the Portal is reached only through [host], [sockets], [sched], [log] and [clock]
 * ([CcEnginePlatform]); the conversation itself is in [handle] and its neighbours.
 */
internal class CcEngine(
    val host: CcHost,
    private val sockets: CcSocketFactory,
    val sched: CcScheduler,
    val log: CcLog,
    @Volatile var clock: () -> Long = System::currentTimeMillis,
) {
  val flow = MutableStateFlow(CcUiState(settings = host.settings()))
  var ui: CcUiState = flow.value
  var live = CcLive()
  private var publishedLive: CcLive? = null
  var protocol: CcProtocol? = null
  var socket: CcSocket? = null
  var generation = 0L
  var attempt = 0
  var retry: CcTimer? = null
  var heartbeat: CcTimer? = null
  var flushQueued = false
  var visible = 0
  /** A paired phone is watching until then (epoch ms): live, foreground state without a screen here. */
  var remoteUntil = 0L
  var remoteExpiry: CcTimer? = null
  var hadLive = false
  var subscribed: Set<String>? = null
  var subscribedForeground = false
  /** Registry answers so far by kind (null = refused or empty). */
  var parts: Map<CcProtocol.Kind, Any?> = emptyMap()
  var planResolved = false
  var calls: Map<String, CcCallInfo> = emptyMap()
  /** Waiting `camera/stream` answers by request key (engine thread only). */
  var streams: Map<String, (String?) -> Unit> = emptyMap()
  var streamSeq = 0L
  var callSeq = 0L
  var toastSeq = 0L
  var lastUndo: CcCall? = null
  var seenAlerts: Set<String>? = null
  /** Operator test switches (Fleet `cc_debug`); see [CcDebugStore]. */
  var dryRun = host.dryRun()
  var testPlan: CcPlan? = host.testPlan()
  /** Operator-only, never persisted: connect to a dead port (see [CcTestPlan.unreachableUrl]). */
  var haUnreachable = false
  /** Floor models for the plan, kept between snapshots (engine thread only). */
  private val planModels = CcPlanModelCache()
  /** One republish queued for when an occupancy signal next decays (motion fading, a sighting ageing out). */
  private var decay: CcTimer? = null
  private var decayAt: Long? = null

  /** Someone needs the full on-screen subscription: this Portal's screen, or a phone's lease. */
  val foreground: Boolean get() = visible > 0 || remoteUntil > clock()

  val wantSocket: Boolean get() = foreground || (ui.settings.bannersListen && host.haConfigured())

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

  fun post(block: () -> Unit) = sched.execute { runCatching(block).onFailure { log.w("control center task failed", it) } }

  // --- lifecycle -------------------------------------------------------------------------

  /** Opens (or re-targets) the socket for the current visibility; closes it when nobody needs it. */
  fun ensure() {
    if (!wantSocket) { disconnect(); return }
    if (!host.haConfigured()) { disconnect(); setPhase(CcPhase.NOT_CONFIGURED, ""); return }
    if (socket == null && retry?.isDone != false) connect() else if (ui.connection.phase == CcPhase.LIVE) retarget()
  }

  fun restart() { disconnect(); attempt = 0; ensure() }

  fun disconnect() {
    generation++
    retry?.cancel(); retry = null
    heartbeat?.cancel(); heartbeat = null
    socket?.close(CLOSE_NORMAL); socket = null
    forgetConversation()
  }

  /** The conversation's state goes with its socket; waiting calls and streams learn they failed. */
  private fun forgetConversation() {
    protocol = null; subscribed = null; parts = emptyMap(); planResolved = false
    calls.values.forEach { it.onResult?.invoke(false, null) }
    calls = emptyMap()
    failStreams()
  }

  private fun connect() {
    val gen = ++generation
    retry = null
    val url = DoorbellUrls.websocket(host.haBaseUrl())?.let { if (haUnreachable) CcTestPlan.unreachableUrl(it) else it }
    if (url == null) { setPhase(CcPhase.NOT_CONFIGURED, ""); return }
    if (!host.online()) { scheduleRetry(gen, CcPhase.OFFLINE); return }
    setPhase(if (hadLive) CcPhase.STALE else CcPhase.CONNECTING, "Connecting")
    val p = CcProtocol(host.haToken()).also { protocol = it }
    val events = Listener(gen, p)
    socket = sockets.open(url, events)?.also { events.ws = it } ?: run { setPhase(CcPhase.NOT_CONFIGURED, ""); return }
  }

  private inner class Listener(val gen: Long, val p: CcProtocol) : CcSocketEvents {
    lateinit var ws: CcSocket
    override fun onMessage(text: String) = post { if (gen == generation) handle(ws, p, text) }
    override fun onClosed(code: Int) = post { if (gen == generation) dropped("closed $code") }
    override fun onFailure(error: Throwable) = post { if (gen == generation) dropped(error.javaClass.simpleName) }
  }

  fun dropped(why: String) {
    log.i("control center socket $why")
    abandonSocket()
    if (!wantSocket) return
    scheduleRetry(generation, if (hadLive && attempt == 0) CcPhase.STALE else CcPhase.UNREACHABLE)
  }

  /** Forget the current socket; its late callbacks belong to an old generation and are ignored. */
  fun abandonSocket() {
    generation++
    socket?.cancel(); socket = null
    protocol = null; subscribed = null; parts = emptyMap(); planResolved = false
    heartbeat?.cancel(); heartbeat = null
    calls.values.forEach { it.onResult?.invoke(false, null) }
    calls = emptyMap()
    failStreams()
  }

  fun scheduleRetry(gen: Long, phase: CcPhase, delay: Long = CcBackoff.delayMs(attempt)) {
    if (gen != generation) return
    attempt++
    val at = clock() + delay
    ui = ui.copy(connection = ui.connection.copy(phase = phase, retryAt = at, step = ""))
    publish()
    retry = sched.schedule(delay) { post { if (gen == generation && wantSocket) connect() } }
  }

  fun startHeartbeat() {
    heartbeat?.cancel()
    heartbeat = sched.repeat(PING_S * MS_PER_S) { post(::beat) }
  }

  private fun beat() {
    val p = protocol ?: return
    val ws = socket ?: return
    val sent = p.pingSentAt
    if (sent > 0 && clock() - sent > PONG_TIMEOUT_MS) { log.i("control center: no pong; reconnecting"); dropped("stale"); return }
    ws.send(p.ping(clock()).text)
    ui = ui.copy(now = clock())
    publish(alertsToo = true)
  }

  // --- publishing --------------------------------------------------------------------------

  fun setPhase(phase: CcPhase, step: String) {
    ui = ui.copy(connection = ui.connection.copy(phase = phase, step = step))
    publish()
  }

  /** Coalesces websocket bursts: the UI sees at most one update per [FLUSH_MS]. */
  fun scheduleFlush() {
    if (flushQueued) return
    flushQueued = true
    sched.schedule(FLUSH_MS) { post { flushQueued = false; publish(alertsToo = true) } }
  }

  fun publish(alertsToo: Boolean = false) {
    // Reuse the same entity map until the live state actually changes, so Compose readers skip.
    if (live !== publishedLive) {
      publishedLive = live
      ui = ui.copy(entities = live.view(), pending = live.pending.keys, appliancesDone = live.done, entitiesVersion = ui.entitiesVersion + 1)
    }
    // Derive what every screen reads once, here on the engine thread, not inside a frame.
    ui = ui.copy(now = clock())
    fuseOccupancy()
    ui = ui.copy(planModels = if (visible > 0) planModels.of(ui) else emptyMap()).warm()
    if (visible == 0) planModels.clear()
    flow.value = ui
    host.homeDot(ui, visible > 0)
    if (alertsToo && ui.connection.phase == CcPhase.LIVE) presentNewAlerts()
  }

  /** People in rooms, only while someone looks (screen or phone); unchanged results keep their instance. */
  private fun fuseOccupancy() {
    val s = ui.settings
    val occ = if (!foreground) CcOccupancy.NONE
    else CcOccupancyFusion.fuse(ui.plan, ui.entities, ui.registry, ui.signals, CcPeoplePolicy.anonymous(s), CcPeoplePolicy.named(s), ui.now)
    if (occ != ui.occupancy) ui = ui.copy(occupancy = occ)
    val at = occ.nextChangeMs
    if (at == decayAt) return
    decay?.cancel()
    decayAt = at
    decay = at?.let { sched.schedule((it - ui.now).coerceAtLeast(0L) + DECAY_SLACK_MS) { post { decayAt = null; publish() } } }
  }

  private fun presentNewAlerts() {
    val active = ui.alerts()
    val seen = seenAlerts
    seenAlerts = active.map { it.id }.toSet() + (seen ?: emptySet())
    host.presentAlerts(CcBackgroundPolicy.plan(active, seen), ui.settings, appVisible = visible > 0, dryRun = dryRun)
  }

  companion object {
    const val TAG = "HearthpaneControl"
    private const val CLOSE_NORMAL = 1000
    const val PING_S = 30L
    private const val MS_PER_S = 1_000L
    const val PONG_TIMEOUT_MS = 10_000L
    /** Floors, areas, devices, entities and the binary sensors' classes. */
    const val REGISTRY_PARTS = 5
    /** Websocket bursts are batched for this long before the UI sees them (~10 updates/s max). */
    const val FLUSH_MS = 100L
    /** Republish just after a signal's decay boundary, so it has certainly passed. */
    private const val DECAY_SLACK_MS = 250L
  }
}

/** A call in flight: what it was, the entity before it, and who wants the answer. */
internal data class CcCallInfo(val call: CcCall, val before: CcEntity?, val undoable: Boolean, val onResult: ((Boolean, String?) -> Unit)?)
