/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Video-message copy: every compose phase for Portal and phone recipients, failures, sizes, list lines. */
class CallMessageCopyCoverageTest {
  private fun msg(audioOnly: Boolean, durationMs: Long = 48_000) =
      CallMessage("0123456789abcdef", "peer", "Kitchen", 0L, durationMs, 1_000, audioOnly = audioOnly)

  @Test fun subCoversEveryPhaseForAPortalRecipient() {
    assertEquals("Up to 2 minutes · they’ll get it on their Portal", CallMessageCopy.sub(ComposePhase.READY, null))
    assertEquals("Recording · camera and mic on", CallMessageCopy.sub(ComposePhase.RECORDING, null))
    assertEquals("Only you have seen this", CallMessageCopy.sub(ComposePhase.REVIEW, null))
    assertEquals("Sending over your home Wi-Fi…", CallMessageCopy.sub(ComposePhase.SENDING, null))
    assertEquals("Sent · it’s gone from this Portal", CallMessageCopy.sub(ComposePhase.SENT, null))
    assertEquals("That Portal has too many messages waiting", CallMessageCopy.sub(ComposePhase.FAILED, "full"))
  }

  @Test fun subUsesPhoneWordingWhenTheRecipientIsAPhone() {
    assertEquals("Up to 2 minutes · they’ll get it in Portal Toolkit", CallMessageCopy.sub(ComposePhase.READY, null, phone = true))
    assertEquals("Sealing it for their phone…", CallMessageCopy.sub(ComposePhase.SENDING, null, phone = true))
    assertEquals("Sent · their phone collects it on the home Wi-Fi", CallMessageCopy.sub(ComposePhase.SENT, null, phone = true))
    assertEquals("Too many messages are waiting for that phone already",
        CallMessageCopy.sub(ComposePhase.FAILED, CallMessageRefusal.FULL.code, phone = true))
    // A non-FULL failure to a phone falls back to the generic failure copy.
    assertEquals("That Portal is out of space", CallMessageCopy.sub(ComposePhase.FAILED, "no_space", phone = true))
    // Phase words that don't depend on the recipient stay the same.
    assertEquals("Only you have seen this", CallMessageCopy.sub(ComposePhase.REVIEW, null, phone = true))
  }

  @Test fun failureMapsEveryRefusalAndSenderReason() {
    assertEquals("They’ve turned video messages off on that Portal", CallMessageCopy.failure("messages_off"))
    assertEquals("That Portal has too many messages waiting", CallMessageCopy.failure("full"))
    assertEquals("That Portal is out of space", CallMessageCopy.failure("no_space"))
    assertEquals("That message is too long to send", CallMessageCopy.failure("too_large"))
    assertEquals("That message is too long to send", CallMessageCopy.failure("too_long"))
    assertEquals("That message was already sent", CallMessageCopy.failure("duplicate"))
    assertEquals("That Portal is taking another message. Try again in a moment", CallMessageCopy.failure("busy"))
    assertEquals("The recording didn’t work. Try again", CallMessageCopy.failure("recording_failed"))
    assertEquals("Couldn’t reach that Portal. Is it on the home Wi-Fi?", CallMessageCopy.failure("no_address"))
    assertEquals("Couldn’t reach that Portal. Is it on the home Wi-Fi?", CallMessageCopy.failure("unreachable"))
    assertEquals("It didn’t send. Your message is still here", CallMessageCopy.failure("something_new"))
    assertEquals("It didn’t send. Your message is still here", CallMessageCopy.failure(null))
  }

  @Test fun liveBarReflectsRecordingAndCamera() {
    assertEquals("Recording · Camera on · Mic on", CallMessageCopy.liveBar(ComposePhase.RECORDING, cameraOn = true))
    assertEquals("Recording · Camera off · Mic on", CallMessageCopy.liveBar(ComposePhase.RECORDING, cameraOn = false))
    assertEquals("Camera on · Mic off", CallMessageCopy.liveBar(ComposePhase.READY, cameraOn = true))
    assertEquals("Camera off · Mic off", CallMessageCopy.liveBar(ComposePhase.REVIEW, cameraOn = false))
  }

  @Test fun storedLineCountsNoneOneAndMany() {
    assertEquals("None right now", CallMessageCopy.storedLine(0, 0))
    assertEquals("1 message · 2 KB", CallMessageCopy.storedLine(1, 1_025))
    assertTrue(CallMessageCopy.storedLine(3, 41L * 1024 * 1024).matches(Regex("3 messages · 41[.,]0 MB")))
  }

  @Test fun sizeRoundsKilobytesUpAndSwitchesToMegabytesAtOneMb() {
    assertEquals("0 KB", CallMessageCopy.size(0))
    assertEquals("1 KB", CallMessageCopy.size(1))
    assertEquals("1 KB", CallMessageCopy.size(1_024))
    assertEquals("1024 KB", CallMessageCopy.size(1024L * 1024 - 1))
    assertTrue(CallMessageCopy.size(1024L * 1024).matches(Regex("1[.,]0 MB")))
    assertTrue(CallMessageCopy.size(1024L * 1024 * 3 / 2).matches(Regex("1[.,]5 MB")))
  }

  @Test fun titlesStopLabelAndSentLine() {
    assertEquals("Message for Gran", CallMessageCopy.title("Gran"))
    assertEquals("Stop · 0:48 of 2:00", CallMessageCopy.stopLabel(48_000))
    assertEquals("Message sent to Gran · end-to-end encrypted", CallMessageCopy.sent("Gran"))
  }

  @Test fun kindAndLineDistinguishAudioFromVideo() {
    assertEquals("Video message · 0:48", CallMessageCopy.kind(msg(audioOnly = false)))
    assertEquals("Audio message · 1:05", CallMessageCopy.kind(msg(audioOnly = true, durationMs = 65_000)))
    assertEquals("2:14 PM · left a 0:48 video message", CallMessageCopy.line(msg(audioOnly = false), "2:14 PM"))
    assertEquals("2:14 PM · left a 0:48 audio message", CallMessageCopy.line(msg(audioOnly = true), "2:14 PM"))
  }
}
