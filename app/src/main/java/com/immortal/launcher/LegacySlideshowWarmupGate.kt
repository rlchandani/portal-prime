/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Admits optional startup work once a scene is presented by the current slideshow owner.
 * [presented] may run on the JS bridge thread; deliveries and [resume] run on Main.
 */
internal class LegacySlideshowWarmupGate(
    private val postMain: (() -> Unit) -> Boolean,
    private val isCurrent: () -> Boolean,
    private val mayRun: () -> Boolean,
    private val startWarm: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    // Every access to these three fields holds lock, including bridge-thread reads.
    private var closed = false
    private var started = false
    private var delivery: Any? = null
    // Main-owned: a paused/hidden scene stays ready until resume can admit the work.
    private var ready = false

    fun presented() {
        synchronized(lock) {
            if (closed || started || delivery != null) return
            val ticket = Any()
            delivery = ticket
            // Serialize posting with admission so no bridge post can follow started=true.
            val accepted = try {
                postMain { delivered(ticket) }
            } catch (_: Throwable) {
                false
            }
            // A late rejected delivery must not consume a newer report's ownership.
            if (!accepted && delivery === ticket) delivery = null
        }
    }

    private fun delivered(ticket: Any) {
        if (synchronized(lock) { closed || started || delivery !== ticket }) return
        // Retain the ticket after delivery to coalesce further reports while inactive.
        ready = true
        resume()
    }

    fun resume() {
        if (!ready || synchronized(lock) { closed || started }) return
        val current = try {
            isCurrent()
        } catch (_: Throwable) {
            close()
            return
        }
        if (!current) {
            close()
            return
        }
        val canRun = try {
            mayRun()
        } catch (_: Throwable) {
            close()
            return
        }
        if (!canRun) return
        synchronized(lock) {
            if (closed || started) return
            // Commit before calling out: failure or reentrancy cannot repeat the attempt.
            started = true
            delivery = null
        }
        try {
            startWarm()
        } catch (_: Throwable) {
            // Optional warm-up must not crash playback or be retried.
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            delivery = null
        }
    }
}
