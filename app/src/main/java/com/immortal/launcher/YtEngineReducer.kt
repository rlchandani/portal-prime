/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Pure reducer for the official-player music engine: `(state, action) -> (state, effects)`.
 * User commands live here; official player events are handled by [YtPlayerEventReducer].
 *
 * Policy encoded here (not in the UI): nothing starts playing unless the music screen is in the
 * foreground and the player is at least the minimum size; leaving the foreground pauses and only
 * a user action resumes.
 */
object YtEngineReducer {
  fun reduce(s: YtEngineState, a: YtAction): YtStep = when (a) {
    is YtPlayerEvent -> YtPlayerEventReducer.reduce(s, a)
    is YtAction.PlayNow -> loadTrack(s.copy(wantsPlay = s.canPlay),
        s.queue.playNow(a.tracks, a.startIndex, a.label)).persisting()
    is YtAction.PlayNext -> enqueue(s, s.queue.playNext(a.track))
    is YtAction.AddToEnd -> enqueue(s, s.queue.addToEnd(a.track))
    is YtAction.Remove -> remove(s, a.entryId)
    is YtAction.Move -> queueOnly(s, s.queue.move(a.from, a.to))
    is YtAction.Select -> navigate(s, s.queue.select(a.entryId))
    YtAction.Next -> s.queue.next()?.let { navigate(s, it) } ?: YtStep(s)
    YtAction.Previous -> previous(s)
    YtAction.Play -> play(s)
    YtAction.Pause -> pause(s.copy(playWhenVisible = false))
    is YtAction.SeekTo -> seek(s, a.positionMs)
    is YtAction.SetRepeat -> queueOnly(s, s.queue.withRepeat(a.mode))
    is YtAction.SetShuffle -> queueOnly(s, if (a.on) s.queue.shuffled(a.seed) else s.queue.unshuffled())
    YtAction.Clear -> YtStep(clearedTrack(s.copy(queue = s.queue.clear(), wantsPlay = false, playWhenVisible = false)),
        listOf(YtEffect.Stop, YtEffect.Persist))
    is YtAction.Restore -> restore(s, a)
    is YtAction.Adopt -> adopt(s, a)
    is YtAction.ApplyShared -> if (a.restart) adopt(s, YtAction.Adopt(a.queue, 0)) else queueOnly(s, a.queue)
    is YtAction.Foreground -> foreground(s, a.visible)
    is YtAction.SizeChanged -> size(s, a.ok)
    YtAction.PlayerLost -> YtStep(
        s.copy(status = YtPlayerStatus.NOT_READY, wantsPlay = false, autoplayBlocked = false),
        listOf(YtEffect.Persist))
  }

  /**
   * Makes [queue]'s current entry the player's item: Load (autoplay) when the user wants play and
   * policy allows it, Cue otherwise, nothing before the player is ready (Ready applies it), Stop
   * when the queue is empty.
   */
  internal fun loadTrack(s: YtEngineState, queue: YtQueue): YtStep {
    val start = queue.current?.resumeMs?.coerceAtLeast(0L) ?: 0L
    val consumed = queue.current?.let { queue.consumeResume(it.id) } ?: queue
    val base = clearedTrack(s.copy(queue = consumed)).copy(positionMs = start,
        durationMs = queue.current?.track?.durationMs ?: 0L)
    val entry = queue.current ?: return YtStep(base.copy(wantsPlay = false), listOf(YtEffect.Stop))
    if (!s.ready) return YtStep(base)
    val id = entry.track.videoId
    val effect = if (base.wantsPlay && base.canPlay) YtEffect.Load(id, start) else YtEffect.Cue(id, start)
    return YtStep(base, listOf(effect))
  }

  internal fun YtStep.persisting(): YtStep = copy(effects = effects + YtEffect.Persist)

  private fun clearedTrack(s: YtEngineState) = s.copy(positionMs = 0L, durationMs = 0L,
      playerVideoId = null, halt = null, autoplayBlocked = false)

  private fun navigate(s: YtEngineState, queue: YtQueue): YtStep =
      loadTrack(s.copy(wantsPlay = s.canPlay), queue).persisting()

  private fun queueOnly(s: YtEngineState, queue: YtQueue): YtStep =
      if (queue === s.queue) YtStep(s) else YtStep(s.copy(queue = queue), listOf(YtEffect.Persist))

  private fun enqueue(s: YtEngineState, queue: YtQueue): YtStep =
      if (s.queue.isEmpty && !queue.isEmpty) navigate(s, queue) else queueOnly(s, queue)

  private fun remove(s: YtEngineState, entryId: Long): YtStep {
    val queue = s.queue.remove(entryId)
    if (queue.current?.id == s.queue.current?.id) return queueOnly(s, queue)
    return loadTrack(s, queue).persisting()
  }

  private fun previous(s: YtEngineState): YtStep {
    val queue = s.queue.previous(s.positionMs)
    if (queue !== s.queue) return navigate(s, queue)
    if (!s.ready || s.queue.current == null) return YtStep(s)
    return YtStep(s.copy(positionMs = 0L), listOf(YtEffect.Seek(0)))
  }

  private fun play(s: YtEngineState): YtStep {
    val entry = s.queue.current ?: return YtStep(s)
    if (!s.canPlay) return YtStep(s)
    val wanting = s.copy(wantsPlay = true)
    return when {
      s.halt != null -> loadTrack(wanting, s.queue.select(entry.id))
      !s.ready -> YtStep(wanting)
      s.status == YtPlayerStatus.ENDED -> YtStep(wanting.copy(positionMs = 0L),
          listOf(YtEffect.Seek(0), YtEffect.Play))
      else -> YtStep(wanting, listOf(YtEffect.Play))
    }
  }

  private fun pause(s: YtEngineState): YtStep {
    val paused = s.copy(wantsPlay = false)
    return YtStep(paused, if (s.ready) listOf(YtEffect.Pause, YtEffect.Persist) else listOf(YtEffect.Persist))
  }

  private fun seek(s: YtEngineState, positionMs: Long): YtStep {
    if (!s.ready || s.queue.current == null) return YtStep(s)
    val max = if (s.durationMs > 0) s.durationMs else Long.MAX_VALUE
    val target = positionMs.coerceIn(0L, max)
    return YtStep(s.copy(positionMs = target), listOf(YtEffect.Seek(target)))
  }

  private fun restore(s: YtEngineState, a: YtAction.Restore): YtStep {
    val entry = a.queue.current
    if (!s.queue.isEmpty || entry == null) return YtStep(s)
    val position = a.positionMs.coerceAtLeast(0L)
    val restored = s.copy(queue = a.queue, positionMs = position, wantsPlay = false,
        durationMs = entry.track.durationMs ?: 0L)
    val effects = if (s.ready) listOf(YtEffect.Cue(entry.track.videoId, position)) else emptyList()
    return YtStep(restored, effects)
  }

  /**
   * A queue moved here ("Move to…" on another Portal): replaces ours and resumes at the sent
   * position. It plays at once when the screen is visible, else cues and starts on [foreground].
   */
  private fun adopt(s: YtEngineState, a: YtAction.Adopt): YtStep {
    val entry = a.queue.current ?: return YtStep(s)
    val position = a.positionMs.coerceAtLeast(0L)
    val playNow = s.canPlay
    val adopted = clearedTrack(s.copy(queue = a.queue)).copy(positionMs = position, consecutiveFailures = 0,
        durationMs = entry.track.durationMs ?: 0L, wantsPlay = playNow, playWhenVisible = !playNow)
    val id = entry.track.videoId
    val load = when {
      !s.ready -> emptyList()
      playNow -> listOf(YtEffect.Load(id, position))
      else -> listOf(YtEffect.Cue(id, position))
    }
    return YtStep(adopted, load + YtEffect.Persist)
  }

  private fun foreground(s: YtEngineState, visible: Boolean): YtStep {
    if (visible && s.playWhenVisible) return play(s.copy(foreground = true, playWhenVisible = false))
    if (visible) return YtStep(s.copy(foreground = true))
    val hidden = s.copy(foreground = false, wantsPlay = false)
    val active = s.ready && (s.isPlaying || s.wantsPlay)
    return YtStep(hidden, if (active) listOf(YtEffect.Pause, YtEffect.Persist) else listOf(YtEffect.Persist))
  }

  private fun size(s: YtEngineState, ok: Boolean): YtStep {
    if (ok || !(s.ready && (s.isPlaying || s.wantsPlay))) return YtStep(s.copy(sizeOk = ok))
    return YtStep(s.copy(sizeOk = false, wantsPlay = false), listOf(YtEffect.Pause))
  }
}

/** Policy gate: playback only while the screen is visible and the player is large enough. */
internal val YtEngineState.canPlay: Boolean get() = foreground && sizeOk
