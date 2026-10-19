/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** One state owner across presentations; observing/configuring never owns or resumes audio. */
internal class MusicPlayerController(context: Context) {
  private val context = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  val store = MusicPlayerStore()
  private var attached = false
  private var source = ""
  private var generation = 0L
  private var requestId = 0L
  var sourceRevision = MusicSourceRevisions.next()
    private set
  private val pendingTimeout = Runnable { if (attached) expirePending() }
  private var ma: MaMusicClient? = null
  private var maGeneration = -1L
  private var maConfig: MaMusicClient.Config? = null
  private var stopObserving: (() -> Unit)? = null
  private val reload = Runnable { if (attached) reloadConnection() }
  private val listener = NowPlayingHub.Listener { _ ->
    main.post { if (attached && ma == null) publishLocal() }
  }

  fun attach() {
    if (attached) return
    attached = true
    reloadConnection()
    stopObserving = ImmortalSettings.observeMusicConnection(context) {
      // A registry Apply can change several keys; connect once from its final snapshot.
      main.removeCallbacks(reload)
      main.post(reload)
    }
    NowPlayingHub.addListener(listener)
  }

  fun detach() {
    if (!attached) return
    attached = false
    stopObserving?.invoke(); stopObserving = null
    NowPlayingHub.removeListener(listener)
    closeMa()
    main.removeCallbacksAndMessages(null)
    selectSource("")
  }

  private fun selectSource(next: String) {
    source = next
    sourceRevision = MusicSourceRevisions.next()
    generation = store.selectSource(next, sourceRevision)
  }

  private fun armPendingTimeout() {
    main.removeCallbacks(pendingTimeout)
    main.postDelayed(pendingTimeout, 5_000)
  }

  private fun closeMa() {
    val prior = ma
    ma = null; maGeneration = -1; maConfig = null
    prior?.close()
  }

  private fun publishLocal() {
    val next = musicSessionSnapshot(NowPlayingHub.current, SystemClock.elapsedRealtime())
    if (source != next.sourceId) {
      selectSource(next.sourceId)
    }
    store.publish(generation, next.copy(presentationRevision = sourceRevision))
  }

  private fun reloadConnection() {
    val saved = synchronized(com.immortal.launcher.settings.SettingsDomains.immortal) {
      ImmortalSettings.load(context)
    }
    if (!saved.multiRoomEnabled || saved.snapcastHost.isBlank()) {
      if (ma != null) closeMa()
      publishLocal()
      return
    }
    val config = MaMusicClient.Config(saved.snapcastHost, saved.maPort,
        saved.maApiToken, saved.maPlayerId)
    if (config == maConfig && ma != null) return
    selectSource(config.sourceId)
    if (ma == null) {
      lateinit var client: MaMusicClient
      client = MaMusicClient(onSelection = { epoch, selected ->
        main.post {
          if (attached && ma === client && epoch == maGeneration)
            store.publish(generation, selected.snapshot.copy(presentationRevision = sourceRevision))
        }
      }, onConnection = { epoch, connection ->
        main.post {
          if (attached && ma === client && epoch == maGeneration && connection != MusicConnection.READY) {
            // READY always arrives with a fresh Selection; never confirm from a stale copy.
            store.publish(generation, store.current.copy(connection = connection, presentationRevision = sourceRevision))
          }
        }
      })
      ma = client
    }
    maConfig = config
    maGeneration = ma!!.config(config)
  }

  /** After uncertain completion, read fresh server state; never automatically resend. */
  fun expirePending() {
    if (store.expirePending(SystemClock.elapsedRealtime()) && ma != null) {
      closeMa()
      reloadConnection()
    }
  }

  fun reconcileConnection() {
    if (attached) reloadConnection()
  }

  fun acceptsRendered(rendered: MusicPlayerSnapshot, action: MusicPlayerUiAction): Boolean =
      synchronized(com.immortal.launcher.settings.SettingsDomains.immortal) {
        reconcileConnection()
        musicRenderedActionIsCurrent(rendered, store.current, sourceRevision, action)
      }

  fun send(action: MusicPlayerUiAction, rendered: MusicPlayerSnapshot? = null): Boolean =
      synchronized(com.immortal.launcher.settings.SettingsDomains.immortal) {
        val observedRevision = rendered?.presentationRevision ?: sourceRevision
        reconcileConnection()
        if (observedRevision != sourceRevision || (rendered != null &&
            !musicRenderedActionIsCurrent(rendered, store.current, sourceRevision, action))) false
        else sendCurrent(action)
      }

  private fun sendCurrent(action: MusicPlayerUiAction): Boolean {
    val snapshot = store.current
    if (action is MusicPlayerUiAction.SelectPlayer) {
      val room = snapshot.rooms.firstOrNull { it.id == action.roomId } ?: return false
      if (!musicRoomActions(snapshot, room).canSelect) return false
      val applied = "maPlayerId" in com.immortal.launcher.settings.SettingsDomains.immortal.apply(context,
          org.json.JSONObject().put("maPlayerId", room.id.removePrefix("ma:")))
      if (applied) reconcileConnection()
      return applied
    }
    val client = ma
    if (client != null) {
      val dispatch = musicMaDispatch(snapshot, action) ?: return false
      val id = ++requestId
      val token = generation
      if (!store.beginCommand(id, dispatch.command, SystemClock.elapsedRealtime(), dispatch.confirmation)) return false
      armPendingTimeout()
      val trackId = snapshot.track?.id.takeIf {
        dispatch.command in setOf(MusicCommand.PLAY, MusicCommand.PAUSE, MusicCommand.NEXT,
            MusicCommand.PREVIOUS, MusicCommand.SEEK)
      }
      val accepted = client.command(dispatch.action, maGeneration, expectedTrackId = trackId) { result ->
        main.post {
          if (attached && ma === client && token == generation) {
            when (result.outcome) {
              MaMusicClient.Outcome.ACKNOWLEDGED -> store.acknowledge(token, id)
              MaMusicClient.Outcome.REFRESHED -> result.selection?.let { store.publish(token, it.snapshot.copy(presentationRevision = sourceRevision)) }
              MaMusicClient.Outcome.REJECTED, MaMusicClient.Outcome.FAILED ->
                store.reject(token, id, result.problem ?: "The player could not apply the change.")
            }
          }
        }
      }
      if (!accepted) store.reject(token, id, "The player is refreshing. Please try again.")
      return accepted
    }
    val track = snapshot.track ?: return false
    if (!snapshot.sourceId.startsWith("local:")) return false
    val command: MusicCommand
    val confirmation: MusicConfirmation
    var position = 0L
    when (action) {
      MusicPlayerUiAction.TogglePlayback -> {
        val pause = snapshot.playback == PlaybackState.PLAYING
        command = if (pause) MusicCommand.PAUSE else MusicCommand.PLAY
        confirmation = MusicConfirmation.Playback(if (pause) PlaybackState.PAUSED else PlaybackState.PLAYING)
      }
      MusicPlayerUiAction.Next -> {
        command = MusicCommand.NEXT
        confirmation = MusicConfirmation.TrackChanged(track.id)
      }
      MusicPlayerUiAction.Previous -> {
        command = MusicCommand.PREVIOUS
        confirmation = MusicConfirmation.TrackOrRestart(track.id, snapshot.positionMs)
      }
      is MusicPlayerUiAction.Seek -> {
        val duration = track.durationMs ?: return false
        if (action.positionMs !in 0..duration) return false
        command = MusicCommand.SEEK
        position = action.positionMs
        confirmation = MusicConfirmation.Seek(track.id, position)
      }
      else -> return false
    }
    val id = ++requestId
    val token = generation
    if (!store.beginCommand(id, command, SystemClock.elapsedRealtime(), confirmation)) return false
    armPendingTimeout()
    MediaSessionReader.musicCommand(snapshot.sourceId.removePrefix("local:"), snapshot.localSessionToken,
        track.id, command, position) { ok ->
      main.post {
        if (attached) {
          if (ok) store.acknowledge(token, id)
          else store.reject(token, id, "This player could not apply the change.")
        }
      }
    }
    return true
  }
}

/** Collect here so playback updates do not invalidate Home's widget/layout composition. */
@Composable
internal fun MusicPlayerHost(
    layout: MusicPlayerLayout,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onLayoutChange: (MusicPlayerLayout) -> Unit,
) {
  val context = LocalContext.current
  val controller = remember(context) { MusicPlayerSessions.controller(context) }
  val photos = rememberMusicPhotos(layout == MusicPlayerLayout.PHOTOS)
  DisposableEffect(controller) {
    MusicPlayerSessions.retainUi(controller)
    onDispose { MusicPlayerSessions.releaseUi(controller) }
  }
  val snapshotState = controller.store.state.collectAsState()
  // Capture an ordinary immutable value; a delegated State getter in the callback reads live state.
  val snapshot = snapshotState.value
  MusicPlayerContent(snapshot, layout, modifier, onDismiss, onLayoutChange,
      onAction = { action ->
        when (action) {
          MusicPlayerUiAction.OpenSettings -> context.openMusicSettings()
          // Library browsing is only offered by an authenticated MA source adapter.
          MusicPlayerUiAction.BrowseLibrary -> synchronized(
              com.immortal.launcher.settings.SettingsDomains.immortal) {
            if (controller.acceptsRendered(snapshot, action)) context.openMusicLibrary(snapshot)
          }
          else -> controller.send(action, snapshot)
        }
      },
      photoContent = { photoModifier -> MusicPhotoHost(photos, photoModifier) })
}

private fun Context.openMusicSettings() {
  FramePerformanceCapture.launchSettings(this,
      Intent(this, ImmortalSettingsActivity::class.java)
          .putExtra(APPROVED_SETTINGS_CATEGORY, "connections")
          .putExtra(APPROVED_SETTINGS_PAGE, "multiroom"))
}

/** Library/provider setup stays in MA's owned UI. No token/cookie is put in a URL or WebView. */
private fun Context.openMusicLibrary(snapshot: MusicPlayerSnapshot) {
  if (snapshot.connection != MusicConnection.READY || !snapshot.sourceId.startsWith("ma:") ||
      snapshot.audioOwnedByOther) return
  val saved = ImmortalSettings.load(this)
  val host = saved.snapcastHost
  if (host.isBlank() || host.any { it.isWhitespace() || it in "/?#@" } || "://" in host) return
  val url = runCatching { java.net.URI("http", null, host, saved.maPort, "/", null, null).toASCIIString() }
      .getOrNull() ?: return
  startActivity(Intent(this, WebAppActivity::class.java).putExtra(WebAppActivity.EXTRA_URL, url))
}
