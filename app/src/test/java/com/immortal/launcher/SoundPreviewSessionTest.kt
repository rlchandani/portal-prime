/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SoundPreviewSessionTest {
    private val states = mutableListOf<SoundPreviewState>()
    private val session = SoundPreviewSession { states += it }

    private class Preview(
        private val events: MutableList<String> = mutableListOf(),
        private val name: String = "preview",
        private val duringStart: (() -> Unit)? = null,
        private val duringClose: (() -> Unit)? = null
    ) : AutoCloseable {
        lateinit var playing: () -> Unit
        lateinit var finished: (Boolean) -> Unit
        var closes = 0
        fun start(onPlaying: () -> Unit, onFinished: (Boolean) -> Unit): AutoCloseable {
            events += "start:$name"
            playing = onPlaying
            finished = onFinished
            duringStart?.invoke()
            return this
        }
        override fun close() {
            closes++
            events += "close:$name"
            duringClose?.invoke()
        }
    }

    @Test fun inactiveDoesNotInvokeFactory() {
        session.play("blocked") { _, _ -> fail("inactive factory ran"); Preview() }
        assertEquals(SoundPreviewState(), session.state)
        assertTrue(states.isEmpty())
    }

    @Test fun callbacksPublishStartingPlayingThenIdleAndCloseOnce() {
        session.setActive(true)
        val preview = Preview()
        session.play("bell", preview::start)
        assertEquals(SoundPreviewState("bell", SoundPreviewPhase.STARTING), session.state)
        preview.playing()
        preview.playing()
        assertEquals(SoundPreviewState("bell", SoundPreviewPhase.PLAYING), session.state)
        preview.finished(true)
        preview.finished(false)
        preview.playing()
        session.stop()
        assertEquals(SoundPreviewState(), session.state)
        assertEquals(1, preview.closes)
        assertEquals(listOf(
            SoundPreviewState("bell", SoundPreviewPhase.STARTING),
            SoundPreviewState("bell", SoundPreviewPhase.PLAYING),
            SoundPreviewState()
        ), states)
    }

    @Test fun failureRetainsLabelAndCanBeRetried() {
        session.setActive(true)
        val failed = Preview()
        session.play("bell", failed::start)
        failed.finished(false)
        failed.finished(true)
        failed.playing()
        assertEquals(SoundPreviewState("bell", SoundPreviewPhase.FAILED), session.state)
        assertEquals(1, failed.closes)
        val retry = Preview()
        session.play("bell", retry::start)
        retry.playing()
        assertEquals(SoundPreviewState("bell", SoundPreviewPhase.PLAYING), session.state)
        session.stop()
        assertEquals(1, retry.closes)
    }

    @Test fun replacementClosesOldBeforeStartingNewAndIgnoresOldCallbacks() {
        session.setActive(true)
        val events = mutableListOf<String>()
        val old = Preview(events, "old")
        val replacement = Preview(events, "new")
        session.play("same label", old::start)
        session.play("same label", replacement::start)
        assertEquals(listOf("start:old", "close:old", "start:new"), events)
        val before = states.toList()
        old.playing()
        old.finished(false)
        old.finished(true)
        assertEquals(before, states)
        assertEquals(0, replacement.closes)
        assertEquals(SoundPreviewState("same label", SoundPreviewPhase.STARTING), session.state)
        replacement.finished(true)
        assertEquals(1, old.closes)
        assertEquals(1, replacement.closes)
    }

    @Test fun synchronousSuccessClosesReturnedResourceWithoutResurrectingPlayback() {
        session.setActive(true)
        lateinit var preview: Preview
        preview = Preview(duringStart = {
            preview.playing()
            preview.finished(true)
            preview.playing()
            preview.finished(false)
        })
        session.play("bell", preview::start)
        assertEquals(SoundPreviewState(), session.state)
        assertEquals(1, preview.closes)
        session.stop()
        assertEquals(1, preview.closes)
    }

    @Test fun synchronousFailureClosesReturnedResourceAndKeepsFailure() {
        session.setActive(true)
        val preview = Preview()
        session.play("broken") { playing, finished ->
            preview.start(playing, finished)
            finished(false)
            finished(true)
            preview
        }
        assertEquals(SoundPreviewState("broken", SoundPreviewPhase.FAILED), session.state)
        assertEquals(1, preview.closes)
    }

    @Test fun factoryExceptionFailsAndInvalidatesCapturedCallbacks() {
        session.setActive(true)
        var latePlaying: (() -> Unit)? = null
        var lateFinished: ((Boolean) -> Unit)? = null
        session.play("broken") { playing, finished ->
            latePlaying = playing
            lateFinished = finished
            playing()
            throw IllegalStateException("allocation failed")
        }
        assertEquals(SoundPreviewState("broken", SoundPreviewPhase.FAILED), session.state)
        val replacement = Preview()
        session.play("next", replacement::start)
        latePlaying!!()
        lateFinished!!(true)
        assertEquals(SoundPreviewState("next", SoundPreviewPhase.STARTING), session.state)
        assertEquals(0, replacement.closes)
        session.stop()
        assertEquals(1, replacement.closes)
    }

    @Test fun exceptionAfterSynchronousCompletionDoesNotOverwriteCompletion() {
        session.setActive(true)
        session.play("bell") { _, finished ->
            finished(true)
            throw IllegalStateException("factory owns partial allocations")
        }
        assertEquals(SoundPreviewState(), session.state)
    }

    @Test fun stopInsideFactoryClosesResourceWhenItEventuallyReturns() {
        session.setActive(true)
        val preview = Preview(duringStart = { session.stop() })
        session.play("bell", preview::start)
        preview.playing()
        preview.finished(false)
        assertEquals(SoundPreviewState(), session.state)
        assertEquals(1, preview.closes)
    }

    @Test fun replacementInsideFactoryCannotAdoptOldReturnedResource() {
        session.setActive(true)
        val replacement = Preview()
        val old = Preview(duringStart = { session.play("new", replacement::start) })
        session.play("old", old::start)
        old.finished(false)
        old.playing()
        assertEquals(1, old.closes)
        assertEquals(0, replacement.closes)
        assertEquals(SoundPreviewState("new", SoundPreviewPhase.STARTING), session.state)
        session.stop()
        assertEquals(1, replacement.closes)
    }

    @Test fun stopInvalidatesCallbacksBeforeClosingAndIsIdempotent() {
        session.setActive(true)
        lateinit var preview: Preview
        preview = Preview(duringClose = { preview.playing(); preview.finished(false) })
        session.play("bell", preview::start)
        preview.playing()
        session.stop()
        session.stop()
        preview.playing()
        preview.finished(true)
        assertEquals(SoundPreviewState(), session.state)
        assertEquals(1, preview.closes)
    }

    @Test fun inactiveClosesAndRejectsStartsUntilReactivated() {
        session.setActive(true)
        lateinit var preview: Preview
        preview = Preview(duringClose = { preview.finished(false); preview.playing() })
        session.play("bell", preview::start)
        preview.playing()
        session.setActive(false)
        session.setActive(false)
        session.play("blocked") { _, _ -> fail("inactive factory ran"); Preview() }
        assertEquals(SoundPreviewState(), session.state)
        assertEquals(1, preview.closes)
        session.setActive(true)
        val next = Preview()
        session.play("next", next::start)
        assertEquals(SoundPreviewState("next", SoundPreviewPhase.STARTING), session.state)
        session.stop()
        assertEquals(1, next.closes)
    }

    @Test fun inactiveDuringFactoryClosesReturnedResource() {
        session.setActive(true)
        val preview = Preview(duringStart = { session.setActive(false) })
        session.play("bell", preview::start)
        assertEquals(SoundPreviewState(), session.state)
        assertEquals(1, preview.closes)
    }

    @Test fun disposeIsPermanentAndInvalidatesCallbacksBeforeClose() {
        session.setActive(true)
        lateinit var preview: Preview
        preview = Preview(duringClose = { preview.finished(false); preview.playing() })
        session.play("bell", preview::start)
        preview.playing()
        session.dispose()
        session.dispose()
        session.setActive(true)
        session.play("blocked") { _, _ -> fail("disposed factory ran"); Preview() }
        preview.playing()
        preview.finished(true)
        assertEquals(SoundPreviewState(), session.state)
        assertEquals(1, preview.closes)
    }

    @Test fun disposeDuringFactoryClosesReturnedResource() {
        session.setActive(true)
        val preview = Preview(duringStart = { session.dispose() })
        session.play("bell", preview::start)
        assertEquals(SoundPreviewState(), session.state)
        assertEquals(1, preview.closes)
    }

    @Test fun throwingCloseDoesNotBlockReplacementOrCauseDoubleClose() {
        session.setActive(true)
        val events = mutableListOf<String>()
        val old = Preview(events, "old", duringClose = { throw IllegalStateException("close failed") })
        val next = Preview(events, "next")
        session.play("old", old::start)
        session.play("next", next::start)
        assertEquals(listOf("start:old", "close:old", "start:next"), events)
        next.finished(true)
        session.stop()
        assertEquals(SoundPreviewState(), session.state)
        assertEquals(1, old.closes)
        assertEquals(1, next.closes)
    }

    @Test fun completionWithThrowingCloseStillPublishesFailureAndAllowsRetry() {
        session.setActive(true)
        val broken = Preview(duringClose = { throw IllegalStateException("close failed") })
        session.play("broken", broken::start)
        broken.finished(false)
        assertEquals(SoundPreviewState("broken", SoundPreviewPhase.FAILED), session.state)
        val next = Preview()
        session.play("next", next::start)
        next.finished(true)
        assertEquals(1, broken.closes)
        assertEquals(1, next.closes)
        assertEquals(SoundPreviewState(), session.state)
    }

    @Test fun startingObserverCanCancelBeforeFactoryAllocation() {
        lateinit var observed: SoundPreviewSession
        observed = SoundPreviewSession {
            if (it.phase == SoundPreviewPhase.STARTING) observed.stop()
        }
        observed.setActive(true)
        observed.play("bell") { _, _ -> fail("cancelled factory ran"); Preview() }
        assertEquals(SoundPreviewState(), observed.state)
    }

    @Test fun factoryExceptionAfterCancellationDoesNotPublishFailure() {
        session.setActive(true)
        session.play("cancelled") { _, _ ->
            session.stop()
            throw IllegalStateException("allocation aborted")
        }
        assertEquals(SoundPreviewState(), session.state)
        assertEquals(listOf(
            SoundPreviewState("cancelled", SoundPreviewPhase.STARTING),
            SoundPreviewState()
        ), states)
    }

    @Test fun replacementDuringCloseIsNotOverwrittenByEarlierPlayRequest() {
        session.setActive(true)
        val newest = Preview()
        val old = Preview(duringClose = { session.play("newest", newest::start) })
        session.play("old", old::start)
        session.play("superseded") { _, _ -> fail("superseded factory ran"); Preview() }
        assertEquals(SoundPreviewState("newest", SoundPreviewPhase.STARTING), session.state)
        assertEquals(1, old.closes)
        assertEquals(0, newest.closes)
        session.stop()
        assertEquals(1, newest.closes)
    }

    @Test fun stopClearsFailureState() {
        session.setActive(true)
        session.play("broken") { _, _ -> throw IllegalStateException("failed") }
        session.stop()
        assertEquals(SoundPreviewState(), session.state)
    }
}
