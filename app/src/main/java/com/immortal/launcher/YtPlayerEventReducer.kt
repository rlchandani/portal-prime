/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.YtEngineReducer.persisting

/**
 * Auto-skip policy for official player errors. Only per-video errors skip; anything else (for
 * example 153, a missing-Referer configuration error) would fail for every video, so it halts.
 */
object YtSkipPolicy {
  /** Stop after this many videos in a row fail instead of churning through the queue. */
  const val MAX_CONSECUTIVE_FAILURES = 3

  /** User-facing reason for a skippable IFrame error code, or null when it is not skippable. */
  fun reasonFor(code: Int): String? = when (code) {
    2 -> "Not a valid video"
    5 -> "Can’t play in this player"
    100 -> "Removed or private"
    101, 150 -> "The owner doesn’t allow playing it here"
    else -> null
  }
}

/** Reduces events reported by the official IFrame player (see [YtEngineReducer]). */
internal object YtPlayerEventReducer {
  fun reduce(s: YtEngineState, e: YtPlayerEvent): YtStep = when (e) {
    YtPlayerEvent.Ready -> ready(s)
    is YtPlayerEvent.StateChanged -> stateChanged(s, e.status)
    is YtPlayerEvent.Error -> error(s, e.code)
    is YtPlayerEvent.Progress -> YtStep(s.copy(
        queue = fillMeta(s.queue, e),
        positionMs = e.positionMs,
        durationMs = if (e.durationMs > 0) e.durationMs else s.durationMs,
        playerVideoId = e.videoId ?: s.playerVideoId,
        positionAtElapsedMs = e.elapsedMs))
    YtPlayerEvent.AutoplayBlocked -> YtStep(s.copy(autoplayBlocked = true))
    YtPlayerEvent.ApiUnavailable -> YtStep(s.copy(status = YtPlayerStatus.NOT_READY,
        wantsPlay = false, halt = YtHalt.PlayerUnavailable))
  }

  private fun fillMeta(queue: YtQueue, e: YtPlayerEvent.Progress): YtQueue {
    val id = e.videoId ?: return queue
    val title = e.title ?: return queue
    if (queue.current?.track?.videoId != id) return queue
    return queue.fillMeta(id, title, e.author.orEmpty())
  }

  /** The player exists: apply the current entry (paused unless the user asked to play). */
  private fun ready(s: YtEngineState): YtStep {
    val halt = s.halt.takeUnless { it == YtHalt.PlayerUnavailable }
    val readied = s.copy(status = YtPlayerStatus.UNSTARTED, halt = halt)
    val entry = s.queue.current ?: return YtStep(readied)
    val id = entry.track.videoId
    val play = s.wantsPlay && s.canPlay
    val effect = if (play) YtEffect.Load(id, s.positionMs) else YtEffect.Cue(id, s.positionMs)
    return YtStep(readied.copy(wantsPlay = play), listOf(effect))
  }

  private fun stateChanged(s: YtEngineState, status: YtPlayerStatus): YtStep {
    val next = s.copy(status = status)
    return when (status) {
      YtPlayerStatus.PLAYING, YtPlayerStatus.BUFFERING -> started(next, status)
      YtPlayerStatus.PAUSED -> YtStep(next.copy(wantsPlay = false))
      YtPlayerStatus.ENDED -> ended(next)
      else -> YtStep(next)
    }
  }

  /** Playing (possibly started from the player's own controls): mirror it, unless policy says no. */
  private fun started(s: YtEngineState, status: YtPlayerStatus): YtStep {
    if (!s.canPlay) return YtStep(s.copy(wantsPlay = false), listOf(YtEffect.Pause))
    if (status == YtPlayerStatus.BUFFERING) return YtStep(s)
    return YtStep(s.copy(wantsPlay = true, consecutiveFailures = 0, halt = null, autoplayBlocked = false))
  }

  private fun ended(s: YtEngineState): YtStep {
    val queue = s.queue.onEnded()
        ?: return YtStep(s.copy(wantsPlay = false, halt = YtHalt.EndOfQueue))
    if (queue.current?.id == s.queue.current?.id) {
      return YtStep(s.copy(positionMs = 0L), listOf(YtEffect.Seek(0), YtEffect.Play))
    }
    return YtEngineReducer.loadTrack(s, queue).persisting()
  }

  private fun error(s: YtEngineState, code: Int): YtStep {
    val reason = YtSkipPolicy.reasonFor(code) ?: return YtStep(
        s.copy(wantsPlay = false, halt = YtHalt.PlayerError(code)), listOf(YtEffect.Stop))
    val entry = s.queue.current ?: return YtStep(s)
    val failures = s.consecutiveFailures + 1
    val marked = s.copy(
        queue = s.queue.markUnplayable(entry.id, reason),
        consecutiveFailures = failures,
        lastSkip = YtSkipNotice((s.lastSkip?.seq ?: 0) + 1, entry.track.title, reason))
    val halt = if (failures >= YtSkipPolicy.MAX_CONSECUTIVE_FAILURES) {
      YtHalt.TooManyFailures(failures)
    } else null
    val next = if (halt == null) marked.queue.next() else null
    if (next == null) {
      val stopped = marked.copy(wantsPlay = false, halt = halt ?: YtHalt.EndOfQueue)
      return YtStep(stopped, listOf(YtEffect.Stop, YtEffect.Persist))
    }
    return YtEngineReducer.loadTrack(marked, next).persisting()
  }
}
