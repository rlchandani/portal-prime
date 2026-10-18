/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Fixed public inputs and boolean results only. No UiAutomation, preferences, or raw diagnostics. */
internal object OneShotSpeechDeviceChecks {
    private const val FIXTURE_PACKAGE = "com.immortal.launcher.debug.test"
    private const val WAIT_MS = 6000L
    const val EXPECTED_CHECK_COUNT = 23

    fun run(probe: Instrumentation): Bundle {
        val checks = JSONArray()
        var failures = 0
        var actualPassed = 0
        fun expect(name: String, framework: Boolean = false, assertion: () -> Boolean) {
            val passed = try { assertion() } catch (_: Throwable) { false }
            checks.put(JSONObject().put("name", name).put("passed", passed))
            if (!passed) failures++ else if (framework) actualPassed++
        }
        expect("created_waits_for_initialization") {
            val f = OwnerFixture(); f.owner.created(f.engine)
            f.counts(0, 0).also { f.owner.finish() }
        }
        expect("initialization_before_created_delivers_once") {
            val f = OwnerFixture(); f.owner.initialized(true); f.owner.created(f.engine)
            f.counts(1, 0).also { f.owner.finish() }
        }
        expect("initialization_failure_before_created_releases_on_attach") {
            val f = OwnerFixture(); f.owner.initialized(false); f.owner.created(f.engine); f.owner.finish()
            f.counts(0, 1)
        }
        expect("finish_before_created_releases_on_attach") {
            val f = OwnerFixture(); f.owner.finish(); f.owner.created(f.engine); f.owner.initialized(true)
            f.counts(0, 1)
        }
        expect("submission_failure_before_created_releases_on_attach") {
            val f = OwnerFixture(); f.owner.submitted(false); f.owner.created(f.engine); f.owner.initialized(true)
            f.counts(0, 1)
        }
        expect("initialization_failure_after_created_releases_once") {
            val f = OwnerFixture(); f.owner.created(f.engine); f.owner.initialized(false); f.owner.initialized(false)
            f.counts(0, 1)
        }
        expect("rejected_submission_after_ready_releases") {
            val f = OwnerFixture(); f.start(); f.owner.submitted(false); f.owner.finish()
            f.counts(1, 1)
        }
        expect("accepted_submission_waits_for_completion") {
            val f = OwnerFixture(); f.start(); f.owner.submitted(true)
            val waiting = f.counts(1, 0); f.owner.finish(); waiting && f.counts(1, 1)
        }
        expect("repeated_finish_is_idempotent") {
            val f = OwnerFixture(); f.start(); repeat(20) { f.owner.finish() }; f.counts(1, 1)
        }
        expect("duplicate_initialization_is_idempotent") {
            val f = OwnerFixture(); f.start(); repeat(20) { f.owner.initialized(true) }
            f.counts(1, 0).also { f.owner.finish() }
        }
        expect("late_success_cannot_revive_finished_owner") {
            val f = OwnerFixture(); f.owner.created(f.engine); f.owner.finish()
            f.owner.initialized(true); f.owner.submitted(true); f.counts(0, 1)
        }
        expect("ready_exception_releases_engine") {
            val releases = AtomicInteger()
            val owner = OneShotSpeechOwner<Any>({ throw IllegalStateException("public fixture") }, { releases.incrementAndGet() })
            owner.created(Any()); runCatching { owner.initialized(true) }; owner.finish(); releases.get() == 1
        }
        expect("release_exception_does_not_duplicate_release") {
            val releases = AtomicInteger()
            val owner = OneShotSpeechOwner<Any>({}, { releases.incrementAndGet(); throw IllegalStateException("public fixture") })
            owner.created(Any()); runCatching { owner.finish() }; runCatching { owner.finish() }; releases.get() == 1
        }
        expect("reentrant_finish_defers_release_until_ready_exits") { reentrant(false) }
        expect("reentrant_rejection_defers_release_until_ready_exits") { reentrant(true) }
        expect("concurrent_finish_defers_release_until_ready_exits") { concurrentReadyFinish() }
        expect("concurrent_terminal_events_release_once") {
            val f = OwnerFixture(); f.start()
            val okay = race(listOf({ f.owner.finish() }, { f.owner.submitted(false) }, { f.owner.finish() }))
            okay && f.counts(1, 1)
        }
        expect("concurrent_attach_and_initialization_deliver_once") {
            (0 until 20).all {
                val f = OwnerFixture()
                val okay = race(listOf({ f.owner.created(f.engine) }, { f.owner.initialized(true) }))
                val delivered = f.counts(1, 0); f.owner.finish(); okay && delivered && f.counts(1, 1)
            }
        }
        // Read only the public engine-selection settings; emit equality, never their values.
        val defaultBefore = defaults(probe.context)
        expect("android_tts_success_onDone_releases_owner", true) { framework(probe, "public_done") }
        expect("android_tts_synthesis_error_onError_releases_owner", true) { framework(probe, "public_error") }
        expect("android_tts_stop_onStop_releases_owner", true) { framework(probe, "public_stop") }
        expect("android_tts_speak_after_shutdown_is_rejected", true) { rejectedAfterShutdown(probe) }
        expect("android_tts_early_bind_failure_releases_after_attach", true) { earlyFailure(probe) }
        val defaultsUnchanged = defaultBefore != null && defaultBefore == defaults(probe.context)
        val passed = failures == 0 && checks.length() == EXPECTED_CHECK_COUNT && defaultsUnchanged
        val report = JSONObject().put("command", "oneShotSpeech").put("passed", passed)
            .put("completed", passed).put("failureCount", failures + if (defaultsUnchanged) 0 else 1)
            .put("checkCount", checks.length()).put("passedCount", checks.length() - failures)
            .put("fixturesPublic", true).put("networkUsed", false).put("stateMutations", false)
            .put("actualFrameworkCases", 5).put("actualFrameworkPassed", actualPassed)
            .put("ttsDefaultsUnchanged", defaultsUnchanged).put("checks", checks)
        return Bundle().apply {
            putBoolean("oneShotSpeechCompleted", passed)
            putString("oneShotSpeechJson", report.toString())
        }
    }

    private class OwnerFixture {
        val engine = Any()
        val ready = AtomicInteger()
        val released = AtomicInteger()
        val correctIdentity = AtomicBoolean(true)
        val owner = OneShotSpeechOwner<Any>({ correctIdentity.set(correctIdentity.get() && it === engine); ready.incrementAndGet() },
            { correctIdentity.set(correctIdentity.get() && it === engine); released.incrementAndGet() })
        fun start() { owner.created(engine); owner.initialized(true) }
        fun counts(r: Int, d: Int) = correctIdentity.get() && ready.get() == r && released.get() == d
    }

    private fun reentrant(reject: Boolean): Boolean {
        val inside = AtomicBoolean(false)
        val earlyRelease = AtomicBoolean(false)
        val releases = AtomicInteger()
        lateinit var owner: OneShotSpeechOwner<Any>
        owner = OneShotSpeechOwner({
            inside.set(true)
            if (reject) owner.submitted(false) else owner.finish()
            earlyRelease.set(releases.get() != 0)
            inside.set(false)
        }, { if (inside.get()) earlyRelease.set(true); releases.incrementAndGet() })
        owner.created(Any()); owner.initialized(true); owner.finish()
        return !earlyRelease.get() && releases.get() == 1
    }

    private fun concurrentReadyFinish(): Boolean {
        val entered = CountDownLatch(1); val leave = CountDownLatch(1); val exited = CountDownLatch(1)
        val releases = AtomicInteger(); val readyExited = AtomicBoolean(false); val early = AtomicBoolean(false)
        val owner = OneShotSpeechOwner<Any>({ entered.countDown(); leave.await(WAIT_MS, TimeUnit.MILLISECONDS); readyExited.set(true) },
            { if (!readyExited.get()) early.set(true); releases.incrementAndGet() })
        owner.created(Any())
        val thread = Thread { try { owner.initialized(true) } finally { exited.countDown() } }.apply { isDaemon = true; start() }
        try {
            if (!entered.await(WAIT_MS, TimeUnit.MILLISECONDS)) return false
            owner.finish()
            if (releases.get() != 0) return false
        } finally { leave.countDown() }
        val finished = exited.await(WAIT_MS, TimeUnit.MILLISECONDS)
        thread.join(100)
        return finished && !early.get() && releases.get() == 1
    }

    private fun race(events: List<() -> Unit>): Boolean {
        val gate = CountDownLatch(1); val done = CountDownLatch(events.size); val failed = AtomicBoolean(false)
        events.forEach { event -> Thread {
            try { if (gate.await(WAIT_MS, TimeUnit.MILLISECONDS)) event() else failed.set(true) }
            catch (_: Throwable) { failed.set(true) } finally { done.countDown() }
        }.apply { isDaemon = true; start() } }
        gate.countDown()
        return done.await(WAIT_MS, TimeUnit.MILLISECONDS) && !failed.get()
    }

    private fun defaults(context: Context): List<String?>? = try {
        listOf("tts_default_synth", "tts_default_rate", "tts_default_pitch", "tts_default_locale")
            .map { Settings.Secure.getString(context.contentResolver, it) }
    } catch (_: Throwable) { null }

    private open class FixtureContext(base: Context, private val reject: Boolean = false) : ContextWrapper(base) {
        val binds = AtomicInteger()
        val wrongEngine = AtomicBoolean(false)
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            if (intent.`package` != FIXTURE_PACKAGE || intent.action != TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE) {
                wrongEngine.set(true); return false
            }
            binds.incrementAndGet()
            return !reject && super.bindService(intent, connection, flags)
        }
    }

    private fun silentParams() = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f) }

    /** Match production's deferred shutdown; completion is observed without bypassing ownership. */
    private class ReleaseProbe {
        val count = AtomicInteger()
        private val done = CountDownLatch(1)
        private val successful = AtomicBoolean(true)
        fun release(client: TextToSpeech) {
            Handler(Looper.getMainLooper()).postDelayed({
                try { client.shutdown() } catch (_: Throwable) { successful.set(false) }
                finally { count.incrementAndGet(); done.countDown() }
            }, 100)
        }
        fun await(): Boolean = done.await(WAIT_MS, TimeUnit.MILLISECONDS) && successful.get()
    }

    private fun framework(probe: Instrumentation, command: String): Boolean {
        val context = FixtureContext(probe.context)
        val terminal = CountDownLatch(1); val began = CountDownLatch(1); val release = ReleaseProbe()
        val terminalKind = AtomicReference(""); val submission = AtomicInteger(TextToSpeech.ERROR)
        val status = AtomicInteger(TextToSpeech.ERROR)
        lateinit var owner: OneShotSpeechOwner<TextToSpeech>
        owner = OneShotSpeechOwner({ client ->
            client.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onBeginSynthesis(id: String?, rate: Int, format: Int, channels: Int) { began.countDown() }
                private fun end(kind: String) { terminalKind.set(kind); owner.finish(); terminal.countDown() }
                override fun onDone(id: String?) { end("done") }
                @Deprecated("Framework legacy callback") override fun onError(id: String?) { end("error") }
                override fun onError(id: String?, errorCode: Int) { end("error") }
                override fun onStop(id: String?, interrupted: Boolean) { end("stop") }
            })
            val result = client.speak(command, TextToSpeech.QUEUE_FLUSH, silentParams(), "public_utterance")
            submission.set(result); owner.submitted(result == TextToSpeech.SUCCESS)
        }, release::release)
        try {
            val client = TextToSpeech(context, { value -> status.set(value); owner.initialized(value == TextToSpeech.SUCCESS) }, FIXTURE_PACKAGE)
            owner.created(client)
            if (command == "public_stop") {
                if (!began.await(WAIT_MS, TimeUnit.MILLISECONDS) || client.stop() != TextToSpeech.SUCCESS) return false
            }
            val expected = when (command) { "public_error" -> "error"; "public_stop" -> "stop"; else -> "done" }
            return terminal.await(WAIT_MS, TimeUnit.MILLISECONDS) && release.await() &&
                status.get() == TextToSpeech.SUCCESS && submission.get() == TextToSpeech.SUCCESS &&
                terminalKind.get() == expected && release.count.get() == 1 && context.binds.get() > 0 && !context.wrongEngine.get()
        } finally { owner.finish(); release.await() }
    }

    private fun rejectedAfterShutdown(probe: Instrumentation): Boolean {
        val context = FixtureContext(probe.context); val initialized = CountDownLatch(1); val status = AtomicInteger(TextToSpeech.ERROR)
        val release = ReleaseProbe()
        val owner = OneShotSpeechOwner<TextToSpeech>({}, release::release)
        try {
            val client = TextToSpeech(context, { status.set(it); owner.initialized(it == TextToSpeech.SUCCESS); initialized.countDown() }, FIXTURE_PACKAGE)
            owner.created(client)
            if (!initialized.await(WAIT_MS, TimeUnit.MILLISECONDS) || status.get() != TextToSpeech.SUCCESS) return false
            // Deliberately disconnect this test client; rejection must initiate the owner's cleanup.
            client.shutdown()
            val result = client.speak("public_rejected", TextToSpeech.QUEUE_FLUSH, silentParams(), "public_rejected")
            owner.submitted(result == TextToSpeech.SUCCESS)
            return result == TextToSpeech.ERROR && release.await() && release.count.get() == 1 &&
                context.binds.get() > 0 && !context.wrongEngine.get()
        } finally { owner.finish(); release.await() }
    }

    private fun earlyFailure(probe: Instrumentation): Boolean {
        val context = FixtureContext(probe.context, reject = true)
        val initialized = CountDownLatch(1); val status = AtomicInteger(TextToSpeech.SUCCESS); val attached = AtomicBoolean(false)
        val beforeAttach = AtomicBoolean(false); val ready = AtomicInteger(); val release = ReleaseProbe()
        val owner = OneShotSpeechOwner<TextToSpeech>({ ready.incrementAndGet() }, release::release)
        var client: TextToSpeech? = null
        try {
            client = TextToSpeech(context, {
                status.set(it); beforeAttach.set(!attached.get()); owner.initialized(it == TextToSpeech.SUCCESS); initialized.countDown()
            }, FIXTURE_PACKAGE)
            // This framework case proves failure before attachment, not callback timing inside construction.
            val notified = initialized.await(WAIT_MS, TimeUnit.MILLISECONDS)
            attached.set(true); owner.created(client)
            return notified && beforeAttach.get() && status.get() == TextToSpeech.ERROR && release.await() &&
                ready.get() == 0 && release.count.get() == 1 && context.binds.get() > 0
        } finally {
            client?.let { if (!attached.getAndSet(true)) owner.created(it) }
            owner.finish(); release.await()
        }
    }
}
