/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.lang.ref.WeakReference
import java.util.UUID
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Test-APK-only IPC. The signed debug target admits one random per-run instrumentation nonce;
 * a genuine MAIN/LAUNCHER Activity entry can create a separate private manual nonce. Signed debug
 * callers alone may discover that manual session; state/cleanup commands require its exact nonce. It exposes only our synthetic document/lifecycle; no
 * arbitrary URI, JavaScript, component, file, screenshot, accessibility node or shell command.
 */
class PinFixtureProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = checkNotNull(context)
        val uid = Binder.getCallingUid()
        val callers = ctx.packageManager.getPackagesForUid(uid).orEmpty()
        check(TARGET_PACKAGE in callers &&
            ctx.packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH)
        check(ctx.packageName == TEST_PACKAGE)
        if (method == "launcherSession") {
            // Only the same signed debug target may discover a manually launched fixture's nonce.
            // Subsequent inspection/cleanup still requires the exact per-run nonce.
            return onFixtureMain { reply ->
                val session = PinFixtureSessions.requireLauncher()
                reply(session.nativeState().apply { putString("nonce", session.nonce) })
            }
        }
        val nonce = checkNotNull(arg)
        check(Regex("[a-f0-9]{64}").matches(nonce))
        return onFixtureMain { reply ->
            when (method) {
                "begin" -> {
                    PinFixtureSessions.begin(nonce)
                    reply(Bundle().apply { putBoolean("admitted", true) })
                }
                "inspect" -> {
                    val session = PinFixtureSessions.require(nonce)
                    val activity = session.activity.get()
                    if (activity == null) reply(session.nativeState())
                    else activity.inspectDocument(reply)
                }
                "finish" -> {
                    val session = PinFixtureSessions.require(nonce)
                    // The only removable task is the nonce-owned fixture's actual Activity.
                    session.activity.get()?.finishAndRemoveTask()
                    reply(Bundle().apply { putBoolean("finishRequested", true) })
                }
                "end" -> {
                    val session = PinFixtureSessions.require(nonce)
                    check(session.activity.get() == null)
                    PinFixtureSessions.end(session)
                    reply(Bundle().apply { putBoolean("ended", true) })
                }
                else -> error("Unsupported fixture command")
            }
        }
    }

    private fun onFixtureMain(action: ((Bundle) -> Unit) -> Unit): Bundle {
        check(Looper.myLooper() != Looper.getMainLooper())
        val done = CountDownLatch(1)
        val abandoned = AtomicBoolean()
        val answer = AtomicReference<Bundle?>()
        val failure = AtomicReference<Throwable?>()
        val task = Runnable {
            if (!abandoned.get()) {
                try {
                    action { result ->
                        if (!abandoned.get()) answer.set(result)
                        done.countDown()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                    done.countDown()
                }
            }
        }
        check(main.post(task))
        try {
            check(done.await(850, TimeUnit.MILLISECONDS)) { "Fixture IPC deadline" }
            failure.get()?.let { throw it }
            return checkNotNull(answer.get())
        } finally {
            abandoned.set(true)
            main.removeCallbacks(task)
        }
    }

    // Neither URI reads nor writes are supported. All admitted calls above use a fixed schema.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?) = 0

    companion object {
        const val TARGET_PACKAGE = "com.immortal.launcher.debug"
        const val TEST_PACKAGE = "com.immortal.launcher.debug.test"
        const val AUTHORITY = "$TEST_PACKAGE.pin-fixture"
    }
}

internal class PinFixtureSession(val nonce: String, val mode: String = "instrumentation") {
    var activityUuid = UUID.randomUUID().toString()
    val formValue = "generated-form-${UUID.randomUUID()}"
    var activity = WeakReference<PinFixtureActivity>(null)
    var creates = 0
    var newIntents = 0
    var destroys = 0
    var touchDowns = 0
    var touchUps = 0
    var resumed = false
    var systemDreamButtonClicks = 0
    var systemDreamLaunchError: String? = null
    var taskId = -1

    fun nativeState(): Bundle = synchronized(this) {
        Bundle().apply {
            putBoolean("authorized", true)
            putString("sessionMode", mode)
            putString("documentSource", if (mode == "launcher") "manual_fixture_server" else "offline_generated")
            putInt("systemDreamButtonClicks", systemDreamButtonClicks)
            putString("systemDreamLaunchErrorType", systemDreamLaunchError)
            putBoolean("present", activity.get() != null)
            putString("activityUuid", activityUuid)
            putInt("creates", creates)
            putInt("newIntents", newIntents)
            putInt("destroys", destroys)
            putInt("touchDowns", touchDowns)
            putInt("touchUps", touchUps)
            putBoolean("resumed", resumed)
            putInt("taskId", taskId)
        }
    }
}

/** Accessed on fixture main. Holding lifecycle counts here survives Activity recreation. */
internal object PinFixtureSessions {
    private var current: PinFixtureSession? = null

    fun begin(nonce: String) {
        check(current == null) { "Another fixture run owns the test process" }
        current = PinFixtureSession(nonce)
    }

    fun beginLauncher(): String? {
        val old = current
        if (old != null && !(old.mode == "launcher" && old.activity.get() == null &&
            old.creates > 0 && old.destroys == old.creates)) return null
        val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        current = PinFixtureSession(nonce, "launcher")
        return nonce
    }

    fun requireLauncher(): PinFixtureSession = checkNotNull(current).also {
        check(it.mode == "launcher" && it.activity.get() != null)
    }

    fun require(nonce: String): PinFixtureSession = checkNotNull(current).also {
        check(it.nonce == nonce) { "Fixture nonce mismatch" }
    }

    fun attach(nonce: String?, activity: PinFixtureActivity): PinFixtureSession? {
        val session = current?.takeIf { nonce != null && it.nonce == nonce } ?: return null
        synchronized(session) {
            session.creates++
            session.activityUuid = UUID.randomUUID().toString()
            session.activity = WeakReference(activity)
            session.taskId = activity.taskId
        }
        return session
    }

    fun destroyed(session: PinFixtureSession, activity: PinFixtureActivity) {
        synchronized(session) {
            session.destroys++
            if (session.activity.get() === activity) {
                session.activity.clear()
                session.resumed = false
            }
        }
    }

    fun end(session: PinFixtureSession) {
        check(current === session && session.activity.get() == null)
        current = null
    }
}
