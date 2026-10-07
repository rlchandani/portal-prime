/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedToolsAudioLifecycleTest {
    @Test fun hiddenPageCannotStartMicrophoneOrNarration() {
        val policy = ToolsAudioSessionPolicy()
        assertNull(policy.begin())
        policy.updateForeground(true)
        assertNotNull(policy.begin())
        policy.updateForeground(false)
        assertNull(policy.begin())
    }

    @Test fun permissionGrantedAfterHideAndReturnCannotRestartRecording() {
        val policy = ToolsAudioSessionPolicy()
        policy.updateForeground(true)
        val permissionRequest = policy.begin()!!
        policy.updateForeground(false)
        policy.updateForeground(true)
        assertFalse(policy.accepts(permissionRequest))
        val explicitRetry = policy.begin()!!
        assertTrue(policy.accepts(explicitRetry))
    }

    @Test fun delayedBroadcastStartupCannotPublishOverANewerListenRequest() {
        val policy = ToolsAudioSessionPolicy()
        policy.updateForeground(true)
        val broadcast = policy.begin()!!
        val listen = policy.begin()!!
        assertFalse(policy.accepts(broadcast))
        assertTrue(policy.accepts(listen))
    }

    @Test fun stoppedNarrationIgnoresDelayedStartAndCompletion() {
        val policy = ToolsAudioSessionPolicy()
        policy.updateForeground(true)
        val firstReading = policy.begin()!!
        policy.invalidate()
        assertFalse(policy.accepts(firstReading))
        val secondReading = policy.begin()!!
        assertFalse(policy.accepts(firstReading))
        assertTrue(policy.accepts(secondReading))
    }

    @Test fun foregroundRecompositionDoesNotRetireCurrentPlayback() {
        val policy = ToolsAudioSessionPolicy()
        policy.updateForeground(true)
        val playback = policy.begin()!!
        repeat(3) { policy.updateForeground(true) }
        assertTrue(policy.accepts(playback))
    }

    @Test fun disposedReaderCannotAcceptCallbacksOrRestartOnReturn() {
        val policy = ToolsAudioSessionPolicy()
        policy.updateForeground(true)
        val reading = policy.begin()!!
        policy.dispose()
        policy.updateForeground(true)
        assertFalse(policy.accepts(reading))
        assertNull(policy.begin())
    }
}
