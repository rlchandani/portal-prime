/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.immortal.launcher.youtube.YtAndroid
import java.util.TimeZone
import org.json.JSONObject

/**
 * `/remote/ytmusic`: the paired phone's Music page drives the official YouTube player queue
 * ([YouTubeMusicEngine]). Session auth, body limit and JSON parsing happen upstream in
 * [RemoteCompanionRoutes]; the wire format lives in [YtRemoteProtocol].
 *
 * POST answers `{ok, status: done|opening|quiet|declined, quietUntil?, reason?}`; 400 for a bad
 * command, 409 `unknown_entry`. Play never starts hidden: off screen it opens the music screen
 * ([MusicHandoff.openMusicScreen]) and the engine starts once the player is visible. After a
 * restart play first restores the saved queue; with nothing saved it is declined `nothing_queued`.
 * Quiet hours answer `quiet` unless `playAnyway`, which plays at the share sheet's quiet volume.
 * Volume itself stays on the existing `/remote/volume`; GET reports it so it works when idle.
 * History, suggestions and search live on their own sub-routes ([YtRemoteLibraryRoutes]).
 */
internal object YtRemoteRoute : RemoteFeatureRoutes.Route {
  const val PATH = "/remote/ytmusic"
  private const val TAG = "HearthpaneYtRemote"

  fun install() {
    RemoteFeatureRoutes.register(PATH, this)
    YtRemoteLibraryRoutes.install()
  }

  override fun handle(context: Context, method: String, body: JSONObject): FleetHttpServer.Response {
    // After a restart the saved queue only loads when Music opens. Load it (never plays) so the
    // phone sees the real queue from its first poll and can jump/remove/play-next entries.
    if (method == "GET" || method == "POST") YouTubeMusicEngine.restoreSaved(context)
    return when (method) {
      "GET" -> RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("player", snapshot(context)))
      "POST" -> post(context, body)
      else -> RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    }
  }

  private fun snapshot(context: Context): JSONObject =
      YtRemoteProtocol.state(YouTubeMusicEngine.state.value, SystemClock.elapsedRealtime(), room(context))

  private fun room(context: Context): YtRemoteRoom {
    val chime = ChimeConfig.load(context)
    val quiet = DinnerQuiet.isQuiet(chime, System.currentTimeMillis(), TimeZone.getDefault())
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val volume = audio?.let { runCatching { RemoteMedia.volumeState(it) }
        .onFailure { e -> Log.w(TAG, "volume read failed: ${e.javaClass.simpleName}") }.getOrNull() }
    val account = runCatching { YtAndroid.library(context).activeAccount.value?.title }
        .onFailure { e -> Log.w(TAG, "account read failed: ${e.javaClass.simpleName}") }.getOrNull()
    return YtRemoteRoom(quiet, if (quiet) MusicSharePolicy.clock(chime.quietEndMin) else null,
        RoutineScreenState.ownsScreen(), RoutineScheduler.busy(context), account?.ifBlank { null }, volume,
        MusicNightGuard.state(context, quiet))
  }

  private fun post(context: Context, body: JSONObject): FleetHttpServer.Response {
    val command = when (val decoded = YtRemoteProtocol.decode(body)) {
      is YtRemoteProtocol.Decoded.Invalid -> return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error(decoded.code))
      is YtRemoteProtocol.Decoded.Ok -> decoded.command
    }
    YtRemoteProtocol.conflict(command, YouTubeMusicEngine.state.value)?.let {
      return RemoteFeatureRoutes.json(409, RemoteFeatureRoutes.error(it))
    }
    val restore = command is YtRemoteCommand.Play && when (playPlan(context)) {
      YtRemotePlayPlan.NOTHING_QUEUED -> return RemoteFeatureRoutes.json(200, YtRemoteProtocol.nothingQueued())
      YtRemotePlayPlan.RESTORE_THEN_PLAY -> true
      YtRemotePlayPlan.PLAY -> false
    }
    if (!command.startsPlayback) {
      apply(command)
      return RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("status", "done"))
    }
    val room = room(context)
    return when (val decision = MusicSharePolicy.decide(room.quiet, room.routine, room.inCall, command.playAnyway)) {
      MusicSharePolicy.Decision.QUIET -> RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok()
          .put("status", "quiet").put("quietUntil", room.quietUntil ?: JSONObject.NULL))
      MusicSharePolicy.Decision.ROUTINE, MusicSharePolicy.Decision.CALL -> RemoteFeatureRoutes.json(200,
          RemoteFeatureRoutes.ok().put("status", "declined")
              .put("reason", MusicSharePolicy.reason(decision, DinnerCall.selfName(context)) ?: JSONObject.NULL))
      MusicSharePolicy.Decision.ACCEPT, MusicSharePolicy.Decision.ACCEPT_QUIETLY -> {
        if (decision == MusicSharePolicy.Decision.ACCEPT_QUIETLY) lowerVolume(context)
        // Posted to the main thread ahead of PlayWhenVisible, so the restored entry is the one played.
        if (restore) YouTubeMusicEngine.restoreSaved(context)
        apply(command)
        val opening = !YouTubeMusicEngine.state.value.foreground
        if (opening) MusicHandoff.openMusicScreen(context)
        RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("status", if (opening) "opening" else "done")
            .put("quietly", decision == MusicSharePolicy.Decision.ACCEPT_QUIETLY))
      }
    }
  }

  private fun playPlan(context: Context): YtRemotePlayPlan {
    val engineHasCurrent = YouTubeMusicEngine.state.value.queue.current != null
    val savedHasCurrent = !engineHasCurrent && YtQueueStore(context).load()?.queue?.current != null
    return YtRemoteProtocol.playPlan(engineHasCurrent, savedHasCurrent)
  }

  /** Engine calls post to the main thread in order, so Select lands before PlayWhenVisible. */
  private fun apply(command: YtRemoteCommand) {
    when (command) {
      is YtRemoteCommand.Play -> YouTubeMusicEngine.dispatch(YtAction.PlayWhenVisible)
      YtRemoteCommand.Pause -> YouTubeMusicEngine.pause()
      YtRemoteCommand.Next -> YouTubeMusicEngine.next()
      YtRemoteCommand.Previous -> YouTubeMusicEngine.previous()
      is YtRemoteCommand.Seek -> YouTubeMusicEngine.seekTo(command.positionMs)
      is YtRemoteCommand.Jump -> {
        YouTubeMusicEngine.select(command.entryId)
        YouTubeMusicEngine.dispatch(YtAction.PlayWhenVisible)
      }
      is YtRemoteCommand.Remove -> YouTubeMusicEngine.remove(command.entryId)
      is YtRemoteCommand.PlayNext -> YouTubeMusicEngine.dispatch(YtAction.PlayEntryNext(command.entryId))
      is YtRemoteCommand.PlayTrack -> {
        YouTubeMusicEngine.playTrackNow(command.track, YtRemoteProtocol.PHONE_LABEL)
        YouTubeMusicEngine.dispatch(YtAction.PlayWhenVisible)
      }
      is YtRemoteCommand.QueueTrack ->
        if (command.next) YouTubeMusicEngine.playNext(command.track) else YouTubeMusicEngine.addToEnd(command.track)
      is YtRemoteCommand.Sleep -> MusicSleepDriver.set(command.choice)
    }
  }

  /** Same cap as "Play anyway, quietly" for a shared song: never louder than now. */
  private fun lowerVolume(context: Context) {
    val volume = MusicVolume(context)
    val now = volume.read()
    volume.setStep(MusicSharePolicy.quietStep(now.step, now.maxStep))
  }
}
