/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate behavior only: these tests make no claim about WebView or physical Portal timing. */
class LegacySlideshowWarmupGateTest {
    @Test fun firstSceneReportWarmsOnceOnlyAfterMainDelivery() {
        val fixture = Fixture()

        fixture.reportFromBridge()

        assertEquals(1, fixture.posts.get())
        assertEquals(0, fixture.currentChecks)
        assertEquals(0, fixture.runChecks)
        assertEquals(0, fixture.warmAttempts)
        fixture.deliverAll()
        assertEquals(1, fixture.warmAttempts)
    }

    @Test fun constructionAndResumeBeforePresentationDoNotWarmOrPost() {
        val fixture = Fixture()

        repeat(5) { fixture.gate.resume() }

        assertEquals(0, fixture.posts.get())
        assertEquals(0, fixture.currentChecks)
        assertEquals(0, fixture.runChecks)
        assertEquals(0, fixture.warmAttempts)
    }

    @Test fun resumeBeforeQueuedPresentationIsDeliveredDoesNotWarm() {
        val fixture = Fixture()
        fixture.reportFromBridge()

        fixture.gate.resume()

        assertEquals(0, fixture.warmAttempts)
        fixture.deliverAll()
        assertEquals(1, fixture.warmAttempts)
    }

    @Test fun duplicateReportsShareOneQueuedMainDelivery() {
        val fixture = Fixture()

        repeat(50) { fixture.gate.presented() }

        assertEquals(1, fixture.posts.get())
        assertEquals(1, fixture.mainQueue.size)
        fixture.deliverAll()
        assertEquals(1, fixture.warmAttempts)
    }

    @Test fun simultaneousBridgeReportsShareOneQueuedMainDelivery() {
        val fixture = Fixture()
        val release = CountDownLatch(1)
        val finished = CountDownLatch(12)
        val failure = AtomicReference<Throwable?>()
        val reporters = List(12) {
            Thread {
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    repeat(20) { fixture.gate.presented() }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    finished.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        release.countDown()
        assertTrue("Bridge reports did not finish", finished.await(5, TimeUnit.SECONDS))
        reporters.forEach { it.join(1_000) }
        failure.get()?.let { throw AssertionError("Bridge report failed", it) }

        assertEquals(1, fixture.posts.get())
        assertEquals(1, fixture.mainQueue.size)
        assertEquals(0, fixture.warmAttempts)
        fixture.deliverAll()
        assertEquals(1, fixture.warmAttempts)
    }

    @Test fun hiddenSceneRetainsPresentationUntilItBecomesActive() {
        val fixture = Fixture()
        fixture.active = false
        fixture.reportFromBridge()
        fixture.deliverAll()
        assertEquals(0, fixture.warmAttempts)

        fixture.gate.resume()
        assertEquals(0, fixture.warmAttempts)
        fixture.active = true
        fixture.gate.resume()
        fixture.gate.resume()

        assertEquals(1, fixture.warmAttempts)
        assertEquals(1, fixture.posts.get())
    }

    @Test fun pausedSceneRetainsPresentationUntilPlaybackResumes() {
        val fixture = Fixture()
        fixture.paused = true
        fixture.reportFromBridge()
        fixture.deliverAll()
        assertEquals(0, fixture.warmAttempts)

        fixture.gate.resume()
        assertEquals(0, fixture.warmAttempts)
        fixture.paused = false
        fixture.gate.resume()

        assertEquals(1, fixture.warmAttempts)
    }

    @Test fun duplicateReportsWhileInactiveDoNotFloodMainOrLoseReadiness() {
        val fixture = Fixture()
        fixture.active = false
        fixture.reportFromBridge()
        fixture.deliverAll()

        repeat(50) { fixture.gate.presented() }
        assertEquals(1, fixture.posts.get())
        fixture.deliverAll()
        assertEquals(0, fixture.warmAttempts)
        fixture.active = true
        fixture.gate.resume()

        assertEquals(1, fixture.warmAttempts)
    }

    @Test fun sourceReplacementBeforeMainDeliveryRejectsOldPresentation() {
        val fixture = Fixture()
        fixture.reportFromBridge()
        fixture.current = false

        fixture.deliverAll()
        fixture.gate.resume()
        fixture.gate.presented()
        fixture.deliverAll()

        assertEquals(0, fixture.warmAttempts)
        assertEquals(0, fixture.runChecks)
    }

    @Test fun sourceInvalidationOnResumeDiscardsRetainedReadiness() {
        val fixture = Fixture()
        fixture.active = false
        fixture.reportFromBridge()
        fixture.deliverAll()
        fixture.current = false
        fixture.active = true

        fixture.gate.resume()
        assertEquals(0, fixture.warmAttempts)
        // Once this owner is stale, a later matching source cannot revive its old scene.
        fixture.current = true
        fixture.gate.resume()
        fixture.gate.presented()
        fixture.deliverAll()

        assertEquals(0, fixture.warmAttempts)
    }

    @Test fun closeRejectsQueuedAndFutureBridgeReports() {
        val fixture = Fixture()
        fixture.reportFromBridge()

        fixture.gate.close()
        fixture.gate.close()
        fixture.deliverAll()
        fixture.reportFromBridge()
        fixture.gate.resume()

        assertEquals(1, fixture.posts.get())
        assertEquals(0, fixture.currentChecks)
        assertEquals(0, fixture.warmAttempts)
    }

    @Test fun closeBeforePresentationPreventsAnyMainPost() {
        val fixture = Fixture()

        fixture.gate.close()
        fixture.reportFromBridge()
        fixture.gate.resume()

        assertEquals(0, fixture.posts.get())
        assertEquals(0, fixture.warmAttempts)
    }

    @Test fun closeAfterInactivePresentationRejectsLaterResume() {
        val fixture = Fixture()
        fixture.active = false
        fixture.reportFromBridge()
        fixture.deliverAll()

        fixture.gate.close()
        fixture.active = true
        fixture.gate.resume()

        assertEquals(0, fixture.warmAttempts)
    }

    @Test fun rejectedMainPostReleasesOwnershipForNextLegitimateReport() {
        val fixture = Fixture()
        fixture.rejectPost = true
        fixture.reportFromBridge()
        fixture.gate.resume()
        assertEquals(0, fixture.warmAttempts)
        assertEquals(0, fixture.mainQueue.size)

        fixture.rejectPost = false
        fixture.reportFromBridge()
        fixture.deliverAll()

        assertEquals(2, fixture.posts.get())
        assertEquals(1, fixture.warmAttempts)
    }

    @Test fun throwingMainPostReleasesOwnershipForNextLegitimateReport() {
        val fixture = Fixture()
        fixture.postFailure = IllegalStateException("Main transport unavailable")
        fixture.reportFromBridge()
        fixture.gate.resume()
        assertEquals(0, fixture.warmAttempts)

        fixture.postFailure = null
        fixture.reportFromBridge()
        fixture.deliverAll()

        assertEquals(2, fixture.posts.get())
        assertEquals(1, fixture.warmAttempts)
    }

    @Test fun lateDeliveryFromRejectedTransportCannotClaimRetryOwnership() {
        val fixture = Fixture()
        fixture.rejectPost = true
        fixture.retainFailedDelivery = true
        fixture.reportFromBridge()
        fixture.rejectPost = false
        fixture.reportFromBridge()
        assertEquals(2, fixture.mainQueue.size)

        fixture.deliverNext()
        assertEquals(0, fixture.warmAttempts)
        assertEquals(0, fixture.currentChecks)
        fixture.deliverNext()

        assertEquals(1, fixture.warmAttempts)
    }

    @Test fun lateDeliveryFromThrowingTransportCannotClaimRetryOwnership() {
        val fixture = Fixture()
        fixture.postFailure = IllegalStateException("Failure after enqueue")
        fixture.retainFailedDelivery = true
        fixture.reportFromBridge()
        fixture.postFailure = null
        fixture.reportFromBridge()

        fixture.deliverNext()
        assertEquals(0, fixture.warmAttempts)
        fixture.deliverNext()

        assertEquals(1, fixture.warmAttempts)
    }

    @Test fun failedWarmCallbackIsOneAttemptAndDoesNotEscapeOrRetry() {
        val fixture = Fixture()
        fixture.warmFailure = AssertionError("Optional warm-up failed")
        fixture.reportFromBridge()

        fixture.deliverAll()
        repeat(10) {
            fixture.reportFromBridge()
            fixture.gate.resume()
        }

        assertEquals(1, fixture.warmAttempts)
        assertEquals(1, fixture.posts.get())
    }

    @Test fun failingOwnershipCallbackRejectsWarmAndDoesNotKeepRetrying() {
        val fixture = Fixture()
        fixture.currentFailure = IllegalStateException("Owner retired")
        fixture.reportFromBridge()
        fixture.deliverAll()
        fixture.currentFailure = null

        repeat(5) {
            fixture.gate.resume()
            fixture.reportFromBridge()
            fixture.deliverAll()
        }

        assertEquals(1, fixture.currentChecks)
        assertEquals(0, fixture.runChecks)
        assertEquals(0, fixture.warmAttempts)
    }

    @Test fun failingPlaybackCallbackRejectsWarmAndDoesNotKeepRetrying() {
        val fixture = Fixture()
        fixture.runFailure = IllegalStateException("Playback owner retired")
        fixture.reportFromBridge()
        fixture.deliverAll()
        fixture.runFailure = null

        repeat(5) {
            fixture.gate.resume()
            fixture.reportFromBridge()
            fixture.deliverAll()
        }

        assertEquals(1, fixture.runChecks)
        assertEquals(0, fixture.warmAttempts)
    }

    @Test fun warmCallbackCannotReentrantlyPostOrStartAnotherWarmAttempt() {
        val fixture = Fixture()
        fixture.onWarm = {
            fixture.gate.presented()
            fixture.gate.resume()
        }
        fixture.reportFromBridge()

        fixture.deliverAll()

        assertEquals(1, fixture.posts.get())
        assertEquals(1, fixture.warmAttempts)
    }

    @Test fun completedGateIgnoresBridgeReportsAndResumeWithoutMoreChecksOrPosts() {
        val fixture = Fixture()
        fixture.reportFromBridge()
        fixture.deliverAll()
        val checks = fixture.currentChecks to fixture.runChecks

        repeat(20) {
            fixture.reportFromBridge()
            fixture.gate.resume()
        }

        assertEquals(1, fixture.posts.get())
        assertEquals(1, fixture.warmAttempts)
        assertEquals(checks, fixture.currentChecks to fixture.runChecks)
        assertTrue(fixture.mainQueue.isEmpty())
    }

    private class Fixture {
        private val mainThread = Thread.currentThread()
        val mainQueue = ConcurrentLinkedQueue<() -> Unit>()
        val posts = AtomicInteger()
        var current = true
        var active = true
        var paused = false
        var rejectPost = false
        var retainFailedDelivery = false
        var postFailure: Throwable? = null
        var currentFailure: Throwable? = null
        var runFailure: Throwable? = null
        var warmFailure: Throwable? = null
        var onWarm: (() -> Unit)? = null
        var currentChecks = 0
        var runChecks = 0
        var warmAttempts = 0
        val gate = LegacySlideshowWarmupGate(
            postMain = { delivery ->
                posts.incrementAndGet()
                if (retainFailedDelivery || (!rejectPost && postFailure == null)) {
                    mainQueue.add(delivery)
                }
                postFailure?.let { throw it }
                !rejectPost
            },
            isCurrent = {
                check(Thread.currentThread() === mainThread)
                currentChecks++
                currentFailure?.let { throw it }
                current
            },
            mayRun = {
                check(Thread.currentThread() === mainThread)
                runChecks++
                runFailure?.let { throw it }
                active && !paused
            },
            startWarm = {
                check(Thread.currentThread() === mainThread)
                warmAttempts++
                onWarm?.invoke()
                warmFailure?.let { throw it }
            },
        )

        fun reportFromBridge() {
            val failure = AtomicReference<Throwable?>()
            val thread = Thread {
                try {
                    gate.presented()
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }.apply { isDaemon = true; start() }
            thread.join(5_000)
            assertFalse("Bridge report blocked", thread.isAlive)
            failure.get()?.let { throw AssertionError("Bridge report escaped", it) }
        }

        fun deliverNext() {
            check(Thread.currentThread() === mainThread)
            checkNotNull(mainQueue.poll()) { "Expected queued Main delivery" }.invoke()
        }

        fun deliverAll() {
            check(Thread.currentThread() === mainThread)
            while (mainQueue.isNotEmpty()) deliverNext()
        }
    }
}
