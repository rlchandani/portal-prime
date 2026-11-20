/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Everything the Home needs to render Who's there?, as one immutable snapshot. */
internal data class WhosThereUi(
    val indicator: WhosThereIndicator = WhosThereIndicator.Hidden,
    val view: WhosThereView = WhosThereView.Shared,
    val people: List<WhosTherePerson> = emptyList(),
    val hidePrivate: Boolean = true,
    val canReveal: Boolean = false,
    /** A glance wants the preview surface: Home mounts the live preview in the indicator. */
    val previewWanted: Boolean = false,
    /** When the latest personal view began (drives the "Hi Maya" chip). */
    val greetedAtMs: Long = 0L,
) {
  fun person(id: String): WhosTherePerson? = people.firstOrNull { it.id == id }
  val owner: WhosTherePerson? get() = (view as? WhosThereView.Personal)?.let { person(it.personId) }
}

/** The coarse part Home itself reads: whether a personal/Family Home is up, and for whom. */
internal data class WhosThereHomeView(val personal: Boolean = false, val names: List<String> = emptyList())

/**
 * The Who's there? runtime. Main-thread state machine around the pure policies: listens to
 * presence, decides with [WhosThereGlancePolicy], runs a [WhosThereGlance] off the main thread,
 * and publishes [WhosThereUi]. The camera is only ever opened from a resumed Home with the screen
 * awake, and closed straight after each glance.
 */
internal object WhosThere {
  private const val TAG = "HearthpaneWhosThere"
  private const val PREVIEW_WAIT_MS = 1_500L

  private val main = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val analysis = Executors.newSingleThreadExecutor { r ->
    Thread(r, "whos-there-analysis").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
  }
  private val ui = MutableStateFlow(WhosThereUi())
  val state: StateFlow<WhosThereUi> = ui.asStateFlow()
  private val home = MutableStateFlow(WhosThereHomeView())
  /** Changes only when whose Home is showing changes (StateFlow drops equal values). */
  val homeView: StateFlow<WhosThereHomeView> = home.asStateFlow()

  @Volatile private var app: Context? = null
  // Main-thread state.
  private var view: WhosThereView = WhosThereView.Shared
  private var homeVisible = false
  private var glance: WhosThereGlance? = null
  private var pendingTrigger: GlanceTrigger? = null
  private var pendingSince = 0L
  private var triggerAt = 0L
  private var lastGlanceEndMs = 0L
  private var lastRecognisedMs = 0L
  private var lastPresence: PresenceState? = null
  private var revealUntilMs = 0L
  private var greetedAtMs = 0L
  private var lastDark = false
  private var lastCovered = false
  private var lastSkip: GlanceSkip? = null
  private var lastTimings: GlanceTimings? = null
  private var lastGreetingMs = -1L
  private var lastBestScore = -1f
  private var notYouHold: Pair<String, Long>? = null
  /** Enrolment holds the camera; glances wait. */
  @Volatile var enrolling = false
  // Analysis-thread state.
  @Volatile private var models: FaceModels? = null
  @Volatile private var modelsError: String? = null
  @Volatile private var modelsLoadMs = -1L

  fun install(context: Context) {
    if (app != null) return
    val c = context.applicationContext
    app = c
    WhosThereStore.init(c)
    PresenceHub.addListener { s -> main.post { onPresence(s) } }
    scope.launch {
      combine(WhosThereStore.settingsState, WhosThereStore.householdState, Calling.state) { _, _, _ -> }.collect {
        ensureModelsIfNeeded()
        if (inCall()) glance?.cancel("call started")
        publish()
      }
    }
  }

  // ---------------------------------------------------------------- inputs from Home

  /** Home in front (resumed, Home tab, not covered by full-screen Music). */
  fun setHomeVisible(visible: Boolean) {
    if (homeVisible == visible) return
    homeVisible = visible
    val now = SystemClock.elapsedRealtime()
    if (!visible) {
      hiddenSince = now
      glance?.cancel("home left")
      clearPending()
      return
    }
    // Back on Home after a while away (the dream ended, the screen woke) with someone here.
    if (hiddenSince > 0L) WhosThereGlancePolicy.onReturn(now - hiddenSince, PresenceHub.current)
        ?.let { main.post { requestGlance(it) } }
  }

  private var hiddenSince = 0L

  private var preview: Surface? = null
  private var glanceSurface: Surface? = null

  /**
   * The indicator keeps its small preview mounted while recognition is ready, so a glance starts
   * without creating views mid-frame. A pending glance starts as soon as a surface exists.
   */
  fun onPreviewAvailable(surface: Surface) {
    preview = surface
    if (pendingTrigger != null && glance == null) startGlance(surface)
  }

  fun onPreviewGone(surface: Surface) {
    if (preview === surface) preview = null
    if (glanceSurface === surface) glance?.cancel("preview gone")
  }

  private fun startGlance(surface: Surface) {
    val trigger = pendingTrigger ?: return
    val c = app ?: return
    val m = models ?: return clearPending()
    if (glance != null) return
    val templates = WhosThereStore.householdState.value.let { h ->
      h.templates.filter { t -> h.person(t.personId)?.recognisableAt(System.currentTimeMillis()) == true }
    }
    Log.i(TAG, "glance start trigger=$trigger")
    glanceSurface = surface
    glance = WhosThereGlance(c, surface, m, templates, analysis, rotations(c),
        onEarly = { o -> main.post { onGlanceEarly(o) } }, onDone = { r -> main.post { onGlanceDone(r) } }).also { it.start() }
  }

  fun requestGlance(trigger: GlanceTrigger, operator: Boolean = false): String {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      main.post { requestGlance(trigger, operator) }
      return "posted"
    }
    val c = app ?: return "not installed"
    val decision = WhosThereGlancePolicy.decide(inputs(c, trigger))
    if (decision is GlanceDecision.Skip) {
      lastSkip = decision.reason
      if (trigger != GlanceTrigger.ARRIVAL || decision.reason != GlanceSkip.OFF) Log.i(TAG, "glance skipped: ${decision.reason.log} (${trigger.name.lowercase()})")
      return "skipped: ${decision.reason.log}"
    }
    pendingTrigger = trigger
    lastGreetingMs = -1L
    pendingSince = SystemClock.elapsedRealtime()
    triggerAt = pendingSince
    publish()
    preview?.let { s -> main.post { if (pendingTrigger != null && glance == null && preview === s) startGlance(s) } }
    main.postDelayed({ if (pendingTrigger != null && glance == null && SystemClock.elapsedRealtime() - pendingSince >= PREVIEW_WAIT_MS) clearPending() }, PREVIEW_WAIT_MS)
    return if (operator) "started (operator)" else "started"
  }

  // ---------------------------------------------------------------- user actions

  /** "Not you?": pick the right person, or null for "Someone else" (shared Home). Never retrains. */
  fun correct(personId: String?) {
    val c = app ?: return
    val shown = (view as? WhosThereView.Personal)?.personId
    WhosThereStore.record(c, AuditKind.NOT_YOU)
    view = if (personId == null) WhosThereView.Shared else WhosThereView.Personal(personId, othersNear = true)
    if (shown != null && shown != personId) notYouHold = shown to System.currentTimeMillis() + WhosThereViewPolicy.NOT_YOU_HOLD_MS
    revealUntilMs = 0L
    publish()
  }

  /** "Is this you?" → Yes. Confirms for this visit only; templates are untouched. */
  fun confirm() {
    val v = view as? WhosThereView.Personal ?: return
    view = v.copy(confirm = false)
    lastRecognisedMs = System.currentTimeMillis()
    publish()
  }

  /** "Show mine" from the Family view: their Home, private still blurred. */
  fun showMine(personId: String) {
    view = WhosThereView.Personal(personId, othersNear = true)
    revealUntilMs = 0L
    publish()
  }

  /** The owner taps Show on a blurred card. */
  fun reveal() {
    val c = app ?: return
    if (!WhosTherePrivacy.canReveal(privacy(c))) return
    revealUntilMs = System.currentTimeMillis() + WhosThereViewPolicy.REVEAL_MS
    publish()
    main.postDelayed({ publish() }, WhosThereViewPolicy.REVEAL_MS + 50)
  }

  /** "Don't recognise me for 1 hour" for whoever's Home is showing (or the household). */
  fun pauseShown(durationMs: Long) {
    val c = app ?: return
    val until = System.currentTimeMillis() + durationMs
    val shown = (view as? WhosThereView.Personal)?.personId
    if (shown != null) {
      WhosThereStore.updateHousehold(c, { h -> h.person(shown)?.let { h.upsert(it.copy(pausedUntilMs = until)) } ?: h })
    } else {
      WhosThereStore.updateSettings(c) { it.copy(pausedUntilMs = until) }
    }
    WhosThereStore.record(c, AuditKind.PAUSED)
    view = WhosThereView.Shared
    publish()
    main.postDelayed({ publish() }, durationMs + 50)
  }

  fun resume() {
    val c = app ?: return
    WhosThereStore.updateSettings(c) { it.copy(pausedUntilMs = 0L) }
  }

  // ---------------------------------------------------------------- internals

  private fun onPresence(next: PresenceState) {
    val prev = lastPresence
    lastPresence = next
    if (next.screen != ScreenState.INTERACTIVE) {
      glance?.cancel("screen ${next.screen.name.lowercase()}")
      clearPending()
      if (view != WhosThereView.Shared) { view = WhosThereView.Shared; publish() }
      return
    }
    if (next.presence == Presence.ABSENT && next.confident && view != WhosThereView.Shared) {
      val since = next.sinceMs
      main.postDelayed({
        val now = lastPresence
        if (now != null && now.presence == Presence.ABSENT && now.sinceMs == since) {
          view = WhosThereView.Shared; publish()
        }
      }, WhosThereViewPolicy.LEAVE_AFTER_MS)
    }
    if (prev == null) return
    WhosThereGlancePolicy.trigger(prev, next, System.currentTimeMillis())?.let { requestGlance(it) }
  }

  /** Greet as soon as one face is clearly someone; the full look still runs (a second face blurs). */
  private fun onGlanceEarly(o: GlanceOutcome) {
    if (glance == null) return
    val now = System.currentTimeMillis()
    val next = held(WhosThereViewPolicy.update(view, o, WhosThereStore.householdState.value.people, now), now)
    if (next !is WhosThereView.Personal) return
    if ((view as? WhosThereView.Personal)?.personId != next.personId) greetedAtMs = now
    view = next
    lastRecognisedMs = now
    lastGreetingMs = SystemClock.elapsedRealtime() - triggerAt
    publish()
    Log.i(TAG, "glance early verdict after ${o.framesAnalysed} frames glance→greeting=${lastGreetingMs}ms")
  }

  private fun onGlanceDone(r: GlanceResult) {
    val c = app ?: return
    glance = null
    glanceSurface = null
    pendingTrigger = null
    lastGlanceEndMs = System.currentTimeMillis()
    lastTimings = r.timings
    lastDark = r.dark
    lastCovered = r.covered
    val now = System.currentTimeMillis()
    val people = WhosThereStore.householdState.value.people
    if (r.failed == null || r.outcome.framesAnalysed > 0) {
      val next = held(WhosThereViewPolicy.update(view, r.outcome, people, now), now)
      val newlyPersonal = next is WhosThereView.Personal && (view as? WhosThereView.Personal)?.personId != next.personId
      if (newlyPersonal || (next is WhosThereView.Family && view !is WhosThereView.Family)) greetedAtMs = now
      if (next is WhosThereView.Personal && !next.confirm || next is WhosThereView.Family) lastRecognisedMs = now
      view = next
      WhosThereStore.record(c, WhosThereAudit.kindFor(next, r.outcome), r.outcome.faceCount)
    }
    publish()
    if (lastGreetingMs < 0) lastGreetingMs = SystemClock.elapsedRealtime() - triggerAt // no early verdict this time
    // Ids and counts only; the outcome holds no embeddings.
    Log.i(TAG, "glance done faces=${r.outcome.faceCount} recognised=${r.outcome.recognised.size} " +
        "unsure=${r.outcome.unsure != null} view=${view.javaClass.simpleName} luma=${"%.0f".format(r.meanLuma)} " +
        "failed=${r.failed} best=${"%.3f".format(r.bestScore)} rot=${r.rotation} ${r.timings.log()} glance→greeting=${lastGreetingMs}ms")
    lastBestScore = r.bestScore
  }

  /** After "Not you?", the corrected-away person isn't put straight back. */
  private fun held(next: WhosThereView, now: Long): WhosThereView {
    val (id, until) = notYouHold ?: return next
    if (now >= until) { notYouHold = null; return next }
    return if (next is WhosThereView.Personal && next.personId == id) WhosThereView.Shared else next
  }

  private fun clearPending() {
    if (pendingTrigger == null) return
    pendingTrigger = null
    publish()
  }

  private fun ensureModelsIfNeeded() {
    val s = WhosThereStore.settingsState.value
    val wanted = s.enabled && WhosThereStore.householdState.value.enrolled.isNotEmpty()
    if (wanted) { ensureModels {}; return }
    // Switched off or nobody left: stop any glance now, then free the models — unless enrolment
    // or a glance still owns them (the close is queued behind their work on the same thread).
    glance?.cancel("recognition off")
    if (!enrolling && glance == null) analysis.execute { if (!enrolling) { models?.close(); models = null } }
  }

  /** Loads the models on the analysis thread (idempotent); [then] runs on main afterwards. */
  fun ensureModels(then: () -> Unit) {
    val c = app ?: return
    analysis.execute {
      if (models == null && modelsError == null) {
        when (val loaded = FaceModels.load(c)) {
          is FaceModels.Companion.Loaded.Ready -> { models = loaded.models; modelsLoadMs = loaded.loadMs }
          is FaceModels.Companion.Loaded.Failed -> modelsError = loaded.message
        }
        Log.i(TAG, "models ready=${models != null} load=${modelsLoadMs}ms")
      }
      main.post { publish(); then() }
    }
  }

  /** For enrolment: the models and the thread that owns them. */
  fun modelsForEnrolment(): Pair<FaceModels, java.util.concurrent.ExecutorService>? = models?.let { it to analysis }
  fun modelsUnavailable(): String? = modelsError

  private fun inCall(): Boolean {
    if (Calling.state.value.busy) return true
    val c = app ?: return false
    val mode = runCatching { c.getSystemService(AudioManager::class.java)?.mode }.getOrNull()
    return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || DreamPolicy.inStockHandoff
  }

  private fun quiet(c: Context): Boolean {
    val s = runCatching { ChimeConfig.load(c) }.onFailure { Log.w(TAG, "quiet hours unreadable; treating as quiet") }
        .getOrNull() ?: return true // unreadable: fail safe, never glance
    return ChimeConfig.inQuietWindow(minuteNow(), s.quietStartMin, s.quietEndMin)
  }

  private fun minuteNow(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

  private fun inputs(c: Context, trigger: GlanceTrigger): GlanceInputs {
    val s = WhosThereStore.settingsState.value
    // The display itself, not the presence hub's last screen event (which can lag a dream exit).
    val awake = runCatching { c.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull() == true
    return GlanceInputs(trigger, System.currentTimeMillis(), s.enabled, WhosThereStore.householdState.value.enrolled.size,
        models != null, WhosThereCamera.granted(c), s.pausedUntilMs, quiet(c), WhosThereDebug.ignoreQuietHours, inCall(),
        homeVisible, awake, glance != null || pendingTrigger != null || enrolling, lastGlanceEndMs, lastRecognisedMs)
  }

  private fun privacy(c: Context): WhosTherePrivacy.Context {
    val h = WhosThereStore.householdState.value
    val owner = (view as? WhosThereView.Personal)?.let { h.person(it.personId) }
    return WhosTherePrivacy.Context(view, owner, inCall(), WhosThereStore.settingsState.value.hidePrivateNear,
        revealUntilMs, System.currentTimeMillis())
  }

  private fun publish() {
    val c = app ?: return
    val s = WhosThereStore.settingsState.value
    val h = WhosThereStore.householdState.value
    val now = System.currentTimeMillis()
    // A person deleted or paused while their Home is up drops back to shared.
    view = when (val v = view) {
      is WhosThereView.Personal -> if (h.person(v.personId)?.recognisableAt(now) == true) v else WhosThereView.Shared
      is WhosThereView.Family -> v.personIds.filter { h.person(it)?.recognisableAt(now) == true }.let {
        when (it.size) { 0 -> WhosThereView.Shared; 1 -> WhosThereView.Personal(it[0], othersNear = true); else -> WhosThereView.Family(it) }
      }
      WhosThereView.Shared -> v
    }
    if (!s.enabled) view = WhosThereView.Shared
    val chime = runCatching { ChimeConfig.load(c) }.getOrNull()
    val indicator = WhosThereIndicatorPolicy.of(IndicatorInputs(s.enabled, h.enrolled.size, modelsError,
        looking = glance != null || pendingTrigger != null, view = view, pausedUntilMs = s.pausedUntilMs, inCall = inCall(),
        quietHours = quiet(c), debugIgnoreQuiet = WhosThereDebug.ignoreQuietHours, quietEndMinute = chime?.quietEndMin ?: 480,
        lastGlanceDark = lastDark, lastGlanceCovered = lastCovered, nowMs = now))
    val p = privacy(c)
    ui.value = WhosThereUi(indicator, view, h.people, WhosTherePrivacy.hidePrivate(p), WhosTherePrivacy.canReveal(p),
        previewWanted = pendingTrigger != null || glance != null, greetedAtMs = greetedAtMs)
    home.value = WhosThereHomeView(view != WhosThereView.Shared,
        WhosThereViewPolicy.people(view).mapNotNull { id -> h.person(id)?.name })
  }

  /** Expected rotations to make faces upright: none in landscape; the Mini upright tries both turns. */
  private fun rotations(c: Context): List<Int> =
      if (c.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) listOf(270, 90, 0) else listOf(0)

  /** Operator-only (see [WhosThereDebug]): show a view for the enrolled people without a glance. */
  fun simulate(kind: String) {
    if (Looper.myLooper() != Looper.getMainLooper()) { main.post { simulate(kind) }; return }
    val ids = WhosThereStore.householdState.value.enrolled.map { it.id }
    val first = ids.firstOrNull() ?: return
    view = when (kind) {
      "personal" -> WhosThereView.Personal(first, othersNear = false)
      "near" -> WhosThereView.Personal(first, othersNear = true)
      "confirm" -> WhosThereView.Personal(first, othersNear = false, confirm = true)
      "family" -> if (ids.size >= 2) WhosThereView.Family(ids.take(2)) else WhosThereView.Personal(first, true)
      else -> WhosThereView.Shared
    }
    if (view != WhosThereView.Shared) greetedAtMs = System.currentTimeMillis()
    publish()
  }

  fun benchmark(): JSONObject {
    val out = JSONObject()
    val m = models ?: return out.put("error", modelsError ?: "models not loaded")
    return runCatching {
      val (det, emb) = analysis.submit(Callable { m.benchmark() }).get(20, TimeUnit.SECONDS)
      out.put("detectMs", det).put("embedMs", emb).put("loadMs", modelsLoadMs).put("input", "synthetic xor texture, no faces")
    }.getOrElse { out.put("error", it.javaClass.simpleName) }
  }

  fun debugStatus(): JSONObject {
    val u = ui.value
    return JSONObject().put("enabled", WhosThereStore.settingsState.value.enabled)
        .put("enrolled", WhosThereStore.householdState.value.enrolled.size)
        .put("models", models != null).put("modelsError", modelsError ?: JSONObject.NULL).put("modelsLoadMs", modelsLoadMs)
        .put("indicator", u.indicator.javaClass.simpleName).put("view", u.view.javaClass.simpleName)
        .put("homeVisible", homeVisible).put("lastSkip", lastSkip?.log ?: JSONObject.NULL)
        .put("lastGlance", lastTimings?.log() ?: JSONObject.NULL).put("glanceToGreetingMs", lastGreetingMs)
        .put("lastBestScore", lastBestScore.toDouble())
  }
}
