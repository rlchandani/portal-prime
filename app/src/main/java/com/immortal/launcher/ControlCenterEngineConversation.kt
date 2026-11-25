/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/*
 * The engine's half of the Home Assistant conversation (engine thread): auth, the registry and
 * the plan, then the live subscription. [CcProtocol] owns the wire; this decides what to ask next.
 */

internal fun CcEngine.handle(ws: CcSocket, p: CcProtocol, text: String) {
  for (out in p.onMessage(text)) when (out) {
    is CcProtocol.Out.Send -> ws.send(out.text)
    CcProtocol.Out.AuthOk -> authOk(ws, p)
    CcProtocol.Out.AuthFailed -> { log.w("control center token refused"); abandonSocket()
      scheduleRetry(generation, CcPhase.AUTH_FAILED, CcBackoff.AUTH_MS); return }
    is CcProtocol.Out.Entities -> entities(CcLastKnown.additions(out.event)) { live.applyCompressed(out.event, clock()) }
    is CcProtocol.Out.Snapshot -> entities(out.entities.map { it.id }, sweep = true) { live.snapshot(out.entities) }
    is CcProtocol.Out.Changed -> entities(listOf(out.entityId), sweep = false) { live.update(mapOf(out.entityId to out.entity), clock()) }
    // Already live (a resubscribe, or get_states answered first): the states are here, nothing to wait for.
    CcProtocol.Out.Subscribed -> if (ui.connection.phase != CcPhase.LIVE) setPhase(ui.connection.phase, "Waiting for the first update")
    CcProtocol.Out.Pong -> Unit
    is CcProtocol.Out.Result -> result(ws, p, out)
  }
}

private fun CcEngine.authOk(ws: CcSocket, p: CcProtocol) {
  setPhase(ui.connection.phase, "Loading rooms")
  (p.registry() + p.getPlan()).forEach { ws.send(it.text) }
  startHeartbeat()
}

/** [full] are the ids this message gave whole (confirming them); [sweep] marks a full answer. */
private fun CcEngine.entities(full: Collection<String>, sweep: Boolean = full.isNotEmpty(), change: () -> CcLive) {
  live = change()
  reconcileLastKnown(full, sweep)
  if (ui.connection.phase != CcPhase.LIVE) {
    attempt = 0; hadLive = true
    ui = ui.copy(connection = CcConnection(CcPhase.LIVE, "", clock(), 0L))
  } else ui = ui.copy(connection = ui.connection.copy(step = "", lastLiveAt = clock()))
  scheduleFlush()
}

private fun CcEngine.result(ws: CcSocket, p: CcProtocol, r: CcProtocol.Out.Result) {
  when (r.req.kind) {
    CcProtocol.Kind.FLOORS, CcProtocol.Kind.AREAS, CcProtocol.Kind.DEVICES, CcProtocol.Kind.ENTITIES, CcProtocol.Kind.CLASSES -> {
      parts = parts + (r.req.kind to if (r.ok) r.result else null)
      if (r.req.kind == CcProtocol.Kind.ENTITIES) requestClasses(ws, p, parts[r.req.kind] as? JSONArray)
      if (parts.size == CcEngine.REGISTRY_PARTS) registryReady(ws, p)
    }
    CcProtocol.Kind.PLAN_GET -> planLoaded(ws, p, if (r.ok) (r.result as? JSONObject)?.optJSONObject("value") else null)
    CcProtocol.Kind.PLAN_SET -> planSaved(r.ok)
    CcProtocol.Kind.CALL -> callResult(r)
    CcProtocol.Kind.STATS -> chargeStatsResult(r)
    CcProtocol.Kind.CAMERA_STREAM -> takeStream(r.req.key)?.invoke(if (r.ok) (r.result as? JSONObject)?.optString("url") else null)
    else -> Unit
  }
}

/** Asks HA for [entityId]'s HLS stream; [onUrl] gets the relative URL, or null (no socket, refused). */
internal fun CcEngine.requestStream(entityId: String, onUrl: (String?) -> Unit) {
  val ws = socket; val p = protocol
  if (ws == null || p == null) { onUrl(null); return }
  val key = "s${++streamSeq}"
  streams = streams + (key to onUrl)
  ws.send(p.cameraStream(entityId, key).text)
}

private fun CcEngine.takeStream(key: String): ((String?) -> Unit)? = streams[key]?.also { streams = streams - key }

internal fun CcEngine.failStreams() {
  val waiting = streams.values.toList()
  streams = emptyMap()
  waiting.forEach { it(null) }
}

/** The list call has no device classes; ask for the binary sensors' entries (one request). */
private fun CcEngine.requestClasses(ws: CcSocket, p: CcProtocol, ents: JSONArray?) {
  val ids = ents?.let { CcRegistryParser.registry(emptyList(), emptyList(), emptyMap(), it).classless }.orEmpty()
  if (ids.isEmpty()) parts = parts + (CcProtocol.Kind.CLASSES to null) else ws.send(p.classes(ids).text)
}

private fun CcEngine.registryReady(ws: CcSocket, p: CcProtocol) {
  val floors = CcRegistryParser.floors(parts[CcProtocol.Kind.FLOORS] as? JSONArray)
  val areas = CcRegistryParser.areas(parts[CcProtocol.Kind.AREAS] as? JSONArray)
  val deviceList = parts[CcProtocol.Kind.DEVICES] as? JSONArray
  val devices = CcRegistryParser.deviceAreas(deviceList)
  val ents = parts[CcProtocol.Kind.ENTITIES] as? JSONArray
  val classes = CcRegistryParser.classes(parts[CcProtocol.Kind.CLASSES] as? JSONObject)
  ui = ui.copy(registry = if (ents == null) null
      else CcRegistryParser.registry(floors, areas, devices, ents)
          .copy(fetchedClass = classes, tesla = CcTeslaRegistry.parse(deviceList, ents)))
  log.i("control center registry: ${floors.size} floors, ${areas.size} areas, ${ui.registry?.entities?.size ?: 0} entities, " +
      "${classes.size} sensor classes")
  setPhase(ui.connection.phase, "Loading devices")
  if (planResolved) subscribe(ws, p)
}

private fun CcEngine.planLoaded(ws: CcSocket, p: CcProtocol, value: JSONObject?) {
  val ha = CcPlanJson.decode(value)
  val local = host.loadPlan()
  val chosen = CcPlanStore.newest(ha, local)
  val source = when { chosen == null -> CcPlanSource.NONE; chosen === ha -> CcPlanSource.HOME_ASSISTANT; else -> CcPlanSource.THIS_PORTAL }
  if (chosen != null && chosen === local) sendPlanToHa(chosen)
  if (chosen === ha && ha != null) host.savePlan(ha)
  val test = testPlan
  ui = if (test != null) ui.copy(plan = test, planSource = CcPlanSource.THIS_PORTAL)
  else ui.copy(plan = chosen ?: CcPlan.EMPTY, planSource = source)
  planResolved = true
  if (parts.size == CcEngine.REGISTRY_PARTS) subscribe(ws, p)
  publish()
}

private fun CcEngine.subscribe(ws: CcSocket, p: CcProtocol) {
  val ids = interest()
  subscribed = ids
  subscribedForeground = foreground
  awaitingFull = foreground && unconfirmed.isNotEmpty()
  // Nothing worth watching in the background (no registry, no plan): stay quiet, no subscription.
  if (ids != null && ids.isEmpty()) { setPhase(CcPhase.LIVE, ""); return }
  ws.send(p.subscribe(ids).text)
}

/** Visibility changed while live: swap the subscription without reconnecting. */
internal fun CcEngine.retarget() {
  val ws = socket ?: return
  val p = protocol ?: return
  if (!planResolved || parts.size != CcEngine.REGISTRY_PARTS) return
  val ids = interest()
  val fg = foreground
  if (ids == subscribed && subscribedForeground == fg) return
  if (!fg) {
    // Kept as last known (not dropped), so the next open draws them at once (see [CcLastKnown]).
    val split = CcLastKnown.split(live.raw, ids, lastKnown)
    lastKnown = split.retained
    live = live.snapshot(split.kept)
    unconfirmed = unconfirmed.filterTo(HashSet()) { it in live.raw }
    awaitingFull = false
    // Persist the same set so even a cold open (after a reboot or a kill) draws at once.
    host.saveLastKnown(lastKnown)
  } else awaitingFull = unconfirmed.isNotEmpty()
  subscribed = ids
  subscribedForeground = fg
  p.resubscribe(ids).forEach { ws.send(it.text) }
}

private fun CcEngine.interest(): Set<String>? =
    if (foreground) CcInterest.foreground(ui.registry, ui.plan)
    else CcInterest.background(ui.registry) ?: ui.plan?.entityIds.orEmpty()

/**
 * An open: the retained last-known entities go back on screen at once, unconfirmed, and the engine publishes
 * (building the plan models here, not in CC's first frame). Engine thread.
 */
internal fun CcEngine.restoreLastKnown() {
  val seeded = loadDiskCacheOnce()
  val restored = CcLastKnown.restorable(live.raw, lastKnown)
  lastKnown = emptyMap()
  if (restored.isNotEmpty()) {
    live = live.snapshot(live.raw.values + restored.values)
    unconfirmed = unconfirmed + restored.keys
  }
  if (restored.isNotEmpty() || seeded) publish()
}

/**
 * The first open of the process seeds from disk ([CcLastKnownStore], [CcPlanStore]): the retained
 * last-known entities and the cached floor plan, so a cold open draws the plan with its states at
 * once instead of blank-then-redraw. Home Assistant's answers still reconcile both (the plan via
 * [CcPlanStore.newest], the entities via [reconcileLastKnown]). Engine thread. Returns whether it
 * changed [ui] (so the caller publishes even when nothing was retained).
 */
private fun CcEngine.loadDiskCacheOnce(): Boolean {
  if (diskLoaded) return false
  diskLoaded = true
  if (lastKnown.isEmpty()) lastKnown = host.loadLastKnown()
  if (testPlan == null && ui.plan?.floors.isNullOrEmpty()) {
    host.loadPlan()?.takeIf { it.floors.isNotEmpty() }?.let {
      ui = ui.copy(plan = it, planSource = CcPlanSource.THIS_PORTAL)
      return true
    }
  }
  return false
}

/** HA gave [full] whole: they're live again. A full answer to the foreground subscription drops the rest. */
private fun CcEngine.reconcileLastKnown(full: Collection<String>, sweep: Boolean) {
  if (unconfirmed.isEmpty()) { awaitingFull = false; return }
  unconfirmed = CcLastKnown.confirm(unconfirmed, full)
  if (!(sweep && awaitingFull) || unconfirmed.isEmpty()) { if (unconfirmed.isEmpty()) awaitingFull = false; return }
  // Not in HA's answer: gone from HA or from the plan since it was last seen.
  val gone = unconfirmed
  live = live.snapshot(live.raw.values.filter { it.id !in gone })
  unconfirmed = emptySet()
  awaitingFull = false
}
