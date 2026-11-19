/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** Where "Leave a message" is: Ready → Recording → Review → Sending → Sent (or Failed). */
internal enum class ComposePhase { READY, RECORDING, REVIEW, SENDING, SENT, FAILED }

@Immutable
internal data class ComposeState(
    val phase: ComposePhase = ComposePhase.READY,
    /** Camera on records video; off records an audio message ("Camera off · audio message"). */
    val cameraOn: Boolean = true,
    val startedMs: Long? = null,
    val durationMs: Long = 0L,
    /** A recording exists on disk (sender temp) that must be deleted unless it was sent. */
    val hasTake: Boolean = false,
    val failure: String? = null,
) {
  fun elapsed(nowMs: Long): Long = startedMs?.let { (nowMs - it).coerceIn(0, CallMessageLimits.MAX_DURATION_MS) } ?: durationMs
}

internal sealed interface ComposeEvent {
  data object ToggleCamera : ComposeEvent
  data class Record(val now: Long) : ComposeEvent
  data class Stop(val now: Long) : ComposeEvent
  data class Tick(val now: Long) : ComposeEvent
  /** The recorder finished writing the file (or failed: [ok] false). */
  data class Recorded(val ok: Boolean, val durationMs: Long) : ComposeEvent
  data object RecordAgain : ComposeEvent
  data object Send : ComposeEvent
  data object SendOk : ComposeEvent
  data class SendFailed(val reason: String) : ComposeEvent
  /** Back, Close, or the screen went away: nothing is kept. */
  data object Discard : ComposeEvent
}

internal sealed interface ComposeEffect {
  data class OpenCamera(val on: Boolean) : ComposeEffect
  data class StartRecording(val cameraOn: Boolean) : ComposeEffect
  data object StopRecording : ComposeEffect
  data object CloseCamera : ComposeEffect
  data object DeleteTake : ComposeEffect
  data object Upload : ComposeEffect
  data object Finish : ComposeEffect
}

internal data class ComposeStep(val state: ComposeState, val effects: List<ComposeEffect> = emptyList())

/**
 * Pure rules for recording a message: the camera is open only on Ready and Recording (with the
 * visible self view), recording stops by itself at 2:00, a mis-tap under 1 s goes back to Ready,
 * the take is deleted on Record again, Discard, and after a confirmed send. JVM-tested.
 */
internal object CallMessageCompose {
  fun reduce(s: ComposeState, e: ComposeEvent): ComposeStep = when (e) {
    ComposeEvent.ToggleCamera -> if (s.phase == ComposePhase.READY)
      ComposeStep(s.copy(cameraOn = !s.cameraOn), listOf(ComposeEffect.OpenCamera(!s.cameraOn))) else ComposeStep(s)
    is ComposeEvent.Record -> if (s.phase == ComposePhase.READY)
      ComposeStep(s.copy(phase = ComposePhase.RECORDING, startedMs = e.now, durationMs = 0, hasTake = true, failure = null),
          listOf(ComposeEffect.StartRecording(s.cameraOn))) else ComposeStep(s)
    is ComposeEvent.Stop -> stop(s, e.now)
    is ComposeEvent.Tick -> if (s.phase == ComposePhase.RECORDING && s.startedMs != null &&
        e.now - s.startedMs >= CallMessageLimits.MAX_DURATION_MS) stop(s, e.now) else ComposeStep(s)
    is ComposeEvent.Recorded -> recorded(s, e)
    ComposeEvent.RecordAgain -> if (s.phase == ComposePhase.REVIEW || s.phase == ComposePhase.FAILED)
      ComposeStep(ComposeState(cameraOn = s.cameraOn), listOf(ComposeEffect.DeleteTake, ComposeEffect.OpenCamera(s.cameraOn)))
    else ComposeStep(s)
    ComposeEvent.Send -> if ((s.phase == ComposePhase.REVIEW || s.phase == ComposePhase.FAILED) && s.hasTake)
      ComposeStep(s.copy(phase = ComposePhase.SENDING, failure = null), listOf(ComposeEffect.Upload)) else ComposeStep(s)
    // Sent: the only copy left the sender. Delete the take before anything else.
    ComposeEvent.SendOk -> if (s.phase == ComposePhase.SENDING)
      ComposeStep(s.copy(phase = ComposePhase.SENT, hasTake = false), listOf(ComposeEffect.DeleteTake)) else ComposeStep(s)
    is ComposeEvent.SendFailed -> if (s.phase == ComposePhase.SENDING)
      ComposeStep(s.copy(phase = ComposePhase.FAILED, failure = e.reason)) else ComposeStep(s)
    ComposeEvent.Discard -> ComposeStep(s.copy(phase = ComposePhase.READY, startedMs = null, hasTake = false), buildList {
      if (s.phase == ComposePhase.RECORDING) add(ComposeEffect.StopRecording)
      add(ComposeEffect.CloseCamera)
      if (s.hasTake) add(ComposeEffect.DeleteTake)
      add(ComposeEffect.Finish)
    })
  }

  private fun stop(s: ComposeState, now: Long): ComposeStep {
    if (s.phase != ComposePhase.RECORDING || s.startedMs == null) return ComposeStep(s)
    val d = (now - s.startedMs).coerceIn(0, CallMessageLimits.MAX_DURATION_MS)
    return ComposeStep(s.copy(startedMs = null, durationMs = d), listOf(ComposeEffect.StopRecording))
  }

  private fun recorded(s: ComposeState, e: ComposeEvent.Recorded): ComposeStep {
    // Also arrives before Stop: the recorder's own 2:00 cap, or a recorder error.
    if (s.phase != ComposePhase.RECORDING) return ComposeStep(s)
    val d = if (e.durationMs > 0) e.durationMs else s.durationMs
    if (!e.ok || d < CallMessageLimits.MIN_DURATION_MS) {
      return ComposeStep(ComposeState(cameraOn = s.cameraOn, failure = if (e.ok) null else "recording_failed"),
          listOf(ComposeEffect.DeleteTake, ComposeEffect.OpenCamera(s.cameraOn)))
    }
    // Review shows the take, not the room: the camera closes until Record again.
    return ComposeStep(s.copy(phase = ComposePhase.REVIEW, startedMs = null, durationMs = d), listOf(ComposeEffect.CloseCamera))
  }
}
