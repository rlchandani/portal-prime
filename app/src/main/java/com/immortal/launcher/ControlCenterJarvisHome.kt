/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * `hearthpane.home_control`: Jarvis asks the house to do something ("turn off the kitchen
 * lights"). Runs on the tool provider's binder thread, inside Jarvis's 5 s tool timeout: brings
 * Home Assistant live if it isn't ([ControlCenter.remoteWatch]), resolves the words against the
 * Control Center registry ([CcHomeCommand]), routes every call through policy ([CcHomeRoute]) and
 * sends the runnable ones through [ControlCenter.send] — the same gate (and dry run) as a tap.
 * Asking-first actions are never sent: the Control Center shows its own confirm sheet ([confirm])
 * and Jarvis is told to point the user at it. While the Control Center is on screen the room
 * lights up ([flash]). Only Jarvis reaches this (the provider's caller check).
 */
internal object CcJarvisHome {
  /** The rooms Jarvis just changed, pulsed on the plan while the Control Center shows. */
  data class Flash(val roomIds: List<String>, val floorId: String?, val seq: Long)

  /** An asking-first call waiting for the household's tap on the Portal's own sheet. */
  data class Confirm(val entityId: String, val call: CcCall, val roomId: String?, val floorId: String?, val seq: Long, val atMs: Long) {
    fun fresh(now: Long): Boolean = now - atMs in 0..CONFIRM_TTL_MS
  }

  private val seqs = AtomicLong()
  private val flashes = MutableStateFlow<Flash?>(null)
  val flash: StateFlow<Flash?> = flashes.asStateFlow()
  private val confirms = MutableStateFlow<Confirm?>(null)
  val confirm: StateFlow<Confirm?> = confirms.asStateFlow()
  /** Control Center screens started (the activity's onStart/onStop). */
  private val showing = AtomicInteger()
  private val undos = object : LinkedHashMap<String, List<Pair<CcEntity, CcCall>>>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<CcEntity, CcCall>>>?) = size > MAX_UNDOS
  }

  fun screenStarted() { showing.incrementAndGet() }
  fun screenStopped() { showing.updateAndGet { (it - 1).coerceAtLeast(0) } }
  val onScreen: Boolean get() = showing.get() > 0

  fun flashDone(f: Flash) { flashes.compareAndSet(f, null) }
  fun confirmShown(c: Confirm) { confirms.compareAndSet(c, null) }

  fun run(context: Context, argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("bad arguments") }
    val undo = args.optString("undo").trim()
    val text = args.optString("request").trim()
    if (undo.isEmpty() && (text.isEmpty() || text.length > CcHomeCommand.MAX_TEXT)) return error("request is required")
    val app = context.applicationContext
    val s = live(app) ?: return error(ControlCenter.state(app).value.connection.disabledReason ?: "Home Assistant isn't connected")
    if (undo.isNotEmpty()) return undo(app, s, undo)
    return when (val intent = CcHomeCommand.resolve(s, CcHomeRequest(text))) {
      is CcHomeIntent.Unclear -> JSONObject().put("ok", false).put("result", intent.reply)
      is CcHomeIntent.Act -> act(app, s, intent)
    }
  }

  private fun act(app: Context, s: CcUiState, a: CcHomeIntent.Act): JSONObject {
    val routed = CcHomeRoute.split(a.items, s.settings) { ControlCenterConfig.scenesSeen(app) }
    val dry = CcDebugStore.dryRun(app)
    val outcome = send(app, routed.run)
    val visible = onScreen
    routed.confirm.firstOrNull()?.let { askOnScreen(app, s, it, visible) }
    if (visible && (routed.run.isNotEmpty() || a.already > 0) && routed.confirm.isEmpty()) {
      flashes.value = Flash(a.roomIds, a.floorId, seqs.incrementAndGet())
    }
    val reply = CcHomeRoute.reply(a, routed, outcome, dry)
    Log.i(TAG, "home_control ${a.verb.name.lowercase()} ${a.what.name.lowercase()} run=${routed.run.size} " +
        "confirm=${routed.confirm.size} refused=${routed.refused.size} ok=${outcome.ok} failed=${outcome.failed} dry=$dry")
    val out = JSONObject().put("ok", routed.run.isNotEmpty() && outcome.failed < routed.run.size).put("result", reply)
    if (routed.confirm.isNotEmpty()) out.put("needs_confirmation", true)
    if (dry) out.put("dry_run", true)
    if (a.roomIds.isNotEmpty()) out.put("rooms", org.json.JSONArray(a.roomIds.mapNotNull { id -> s.plan?.room(id)?.name ?: s.registry?.areas?.firstOrNull { it.id == id }?.name }))
    CcHomeRoute.card(a, routed)?.takeIf { outcome.ok > 0 }?.let { c ->
      val card = JSONObject().put("type", "ha_action").put("title", c.title).put("state", c.state)
      c.on?.let { card.put("on", it) }
      remember(routed.run)?.let { token -> card.put("undo", JSONObject().put("tool", TOOL).put("args", JSONObject().put("undo", token))) }
      out.put("card", card)
    }
    return out
  }

  /** Sends [items] and waits briefly for Home Assistant's answers (dry run answers at once). */
  private fun send(app: Context, items: List<CcHomeItem>): CcHomeRoute.Outcome {
    if (items.isEmpty()) return CcHomeRoute.Outcome(0, 0, 0)
    val latch = CountDownLatch(items.size)
    val ok = AtomicInteger()
    val failed = AtomicInteger()
    for (item in items) {
      val e = item.entity
      if (e.domain == "scene" || e.domain == "script") {
        if (ControlCenter.runScene(app, e)) ok.incrementAndGet() else failed.incrementAndGet()
        latch.countDown()
        continue
      }
      val sent = ControlCenter.send(app, item.call, undoable = false, onResult = { done, _ ->
        if (done) ok.incrementAndGet() else failed.incrementAndGet()
        latch.countDown()
      })
      if (!sent) { failed.incrementAndGet(); latch.countDown() }
    }
    latch.await(RESULT_WAIT_MS, TimeUnit.MILLISECONDS)
    return CcHomeRoute.Outcome(ok.get(), failed.get(), latch.count.toInt())
  }

  /** Puts the Portal's own confirm sheet up (opening the Control Center if it isn't showing). */
  private fun askOnScreen(app: Context, s: CcUiState, item: CcHomeItem, visible: Boolean) {
    val floor = item.roomId?.let { id -> s.plan?.floors?.firstOrNull { f -> f.rooms.any { it.id == id } }?.id }
    confirms.value = Confirm(item.entity.id, item.call, item.roomId, floor, seqs.incrementAndGet(), System.currentTimeMillis())
    if (!visible) ControlCenterActivity.open(app)
  }

  /** Keeps how to put [run] back, for the card's Undo; null when none of it can be undone. */
  private fun remember(run: List<CcHomeItem>): String? {
    val back = run.mapNotNull { item -> CcCalls.undo(item.entity, item.call)?.let { item.entity to it } }
    if (back.isEmpty()) return null
    val token = UUID.randomUUID().toString().take(TOKEN_CHARS)
    synchronized(undos) { undos[token] = back }
    return token
  }

  private fun undo(app: Context, s: CcUiState, token: String): JSONObject {
    val back = synchronized(undos) { undos.remove(token) } ?: return error("nothing to undo")
    val items = back.map { (before, call) -> CcHomeItem(s.entity(before.id) ?: before, call, CcHomeCommand.roomOf(s, before.id)) }
    val routed = CcHomeRoute.split(items, s.settings) { ControlCenterConfig.scenesSeen(app) }
    val outcome = send(app, routed.run)
    if (onScreen && routed.run.isNotEmpty()) {
      val rooms = routed.run.mapNotNull { it.roomId }.distinct()
      flashes.value = Flash(rooms, rooms.firstNotNullOfOrNull { id -> s.plan?.floors?.firstOrNull { f -> f.rooms.any { it.id == id } }?.id },
          seqs.incrementAndGet())
    }
    val dry = CcDebugStore.dryRun(app)
    return JSONObject().put("ok", outcome.ok > 0).put("result", if (outcome.ok > 0) "Undone." else "Couldn't undo that.")
        .apply { if (dry) put("dry_run", true) }
  }

  /**
   * The live state to act on: Home Assistant connected and the plan's devices known. Holds the
   * live subscription for a moment (the phone's lease) and waits at most [LIVE_WAIT_MS].
   */
  private fun live(app: Context): CcUiState? {
    ControlCenter.remoteWatch(app)
    val flow = ControlCenter.state(app)
    val ready: (CcUiState) -> Boolean = { st -> st.connection.canControl && st.plan != null && st.entities.keys.containsAll(st.plan.entityIds) }
    flow.value.takeIf(ready)?.let { return it }
    val got = runBlocking { withTimeoutOrNull(LIVE_WAIT_MS) { flow.first(ready) } }
    return got ?: flow.value.takeIf { it.connection.canControl }
  }

  private fun error(message: String) = JSONObject().put("error", message)

  const val TOOL = "hearthpane.home_control"
  private const val TAG = "HearthpaneHome"
  /** Jarvis gives a tool 5 s: at most this to come live, then [RESULT_WAIT_MS] for answers. */
  private const val LIVE_WAIT_MS = 2_500L
  private const val RESULT_WAIT_MS = 1_500L
  /** A confirm sheet asked for by voice is shown only this soon after (an old one never pops up later). */
  const val CONFIRM_TTL_MS = 60_000L
  private const val MAX_UNDOS = 8
  private const val TOKEN_CHARS = 12
}
