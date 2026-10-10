/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PinnedAppScreensaverTest {
    private class Host : PinnedScreensaverHost {
        override var pinned = true
        override var covered = false
        var releases = 0
        var shows = 0
        var reveals = 0
        var revealSucceeds = true
        override fun pinCurrent(): Boolean { pinned = true; return true }
        override fun markSessionExpired() { pinned = true }
        override fun release() { releases++; pinned = false; covered = false }
        override fun showFrame(): Boolean { shows++; covered = true; return true }
        override fun revealCover(): Boolean { reveals++; if (revealSucceeds) covered = false; return revealSucceeds }
        override fun windowsChanged() {}
        override fun nativeDreamAttached() {}
        override fun nativeDreamDetached() {}
        override fun status() = JSONObject().put("pinned", pinned)
    }
    @Test fun pinnedDreamDismissNeverReadsPreferencesOrLaunchesAnyApp() {
        val host = Host(); PinnedAppScreensaver.attach(host)
        try {
            val context = Mockito.mock(Context::class.java)
            assertTrue(ScreensaverDismiss.launchChosenApp(context))
            Mockito.verifyNoInteractions(context)
            assertTrue(host.pinned)
            assertEquals(0, host.shows)
        } finally { PinnedAppScreensaver.detach(host); PinnedAppScreensaver.release() }
    }
    @Test fun serviceReplacementReleasesOnlyItsOwnOldSurface() {
        val old = Host(); val replacement = Host()
        PinnedAppScreensaver.attach(old)
        PinnedAppScreensaver.attach(replacement)
        try {
            assertEquals(1, old.releases)
            PinnedAppScreensaver.detach(old)
            assertEquals(0, replacement.releases)
            assertTrue(PinnedAppScreensaver.hasPin())
            assertTrue(PinnedAppScreensaver.showFrame())
            assertEquals(1, replacement.shows)
            assertTrue(PinnedAppScreensaver.hasFrame())
        } finally { PinnedAppScreensaver.detach(replacement) }
        assertTrue(PinnedAppScreensaver.hasPin()) // expiry blocks launch fallback after host loss
        PinnedAppScreensaver.release()
        assertFalse(PinnedAppScreensaver.hasPin())
        assertFalse(PinnedAppScreensaver.hasFrame())
    }
    @Test fun lostServiceStillSuppressesConfiguredDismissLaunchUntilDeliberateExit() {
        val host = Host(); PinnedAppScreensaver.attach(host)
        try {
            PinnedAppScreensaver.detach(host)
            val context = Mockito.mock(Context::class.java)
            assertTrue(PinnedAppScreensaver.hasPin())
            assertTrue(ScreensaverDismiss.launchChosenApp(context))
            Mockito.verifyNoInteractions(context)
        } finally { PinnedAppScreensaver.release() }
    }
    @Test fun lostAccessibilityHostCannotAdmitNativeDreamReturnWithoutALostBarrier() {
        val host = Host(); PinnedAppScreensaver.attach(host)
        try {
            PinnedAppScreensaver.detach(host)
            assertTrue(PinnedAppScreensaver.hasPin())
            assertFalse(PinnedAppScreensaver.nativeDreamTapped())
            PinnedAppScreensaver.release() // deliberate release permits ordinary reveal
            assertTrue(PinnedAppScreensaver.nativeDreamTapped())
        } finally { PinnedAppScreensaver.release() }
    }
    @Test fun frameSelectionChecksPinAtMainAdmissionRatherThanOnTheWorker() {
        val host = Host().apply { pinned = false }; PinnedAppScreensaver.attach(host)
        val legacy = AtomicInteger()
        try {
            Mockito.mockStatic(android.os.Looper::class.java).use { loops ->
                val main = Mockito.mock(android.os.Looper::class.java)
                val worker = Mockito.mock(android.os.Looper::class.java)
                loops.`when`<android.os.Looper> { android.os.Looper.getMainLooper() }.thenReturn(main)
                loops.`when`<android.os.Looper> { android.os.Looper.myLooper() }.thenReturn(worker)
                Mockito.mockConstruction(android.os.Handler::class.java) { handler, _ ->
                    Mockito.`when`(handler.post(Mockito.any(Runnable::class.java))).thenAnswer { call ->
                        PinnedAppScreensaver.pinCurrent() // QuickBar wins before queued main admission
                        (call.arguments[0] as Runnable).run()
                        true
                    }
                }.use {
                    assertTrue(PinnedAppScreensaver.selectFrame { legacy.incrementAndGet(); true }.getBoolean("ok"))
                }
            }
            assertEquals(0, legacy.get())
            assertEquals(1, host.shows)
        } finally { PinnedAppScreensaver.detach(host); PinnedAppScreensaver.release() }
    }
    @Test fun backSelectsNewCoverAtAdmissionAndDoesNotSendInputToApp() {
        val host = Host(); PinnedAppScreensaver.attach(host)
        val legacy = AtomicInteger()
        try {
            Mockito.mockStatic(android.os.Looper::class.java).use { loops ->
                val main = Mockito.mock(android.os.Looper::class.java)
                val worker = Mockito.mock(android.os.Looper::class.java)
                loops.`when`<android.os.Looper> { android.os.Looper.getMainLooper() }.thenReturn(main)
                loops.`when`<android.os.Looper> { android.os.Looper.myLooper() }.thenReturn(worker)
                Mockito.mockConstruction(android.os.Handler::class.java) { handler, _ ->
                    Mockito.`when`(handler.post(Mockito.any(Runnable::class.java))).thenAnswer { call ->
                        host.covered = true // a native interruption covers it before admission
                        (call.arguments[0] as Runnable).run()
                        true
                    }
                }.use {
                    assertTrue(PinnedAppScreensaver.back { legacy.incrementAndGet(); true }.getBoolean("ok"))
                }
            }
            assertEquals(1, host.reveals)
            assertEquals(0, legacy.get())
        } finally { PinnedAppScreensaver.detach(host); PinnedAppScreensaver.release() }
    }
    @Test fun lostCoverRejectsBackWithoutTouchingUnderlyingApp() {
        val host = Host().apply { covered = true; revealSucceeds = false }
        PinnedAppScreensaver.attach(host)
        val legacy = AtomicInteger()
        try {
            Mockito.mockStatic(android.os.Looper::class.java).use { loops ->
                val main = Mockito.mock(android.os.Looper::class.java)
                loops.`when`<android.os.Looper> { android.os.Looper.getMainLooper() }.thenReturn(main)
                loops.`when`<android.os.Looper> { android.os.Looper.myLooper() }.thenReturn(main)
                assertFalse(PinnedAppScreensaver.back { legacy.incrementAndGet(); true }.getBoolean("ok"))
            }
            assertEquals(1, host.reveals)
            assertEquals(0, legacy.get())
        } finally { PinnedAppScreensaver.detach(host); PinnedAppScreensaver.release() }
    }
    @Test fun navigationMarksIntentionalExitBeforeFinishingDreamAndLaunching() {
        val host = Host(); val dream = Mockito.mock(PhotoDreamService::class.java)
        val before = DreamPolicy.userExitAt; val order = mutableListOf<String>()
        PinnedAppScreensaver.attach(host); ImmortalDreamNavigation.attached(dream)
        try {
            Mockito.doAnswer {
                assertTrue(DreamPolicy.userExitAt > 0L)
                assertFalse(PinnedAppScreensaver.hasPin())
                order.add("finish"); null
            }.`when`(dream).finish()
            Mockito.mockStatic(android.os.Looper::class.java).use { loops ->
                val main = Mockito.mock(android.os.Looper::class.java)
                loops.`when`<android.os.Looper> { android.os.Looper.getMainLooper() }.thenReturn(main)
                loops.`when`<android.os.Looper> { android.os.Looper.myLooper() }.thenReturn(main)
                assertTrue(PinnedAppScreensaver.navigation { order.add("launch"); true }.getBoolean("ok"))
            }
            assertEquals(listOf("finish", "launch"), order)
        } finally {
            ImmortalDreamNavigation.detached(dream)
            PinnedAppScreensaver.detach(host); PinnedAppScreensaver.release()
            DreamPolicy.userExitAt = before
        }
    }
    @Test fun queuedTimeoutCannotPinOrCoverLater() {
        val count = AtomicInteger()
        val request = PinnedMainRequest { count.incrementAndGet(); JSONObject().put("ok", true) }
        assertEquals("main_timeout", request.await(0).getString("error"))
        request.run()
        assertEquals(0, count.get())
    }
    @Test fun begunCommandReportsUncertaintyRatherThanInvitingADuplicate() {
        val began = CountDownLatch(1); val release = CountDownLatch(1)
        val count = AtomicInteger()
        val request = PinnedMainRequest {
            count.incrementAndGet(); began.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            JSONObject().put("ok", true)
        }
        val runner = Thread(request)
        runner.start()
        try {
            assertTrue(began.await(2, TimeUnit.SECONDS))
            try { request.await(0); fail("A begun command is not a cancelled command") }
            catch (_: PinnedCommandPending) {}
        } finally { release.countDown(); runner.join(2_000) }
        assertFalse(runner.isAlive)
        assertTrue(request.await(0).getBoolean("ok"))
        request.run()
        assertEquals(1, count.get())
    }
    @Test fun exceptionsCompleteTheCommandWithoutRepeatingItsSideEffect() {
        val count = AtomicInteger()
        val request = PinnedMainRequest { count.incrementAndGet(); throw IllegalStateException() }
        request.run(); request.run()
        assertEquals("pin_command_failed", request.await(0).getString("error"))
        assertEquals(1, count.get())
    }
}
