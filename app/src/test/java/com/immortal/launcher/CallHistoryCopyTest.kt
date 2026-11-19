/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** Call history (30 days, local), copy from the mock, stats parsing and the ringtone synth. */
class CallHistoryCopyTest {
  private val peer = CallContact("22222222-2222-2222-2222-222222222222", "Kitchen")
  private val day = 24 * 60 * 60_000L
  private val now = 100 * day

  private fun entry(i: Int, at: Long, dir: CallDirection = CallDirection.INCOMING, reason: CallEndReason = CallEndReason.MISSED,
                    ms: Long = 0, kind: CallKind = CallKind.CALL) =
      CallHistoryEntry("%016x".format(i), peer.deviceId, peer.name, dir, kind, reason, at, ms)

  @Test fun addReplacesByIdKeepsNewestFirstAndDropsOldEntries() {
    var list = CallHistory.add(emptyList(), entry(1, now - 40 * day), now)
    assertTrue("older than 30 days is gone", list.isEmpty())
    list = CallHistory.add(list, entry(1, now - 2_000), now)
    list = CallHistory.add(list, entry(2, now - 1_000), now)
    list = CallHistory.add(list, entry(1, now - 500, reason = CallEndReason.HUNG_UP, ms = 60_000), now)
    assertEquals(listOf("%016x".format(1), "%016x".format(2)), list.map { it.id })
    assertEquals(CallEndReason.HUNG_UP, list.first().reason)
  }

  @Test fun missedCountsOnlyUnansweredIncoming() {
    assertTrue(entry(1, now).missed)
    assertTrue(entry(1, now, reason = CallEndReason.QUIET).missed)
    assertFalse(entry(1, now, reason = CallEndReason.DECLINED).missed)
    assertFalse(entry(1, now, dir = CallDirection.OUTGOING, reason = CallEndReason.NO_ANSWER).missed)
    assertFalse(entry(1, now, reason = CallEndReason.HUNG_UP, ms = 1_000).missed)
    val list = listOf(entry(1, now), entry(2, now - 1, reason = CallEndReason.QUIET), entry(3, now - 2, reason = CallEndReason.DECLINED))
    assertEquals(2, CallHistory.unseenMissed(list))
    assertEquals(0, CallHistory.unseenMissed(CallHistory.markSeen(list)))
    val seen = CallHistory.markSeen(list)
    assertSame("already seen: no copy", seen, CallHistory.markSeen(seen))
    assertEquals("%016x".format(1), CallHistory.latestUnseenMissed(list)!!.id)
  }

  @Test fun filtersAndCodec() {
    val list = listOf(entry(1, now), entry(2, now - 1, kind = CallKind.DROP_IN, reason = CallEndReason.HUNG_UP, ms = 5_000),
        entry(3, now - 2, dir = CallDirection.OUTGOING, reason = CallEndReason.HUNG_UP, ms = 9_000))
    assertEquals(1, CallHistory.filter(list, CallHistoryFilter.MISSED).size)
    assertEquals(1, CallHistory.filter(list, CallHistoryFilter.DROP_INS).size)
    assertEquals(list, CallHistory.decode(CallHistory.encode(list)))
    assertEquals(emptyList<CallHistoryEntry>(), CallHistory.decode("{bad"))
    assertEquals(1, CallHistory.decode(CallHistory.encode(list).replace("\"i\":\"0000000000000002\"", "\"i\":\"zz\"")
        .replace("\"i\":\"0000000000000003\"", "\"i\":\"yy\"")).size)
  }

  @Test fun endedCallsBecomeHistory() {
    val ended = EndedCall("0123456789abcdef", peer, CallDirection.OUTGOING, CallKind.CALL, CallEndReason.HUNG_UP, 1_000, 61_000)
    val e = CallHistory.of(ended)
    assertEquals(60_000L, e.durationMs)
    assertEquals(1_000L, e.atMs)
    assertTrue("your own calls are never 'new'", e.seen)
  }

  @Test fun liveBarAndTimers() {
    assertEquals("Camera on · Mic on", CallingCopy.liveBar(true, true))
    assertEquals("Camera off · Mic muted", CallingCopy.liveBar(false, false))
    assertFalse(CallingCopy.liveDotActive(false, false))
    assertEquals("4:12", CallingCopy.clock(252_000))
    assertEquals("1:04:12", CallingCopy.clock(3_852_000))
    assertEquals("0:00", CallingCopy.clock(-5))
    assertEquals("2:14 of 15:00", CallingCopy.capProgress(134_000, 900_000))
    assertEquals("Under a minute", CallingCopy.duration(30_000))
    assertEquals("24 min", CallingCopy.duration(24 * 60_000L))
    assertEquals("1 h 5 min", CallingCopy.duration(65 * 60_000L))
  }

  @Test fun ringScreensSayWhatHappens() {
    val call = ActiveCall("0123456789abcdef", peer, CallDirection.OUTGOING, CallKind.DROP_IN, true, CallPhase.COUNTDOWN, 0, 0, cameraOn = true)
    assertEquals("Dropping in on Kitchen", CallingCopy.outgoingTitle(CallKind.DROP_IN, false, "Kitchen"))
    assertEquals("Asking Kitchen", CallingCopy.outgoingTitle(CallKind.DROP_IN, true, "Kitchen"))
    assertEquals("Calling Kitchen", CallingCopy.outgoingTitle(CallKind.CALL, false, "Kitchen"))
    assertEquals("Camera on · Mic on · joining in 3 s", CallingCopy.outgoingLiveBar(call, 3))
    assertEquals("Camera off · Mic off until they say yes", CallingCopy.outgoingLiveBar(call.copy(phase = CallPhase.RINGING), 3))
    assertEquals("Camera preview · only you can see it", CallingCopy.outgoingLiveBar(call.copy(kind = CallKind.CALL), 3))
    assertEquals("Audio call · camera off", CallingCopy.outgoingLiveBar(call.copy(kind = CallKind.CALL, cameraOn = false), 3))
    assertTrue(CallingCopy.outgoingSub(call.copy(phase = CallPhase.DIALING)).startsWith("Reaching"))
    assertEquals("Kitchen is dropping in", CallingCopy.dropInBannerTitle(false, "Kitchen"))
    assertEquals("Chime played. Camera and mic turn on in 2 s.", CallingCopy.dropInBannerSub(false, 2))
    assertEquals("Drop-in from Kitchen · Camera on · Mic on", CallingCopy.dropInBar("Kitchen", true, true, true))
    assertEquals("Video call from Kitchen", CallingCopy.incomingSub(CallKind.CALL, true, "Kitchen"))
  }

  @Test fun everyEndingHasHonestCopy() {
    CallEndReason.entries.forEach { r ->
      val c = CallingCopy.ended(EndedCall("0123456789abcdef", peer, CallDirection.OUTGOING, CallKind.CALL, r, null, 1_000, 420),
          "4:36 PM", "7:00")
      assertTrue(r.name, c.title.isNotBlank() && c.body.isNotBlank())
    }
    val quiet = CallingCopy.ended(EndedCall("0123456789abcdef", peer, CallDirection.OUTGOING, CallKind.CALL, CallEndReason.QUIET,
        null, 0, 420), "", "7:00")
    assertEquals("Kitchen is in quiet hours", quiet.title)
    assertTrue(quiet.body.contains("until 7:00"))
    val done = CallingCopy.ended(EndedCall("0123456789abcdef", peer, CallDirection.OUTGOING, CallKind.CALL, CallEndReason.HUNG_UP,
        0, 24 * 60_000L), "4:36 PM", null)
    assertEquals("24 min · 4:36 PM · direct between Portals, nothing stored", done.body)
  }

  @Test fun historyLinesDaysAndLabels() {
    assertEquals("2:14 PM · quiet hours, nothing rang", CallingCopy.historyLine(entry(1, now, reason = CallEndReason.QUIET), "2:14 PM"))
    assertEquals("x · drop-in · 3 min", CallingCopy.historyLine(entry(1, now, kind = CallKind.DROP_IN, reason = CallEndReason.HUNG_UP,
        ms = 180_000), "x"))
    val utc = TimeZone.getTimeZone("UTC")
    assertEquals("Today", CallingCopy.historyDay(now, now, utc))
    assertEquals("Yesterday", CallingCopy.historyDay(now - day, now, utc))
    assertEquals("Earlier", CallingCopy.historyDay(now - 3 * day, now, utc))
    assertEquals("7:00 AM", CallingCopy.minuteLabel(420, false))
    assertEquals("12:30 PM", CallingCopy.minuteLabel(750, false))
    assertEquals("22:00", CallingCopy.minuteLabel(1320, true))
    assertEquals("LA", CallingCopy.initials("Living Area"))
    assertEquals("K", CallingCopy.initials("Kitchen"))
    assertEquals("?", CallingCopy.initials(" "))
  }

  @Test fun statsParserComputesRatesAndLatency() {
    val stats = listOf(
        RawStat("codec", "c1", mapOf("mimeType" to "video/H264")),
        RawStat("outbound-rtp", "o", mapOf("kind" to "video", "framesPerSecond" to 29.9, "bytesSent" to java.math.BigInteger.valueOf(500_000),
            "frameWidth" to 1280L, "frameHeight" to 720L, "encoderImplementation" to "OMX.qcom.video.encoder.avc", "codecId" to "c1",
            "qualityLimitationReason" to "none")),
        RawStat("inbound-rtp", "i", mapOf("kind" to "video", "framesPerSecond" to 30.0, "bytesReceived" to java.math.BigInteger.valueOf(400_000),
            "jitterBufferDelay" to 3.0, "jitterBufferEmittedCount" to java.math.BigInteger.valueOf(100))),
        RawStat("candidate-pair", "p", mapOf("state" to "succeeded", "nominated" to true, "currentRoundTripTime" to 0.008)),
    )
    val prev = CallStats(atMs = 0, bytesSent = 250_000, bytesReceived = 150_000)
    val s = CallStatsParser.parse(stats, 2_000, prev)
    assertEquals(1000, s.sendKbps)
    assertEquals(1000, s.recvKbps)
    assertEquals(8, s.rttMs)
    assertEquals(30, s.jitterBufferMs)
    assertEquals("video/H264", s.codec)
    assertEquals(1280, s.sendWidth)
    assertEquals(4 + 30 + CallStats.PIPELINE_ALLOWANCE_MS, s.latencyEstimateMs)
    assertTrue(s.logLine().contains("1280x720"))
    val first = CallStatsParser.parse(stats, 2_000, null)
    assertEquals(0, first.sendKbps)
    assertNull(CallStatsParser.parse(emptyList(), 0, null).latencyEstimateMs)
  }

  @Test fun ringtonesAreLoopableAndNeverClip() {
    CallRingtone.entries.forEach { tone ->
      val pcm = CallingRingtones.render(tone)
      assertTrue(tone.name, pcm.size in CallingRingtones.SAMPLE_RATE * 2..CallingRingtones.SAMPLE_RATE * 4)
      val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
      assertTrue(tone.name, peak <= (CallingRingtones.PEAK * Short.MAX_VALUE).toInt() + 1)
      assertTrue(tone.name, peak > Short.MAX_VALUE / 4)
      assertTrue("${tone.name} loop point fades to silence", kotlin.math.abs(pcm.last().toInt()) < 200)
    }
    assertTrue(CallingRingtones.chime().size < CallingRingtones.SAMPLE_RATE * 2)
  }
}
