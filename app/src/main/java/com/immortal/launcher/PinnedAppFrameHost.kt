/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject

/** A photo curtain over the existing app window, never another Activity/task or WebView. */
internal class PinnedAppFrameHost(private val service: AccessibilityService) : PinnedScreensaverHost {
    private val policy = PinnedCoverPolicy()
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var surface: Cover? = null
    private var nativeCover = false
    private var closed = false
    private var generation = 0L
    private var nativeDetachGraceUntil = 0L
    private var pendingReveal: PendingReveal? = null
    private val homePackages by lazy {
        service.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .mapNotNull { it.activityInfo?.packageName }.toSet()
    }
    private var label = ""
    private var nativeAttachCount = 0
    private var nativeTapCount = 0
    private var shownCount = 0
    private var dismissedCount = 0
    private var lastHideDispatchMicros = 0L
    private var lastCleanupMicros = 0L
    private val retirement = PinnedSurfaceRetirement<View>(
        hide = { it.visibility = View.GONE },
        remove = { wm.removeViewImmediate(it) },
        attached = { it.isAttachedToWindow },
    )
    private val cleanups = mutableMapOf<PinnedSurfaceRetirement.Ticket<View>, RetirementWork>()
    override val pinned get() = policy.pinned
    override val covered get() = surface != null

    private class Cover(val root: FrameLayout, val frame: PhotoFrameController?,
        var mode: PinnedCoverMode, val params: WindowManager.LayoutParams, val generation: Long) {
        val renderer = PinnedRendererLifetime { frame?.onExit = null; frame?.stop() }
        var receiver: BroadcastReceiver? = null
        var nightTick: Runnable? = null
        var lostPanel = false
    }

    private fun identities(windows: List<PinAppWindow>) = windows.map {
        PinnedWindowIdentity(it.packageName, it.windowId)
    }
    private fun current(windows: List<PinAppWindow>) =
        windows.firstOrNull { it.focused } ?: windows.firstOrNull { it.active }
    private fun identity(window: PinAppWindow?) = window?.let {
        PinnedWindowIdentity(it.packageName, it.windowId)
    }

    private fun eligible(window: PinAppWindow?): Boolean {
        val pkg = window?.packageName ?: return false
        if (pkg == service.packageName || pkg == "android" || pkg == "com.android.systemui" ||
            pkg.startsWith("com.facebook.portal")) return false
        return pkg !in homePackages
    }

    override fun pinCurrent(): Boolean {
        if (closed || covered || nativeCover || ImmortalDreamNavigation.hasOwnedSurface()) return false
        val window = current(readPinAppWindows(service))
        if (!eligible(window)) return false
        val selected = window ?: return false
        if (!policy.pin(PinnedWindowIdentity(selected.packageName, selected.windowId))) return false
        nativeDetachGraceUntil = 0L
        cancelReveal()
        label = runCatching {
            service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(selected.packageName, 0)).toString()
        }.getOrDefault("App")
        QuickBar.setPinState(true, true, false)
        Toast.makeText(service, "$label pinned. Tap the screensaver to return here.", Toast.LENGTH_SHORT).show()
        return true
    }

    override fun markSessionExpired() {
        if (closed) return
        policy.expire(identity(current(readPinAppWindows(service))))
        cancelReveal()
        surface?.let { showLostSession(it) }
        windowsChanged()
    }

    override fun release() {
        policy.release()
        nativeCover = false
        nativeDetachGraceUntil = 0L
        cancelReveal()
        removeSurface()
        label = ""
        if (!closed) windowsChanged()
    }

    override fun windowsChanged() {
        if (closed) return
        val windows = readPinAppWindows(service)
        val foreground = current(windows)
        // These are actual TYPE_APPLICATION roots. Native TYPE_SYSTEM Dreams and our
        // TYPE_ACCESSIBILITY_OVERLAY are excluded by the reader, not by pin eligibility.
        if (policy.observe(identities(windows), identity(foreground), covered = nativeCover || covered)) {
            cancelReveal()
            // Policy has cleared its guard before finish/remove can reenter window observation.
            DreamPolicy.userExitAt = System.currentTimeMillis()
            PinnedAppScreensaver.navigation { true }
            return
        }
        if (policy.lost) {
            cancelReveal()
            surface?.let { showLostSession(it) }
        }
        QuickBar.setPinState(eligible(foreground), pinned, covered || nativeCover)
    }

    override fun nativeDreamAttached() {
        if (closed || !pinned) return
        nativeCover = true
        nativeAttachCount++
        nativeDetachGraceUntil = 0L
        cancelReveal()
        removeSurface() // retire only this captured overlay; native Dream now owns the frame
    }

    override fun nativeDreamTapped(): Boolean {
        if (closed) return false
        if (!pinned) return true
        nativeTapCount++
        // A known-lost native session may finish only after its replacement barrier attaches.
        return !policy.lost || showFrame()
    }

    override fun nativeDreamDetached() {
        if (closed) return
        nativeCover = false
        // Family hardware can briefly omit the original root while the native Dream detaches.
        // Observation preserves unknown; only a requested reveal gets this finite settling grace.
        nativeDetachGraceUntil = SystemClock.uptimeMillis() + 200L
        windowsChanged()
    }

    override fun showFrame(): Boolean {
        if (closed || !pinned) return false
        windowsChanged()
        if (!pinned) return false
        val cfg = ScreensaverConfig.load(service)
        var selection = policy.select(SleepScheduler.isOvernightNow(service), cfg.overnightNightClock)
        if (selection == null) {
            // A dark-window transition may remove a valid cover, but may not expose a lost app.
            if (!covered) return false
            if (policy.reveal(identities(readPinAppWindows(service)), SystemClock.uptimeMillis(), 0L) ==
                PinnedRevealDecision.REVEAL) {
                removeSurface()
                return false
            }
            selection = policy.select(SleepScheduler.isOvernightNow(service), cfg.overnightNightClock)
                ?: return false
        }
        val old = surface
        // Select day/night before reuse. A closed renderer behind a lost panel is never restarted.
        if (old != null && (old.mode == selection.mode || selection.lostBarrier)) {
            old.mode = selection.mode
            if (selection.lostBarrier) showLostSession(old)
            reconcilePower(old, cfg)
            scheduleNightTick(old)
            return true
        }
        if (!retirement.canCreateCover) return false
        removeSurface()
        if (!retirement.canCreateCover) return false
        val next = if (selection.lostBarrier) null else PhotoFrameController(service, interactivePreview = false)
        if (selection.mode == PinnedCoverMode.NIGHT) next?.faceOverride = Face.flipNight(service)
        val root = if (next != null) next.view as FrameLayout else FrameLayout(service)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags, PixelFormat.OPAQUE).apply { gravity = Gravity.TOP or Gravity.LEFT }
        val cover = Cover(root, next, selection.mode, params, ++generation)
        setPowerParams(cover, cfg)
        root.setOnTouchListener { _, event ->
            if (surface === cover && !cover.renderer.stopped) next?.onTouch(event)
            true // the wake tap belongs to the curtain, never to the app underneath
        }
        next?.onExit = { if (surface === cover && !closed) revealCover() }
        if (selection.lostBarrier) showLostSession(cover)
        return try {
            wm.addView(root, params)
            surface = cover
            shownCount++
            next?.start()
            registerPowerReceiver(cover)
            scheduleNightTick(cover)
            if (cover.mode == PinnedCoverMode.DAY && !cover.lostPanel) SleepScheduler.onScreensaverStarted(service)
            QuickBar.setPinState(false, true, true)
            true
        } catch (_: Exception) {
            if (surface === cover) surface = null
            retireCover(cover) // addView can fail after partial attachment; retain uncertain ownership
            false
        }
    }

    private fun setPowerParams(cover: Cover, cfg: ScreensaverConfig.Settings) {
        val keep = PinnedCoverPolicy.keepScreenOn(cover.mode, cfg.presenceMode,
            DreamPolicy.hasBattery(service), cfg.batterySaver, DreamPolicy.isPowered(service))
        val bit = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        cover.params.flags = if (keep) cover.params.flags or bit else cover.params.flags and bit.inv()
        cover.params.screenBrightness = if (cover.mode == PinnedCoverMode.NIGHT)
            PhotoFramePreviewActivity.NIGHT_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }

    private fun reconcilePower(cover: Cover, cfg: ScreensaverConfig.Settings = ScreensaverConfig.load(service)) {
        if (closed || surface !== cover) return
        setPowerParams(cover, cfg)
        runCatching { wm.updateViewLayout(cover.root, cover.params) }
    }

    private fun registerPowerReceiver(cover: Cover) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { reconcilePower(cover) }
        }
        cover.receiver = receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // Sticky battery state also reconciles Portal+ (no battery), not just charging devices.
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                service.registerReceiver(receiver, filter)
            }
        }
    }

    private fun scheduleNightTick(cover: Cover) {
        cover.nightTick?.let { main.removeCallbacks(it) }
        cover.nightTick = null
        if (cover.mode != PinnedCoverMode.NIGHT || surface !== cover || closed) return
        val tick = Runnable {
            if (surface !== cover || closed) return@Runnable
            windowsChanged()
            if (surface !== cover || !pinned) return@Runnable
            if (!SleepScheduler.isOvernightNow(service)) {
                val decision = policy.reveal(identities(readPinAppWindows(service)), SystemClock.uptimeMillis(), 0L)
                if (decision == PinnedRevealDecision.REVEAL) {
                    removeSurface() // normal morning return keeps the valid pin
                    QuickBar.setPinState(false, pinned, nativeCover)
                } else {
                    // Morning is not permission to uncover a replacement or an expired session.
                    cover.mode = PinnedCoverMode.DAY
                    showLostSession(cover)
                    reconcilePower(cover)
                    cover.nightTick = null
                }
            } else scheduleNightTick(cover)
        }
        cover.nightTick = tick
        main.postDelayed(tick, 60_000L)
    }

    override fun revealCover(): Boolean {
        val cover = surface ?: return false
        if (closed) return false
        pendingReveal?.let { if (it.cover === cover && it.revision == policy.revision) return true }
        cancelReveal()
        val request = PendingReveal(cover, policy.revision, nativeDetachGraceUntil)
        pendingReveal = request
        return attemptReveal(request)
    }

    private inner class PendingReveal(val cover: Cover, val revision: Long, val untilMs: Long) : Runnable {
        override fun run() { attemptReveal(this) }
    }

    private fun attemptReveal(request: PendingReveal): Boolean {
        if (closed || pendingReveal !== request || surface !== request.cover ||
            surface?.generation != request.cover.generation || policy.revision != request.revision) {
            if (pendingReveal === request) cancelReveal()
            return false
        }
        windowsChanged()
        if (pendingReveal !== request || surface !== request.cover) return false
        val now = SystemClock.uptimeMillis()
        return when (policy.reveal(identities(readPinAppWindows(service)), now, request.untilMs, request.revision)) {
            PinnedRevealDecision.WAIT -> {
                main.removeCallbacks(request)
                main.postDelayed(request, minOf(50L, request.untilMs - now))
                true // one admitted reveal; its captured root/revision cannot apply to another cover
            }
            PinnedRevealDecision.REVEAL -> {
                cancelReveal()
                DreamPolicy.userExitAt = System.currentTimeMillis()
                PresenceHub.onInteraction(service)
                SleepScheduler.onReturnedToApp(service)
                removeSurface()
                dismissedCount++
                windowsChanged()
                true
            }
            PinnedRevealDecision.LOST -> {
                cancelReveal()
                showLostSession(request.cover)
                false
            }
            else -> { cancelReveal(); false }
        }
    }

    private fun cancelReveal() {
        pendingReveal?.let { main.removeCallbacks(it) }
        pendingReveal = null
    }

    /** A lost session never invokes the configured dismiss-app launch as a fallback. */
    private fun showLostSession(cover: Cover) {
        if (cover.lostPanel) return
        cover.lostPanel = true
        val panel = pinnedSessionLostPanel(service) {
            if (!closed && surface === cover) service.openApprovedHomeTab(ApprovedLauncherTab.HOME)
        }
        cover.root.addView(panel, FrameLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
        cover.root.setOnTouchListener(null)
        cover.renderer.stopOnce()
    }

    private fun removeSurface() {
        val old = surface ?: return
        surface = null // revoke callbacks before hide/detach can generate window events
        cancelReveal()
        val began = SystemClock.elapsedRealtimeNanos()
        retireCover(old)
        lastHideDispatchMicros = (SystemClock.elapsedRealtimeNanos() - began) / 1_000L
    }

    private fun retireCover(old: Cover) {
        val ticket = retirement.retire(old.root, old.renderer) // GONE before any deferred destruction
        old.nightTick?.let { main.removeCallbacks(it) }
        old.nightTick = null
        old.receiver?.let { runCatching { service.unregisterReceiver(it) } }
        old.receiver = null
        old.frame?.onExit = null
        if (cleanups.containsKey(ticket)) return
        val work = RetirementWork(ticket)
        cleanups[ticket] = work
        old.root.addOnAttachStateChangeListener(work)
        if (!closed) work.afterHide() // close schedules its single final pass below
    }

    private inner class RetirementWork(val ticket: PinnedSurfaceRetirement.Ticket<View>) :
        Runnable, View.OnAttachStateChangeListener {
        private val afterAnimation = Runnable {
            if (retirement.owns(ticket)) main.post(this)
        }
        fun afterHide() {
            ticket.surface.postOnAnimation(afterAnimation)
            main.postDelayed(this, 100L) // detached/no-vsync fallback, never an unbounded poll
        }
        fun cancelSchedule() {
            main.removeCallbacks(this)
            ticket.surface.removeCallbacks(afterAnimation)
        }
        private fun finished() {
            cancelSchedule()
            ticket.surface.removeOnAttachStateChangeListener(this)
            cleanups.remove(ticket)
        }
        override fun run() {
            cancelSchedule()
            val began = SystemClock.elapsedRealtimeNanos()
            val result = if (closed) retirement.drainOnce(ticket) else retirement.attempt(ticket)
            lastCleanupMicros = (SystemClock.elapsedRealtimeNanos() - began) / 1_000L
            when (result) {
                PinnedRetirementResult.DONE, PinnedRetirementResult.IGNORED -> {
                    if (!retirement.owns(ticket)) finished()
                }
                PinnedRetirementResult.RETRY -> main.postDelayed(this, if (ticket.attempts == 1) 200L else 400L)
                PinnedRetirementResult.FAILED -> Unit // retain owner/listener/count, with no further timer
            }
        }
        override fun onViewAttachedToWindow(view: View) {}
        override fun onViewDetachedFromWindow(view: View) {
            // Android may clear AttachInfo after this callback returns; check once after dispatch.
            main.post {
                if (retirement.detached(ticket)) finished()
            }
        }
    }

    /** Call after manager detach on service unbind. One final deferred pass per captured window. */
    fun close() {
        if (closed) return
        closed = true
        release()
        cleanups.values.toList().forEach {
            it.cancelSchedule()
            it.afterHide()
        }
    }

    override fun status(): JSONObject {
        val windows = readPinAppWindows(service)
        val foreground = current(windows)
        return JSONObject().put("ok", true).put("available", !closed).put("pinned", pinned).put("covered", covered)
            .put("nativeCover", nativeCover).put("lost", policy.lost).put("expired", policy.expired).put("label", label)
            .put("targetPackage", policy.target?.packageName ?: JSONObject.NULL)
            .put("targetWindowId", policy.target?.windowId ?: JSONObject.NULL)
            .put("foregroundPackage", foreground?.packageName ?: JSONObject.NULL)
            .put("foregroundWindowId", foreground?.windowId ?: JSONObject.NULL)
            .put("targetWindowPresent", policy.target != null && policy.target in identities(windows))
            .put("nativeAttachCount", nativeAttachCount).put("nativeTapCount", nativeTapCount)
            .put("shownCount", shownCount).put("dismissedCount", dismissedCount)
            .put("lastHideDispatchMicros", lastHideDispatchMicros)
            .put("lastCleanupMicros", lastCleanupMicros).put("pendingCleanupCount", retirement.pendingCount)
            .put("failedCleanupCount", retirement.failedCount).put("revealPending", pendingReveal != null)
            .put("pinControl", QuickBar.pinControlStatus())
    }
}
