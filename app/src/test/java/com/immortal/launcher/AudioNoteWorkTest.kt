/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the production command coordinator runs here; no Android audio or real memo file is used. */
class AudioNoteWorkTest {
    @Test fun hidingDuringRecorderPreparationPreventsStartWithoutWaitingForPreparation() {
        val executor = Executors.newSingleThreadExecutor()
        val work = AudioNoteWork(executor)
        val preparing = CountDownLatch(1)
        val finishPreparing = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val starts = AtomicInteger()
        try {
            val request = work.submit { lease ->
                preparing.countDown()
                check(finishPreparing.await(2, TimeUnit.SECONDS))
                work.resume(lease) { starts.incrementAndGet() }
            }!!
            assertTrue(preparing.await(2, TimeUnit.SECONDS))
            work.submit { cleaned.countDown() }
            // Cancellation is logical and immediate while native preparation is still blocked.
            assertFalse(request.isCurrent())
            finishPreparing.countDown()
            assertTrue(cleaned.await(2, TimeUnit.SECONDS))
            assertEquals(0, starts.get())
        } finally {
            finishPreparing.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun latePlayerPreparedCallbackCannotReplaceStopCleanup() {
        val executor = Executors.newSingleThreadExecutor()
        val work = AudioNoteWork(executor)
        val pending = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val finishCleanup = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val starts = AtomicInteger()
        try {
            val playback = work.submit { pending.countDown() }!!
            assertTrue(pending.await(2, TimeUnit.SECONDS))
            work.submit {
                cleanupStarted.countDown()
                check(finishCleanup.await(2, TimeUnit.SECONDS))
                cleaned.countDown()
            }
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
            assertFalse(work.resume(playback) { starts.incrementAndGet() })
            finishCleanup.countDown()
            assertTrue(cleaned.await(2, TimeUnit.SECONDS))
            assertEquals(0, starts.get())
        } finally {
            finishCleanup.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun staleCompletionCannotReleaseTheReplacementPlayer() {
        val executor = Executors.newSingleThreadExecutor()
        val work = AudioNoteWork(executor)
        val firstReady = CountDownLatch(1)
        val nextReady = CountDownLatch(1)
        val replacementReleases = AtomicInteger()
        try {
            val old = work.submit { firstReady.countDown() }!!
            assertTrue(firstReady.await(2, TimeUnit.SECONDS))
            val next = work.submit { nextReady.countDown() }!!
            assertTrue(nextReady.await(2, TimeUnit.SECONDS))
            assertFalse(work.resume(old) { replacementReleases.incrementAndGet() })
            assertTrue(next.isCurrent())
            assertEquals(0, replacementReleases.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun repeatedCommandsKeepOnlyTheLatestPendingOperation() {
        val executor = Executors.newSingleThreadExecutor()
        val work = AudioNoteWork(executor)
        val blocked = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val lastRan = CountDownLatch(1)
        val actions = Collections.synchronizedList(mutableListOf<Int>())
        val discarded = AtomicInteger()
        try {
            work.submit {
                blocked.countDown()
                check(unblock.await(2, TimeUnit.SECONDS))
            }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            repeat(99) { index ->
                work.submit(onDiscard = { discarded.incrementAndGet() }) { actions.add(index) }
            }
            work.submit { actions.add(99); lastRan.countDown() }
            unblock.countDown()
            assertTrue(lastRan.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(99), actions.toList())
            assertEquals(99, discarded.get())
        } finally {
            unblock.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun releaseRetiresPreparingAudioAndAlwaysRunsFinalCleanup() {
        val executor = Executors.newSingleThreadExecutor()
        val work = AudioNoteWork(executor)
        val preparing = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val starts = AtomicInteger()
        try {
            val request = work.submit { lease ->
                preparing.countDown()
                check(unblock.await(2, TimeUnit.SECONDS))
                work.resume(lease) { starts.incrementAndGet() }
            }!!
            assertTrue(preparing.await(2, TimeUnit.SECONDS))
            work.close { cleaned.countDown() }
            assertFalse(request.isCurrent())
            assertEquals(null, work.submit { starts.incrementAndGet() })
            unblock.countDown()
            assertTrue(cleaned.await(2, TimeUnit.SECONDS))
            assertEquals(0, starts.get())
        } finally {
            unblock.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun queuedMainThreadCallbackIsRetiredBeforeDelivery() {
        val executor = Executors.newSingleThreadExecutor()
        val work = AudioNoteWork(executor)
        val queued = CountDownLatch(1)
        val callbacks = Collections.synchronizedList(mutableListOf<() -> Unit>())
        val deliveries = AtomicInteger()
        try {
            work.submit { request ->
                request.dispatch({ callbacks.add(it) }) { deliveries.incrementAndGet() }
                queued.countDown()
            }
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            work.submit {}
            callbacks.toList().forEach { it() }
            assertEquals(0, deliveries.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun explicitMemoDeletionSurvivesCloseAndPrecedesFinalCleanup() {
        val executor = Executors.newSingleThreadExecutor()
        val work = AudioNoteWork(executor)
        val blocked = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val actions = Collections.synchronizedList(mutableListOf<String>())
        try {
            work.submit {
                blocked.countDown()
                check(unblock.await(2, TimeUnit.SECONDS))
            }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            work.commit { actions.add("delete saved memo") }
            work.close { actions.add("release owner"); finished.countDown() }
            unblock.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("delete saved memo", "release owner"), actions.toList())
        } finally {
            unblock.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun legacyPeakQueryDoesNotRetirePlaybackOrItsCompletionCallback() {
        val executor = Executors.newSingleThreadExecutor()
        val work = AudioNoteWork(executor)
        val ready = CountDownLatch(1)
        val deliveries = AtomicInteger()
        try {
            val playing = work.submit { ready.countDown() }!!
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertEquals(12, work.read(0) { 12 })
            playing.dispatch({ it() }) { deliveries.incrementAndGet() }
            assertEquals(1, deliveries.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun legacyRecordingReturnWaitsForNativeResultAndPreservesFailure() {
        val executor = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val work = AudioNoteWork(executor)
        val starting = CountDownLatch(1)
        val finishStart = CountDownLatch(1)
        try {
            val result = caller.submit<Boolean> {
                work.call(false) {
                    starting.countDown()
                    check(finishStart.await(2, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(starting.await(2, TimeUnit.SECONDS))
            assertFalse(result.isDone) // Enqueuing is not successful recording.
            finishStart.countDown()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertFalse(work.call(true) { false })
        } finally {
            finishStart.countDown()
            caller.shutdownNow()
            executor.shutdownNow()
        }
    }

    @Test fun newOwnersInitialSnapshotWaitsForOldOwnersMemoFinalization() {
        val executor = Executors.newSingleThreadExecutor()
        val old = AudioNoteWork(executor)
        val next = AudioNoteWork(executor)
        val saving = CountDownLatch(1)
        val finishSaving = CountDownLatch(1)
        val snapshotQueued = CountDownLatch(1)
        val savedMemo = AtomicBoolean(false)
        val observedMemo = AtomicBoolean(false)
        val callbacks = Collections.synchronizedList(mutableListOf<() -> Unit>())
        try {
            old.submit {
                saving.countDown()
                check(finishSaving.await(2, TimeUnit.SECONDS))
            }
            assertTrue(saving.await(2, TimeUnit.SECONDS))
            old.close { savedMemo.set(true) }
            next.readAsync(false, { savedMemo.get() }, {
                callbacks.add(it)
                snapshotQueued.countDown()
            }) { observedMemo.set(it) }
            finishSaving.countDown()
            assertTrue(snapshotQueued.await(2, TimeUnit.SECONDS))
            callbacks.toList().forEach { it() }
            assertTrue(observedMemo.get())
        } finally {
            finishSaving.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun disposedOwnerCannotReceiveItsQueuedMemoSnapshot() {
        val executor = Executors.newSingleThreadExecutor()
        val work = AudioNoteWork(executor)
        val queued = CountDownLatch(1)
        val callbacks = Collections.synchronizedList(mutableListOf<() -> Unit>())
        val deliveries = AtomicInteger()
        try {
            work.readAsync(false, { true }, {
                callbacks.add(it)
                queued.countDown()
            }) { deliveries.incrementAndGet() }
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            work.close {}
            callbacks.toList().forEach { it() }
            assertEquals(0, deliveries.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
