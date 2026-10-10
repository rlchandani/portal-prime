/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Installed only while the provisioned accessibility service owns its windows. */
internal interface PinnedScreensaverHost {
    val pinned: Boolean
    val covered: Boolean
    fun pinCurrent(): Boolean
    fun release()
    fun showFrame(): Boolean
    fun windowsChanged()
    fun nativeDreamAttached()
    fun nativeDreamDetached()
    fun nativeDreamTapped(): Boolean = true
    fun markSessionExpired() {}
    fun revealCover(): Boolean = false
    fun status(): JSONObject
}

/** No persisted tasks, app launch intents, shell calls or network on the return path. */
internal object PinnedAppScreensaver {
    @Volatile private var owner: PinnedScreensaverHost? = null
    @Volatile private var expiredPin = false
    fun attach(host: PinnedScreensaverHost) {
        val old = owner
        expiredPin = expiredPin || old?.pinned == true
        old?.release()
        owner = host
        if (expiredPin) host.markSessionExpired()
    }
    fun detach(host: PinnedScreensaverHost) {
        if (owner !== host) return
        expiredPin = expiredPin || host.pinned
        owner = null
        host.release()
    }
    fun hasPin(): Boolean = expiredPin || owner?.pinned == true
    fun hasFrame(): Boolean = owner?.covered == true
    fun release() { expiredPin = false; owner?.release() }
    fun windowsChanged() { owner?.windowsChanged() }
    fun nativeDreamAttached() { owner?.nativeDreamAttached() }
    fun nativeDreamDetached() { owner?.nativeDreamDetached() }
    fun nativeDreamTapped(): Boolean = owner?.nativeDreamTapped() ?: !expiredPin
    fun showFrame(): Boolean = owner?.takeIf { it.pinned }?.showFrame() == true
    fun pinCurrent(): Boolean {
        val applied = owner?.pinCurrent() == true
        if (applied) expiredPin = false
        return applied
    }
    fun status(): JSONObject {
        val result = owner?.status() ?: JSONObject().put("ok", true).put("available", false).put("pinned", false)
        if (expiredPin) result.put("pinned", true).put("lost", true).put("expired", true)
        return result
    }

    /** Choose and launch on main together: no worker-side stale 'unpinned' decision. */
    fun selectFrame(legacyLaunch: () -> Boolean): JSONObject = onMain {
        if (hasPin()) {
            val shown = showFrame()
            status().put("ok", shown).put("result", if (shown) "shown" else "pinned_return_unavailable")
        } else JSONObject().put("ok", legacyLaunch()).put("result", "legacy_frame_requested")
    }

    /** Mark intentional exit before cleanup/finish, then launch in the same admitted action. */
    fun navigation(launch: () -> Boolean): JSONObject = onMain {
        if (hasPin() || ImmortalDreamNavigation.hasOwnedSurface())
            DreamPolicy.userExitAt = System.currentTimeMillis()
        ImmortalDreamNavigation.dismissForNavigation()
        JSONObject().put("ok", launch()).put("result", "navigation_requested")
    }

    /** A curtain appearing before admission owns Back; never send the tap underneath it. */
    fun back(legacyBack: () -> Boolean): JSONObject = onMain {
        if (hasFrame()) {
            val revealed = owner?.revealCover() == true
            status().put("ok", revealed).put("result", "covered_dismissed")
        } else JSONObject().put("ok", legacyBack()).put("result", "back_requested")
    }

    /** Admit a remote command on main. A queued timeout can never apply later. */
    fun command(action: String): JSONObject = onMain {
        val success = when (action) {
            "pin" -> pinCurrent()
            "unpin" -> { release(); true }
            "show" -> showFrame()
            "dismiss" -> owner?.takeIf { it.covered }?.revealCover() == true
            else -> false
        }
        status().put("ok", success)
    }

    fun readStatus(): JSONObject = onMain { status() }

    private fun onMain(action: () -> JSONObject): JSONObject {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val request = PinnedMainRequest(action)
        val handler = Handler(Looper.getMainLooper())
        if (!handler.post(request)) return JSONObject().put("ok", false).put("error", "main_unavailable")
        return try { request.await() } finally { handler.removeCallbacks(request) }
    }
}

internal class PinnedCommandPending : RuntimeException()

/** Tests exercise cancellation vs admission, not a sleep-dependent approximation. */
internal class PinnedMainRequest(private val action: () -> JSONObject) : Runnable {
    private val lock = Any()
    private val done = CountDownLatch(1)
    private var started = false
    private var cancelled = false
    private var result: JSONObject? = null
    override fun run() {
        synchronized(lock) {
            if (cancelled || started) return
            started = true
        }
        val value = runCatching { action() }.getOrElse {
            JSONObject().put("ok", false).put("error", "pin_command_failed")
        }
        synchronized(lock) { result = value }
        done.countDown()
    }
    fun await(timeoutMs: Long = 2_000L): JSONObject {
        try { done.await(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return synchronized(lock) {
            result ?: if (started) throw PinnedCommandPending() else {
                cancelled = true
                JSONObject().put("ok", false).put("error", "main_timeout")
            }
        }
    }
}
