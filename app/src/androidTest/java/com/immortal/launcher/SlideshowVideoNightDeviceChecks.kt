/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.immortal.launcher.settings.SettingsRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Observer sidecar for the real preview/controller and worker-owned Android player.
 * No build/install glue, household media, Dream/security writes, or persisted pause changes.
 * Every handler rendezvous is bounded; native queries execute ONLY on Owner.worker.
 * The 60-second check budget reserves 15 seconds for independent finally cleanup attempts.
 */
internal object SlideshowVideoNightDeviceChecks {
  fun run(instrumentation: Instrumentation): JSONObject = Probe(instrumentation).run()

  private class Failure(val label: String) : RuntimeException()
  private data class PreferenceSnapshot(val prefs: SharedPreferences, val values: Map<String, *>)
  private data class MainState(
      val controller: PhotoFrameController, val video: SlideshowVideoView,
      val source: Any?, val revision: Long, val surfaceRevision: Long, val seekRevision: Long, val cached: Int,
      val retained: Int, val paused: Boolean, val active: Boolean, val night: Boolean,
      val reveal: Float, val rendered: Boolean, val generation: Int, val index: Int,
  )
  private data class PlayerState(
      val player: MediaPlayer?, val prepared: Boolean, val playing: Boolean,
      val nativePlaying: Boolean, val position: Int, val duration: Int, val appliedSeek: Long,
  )

  private class Probe(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = SystemClock.elapsedRealtime()
    private val checkDeadline = started + 60_000L
    private var deadline = checkDeadline
    private val marker = UUID.randomUUID().toString()
    private val folder = File(context.cacheDir, "video-night-$marker")
    private val photo = File(folder, "01-photo.png")
    private val clip = File(folder, "02-video.mp4")
    private val assertions = JSONArray()
    private val measurements = JSONObject()
    private val result = JSONObject()
    private val snapshots = linkedMapOf<String, PreferenceSnapshot>()
    private val patches = linkedMapOf<String, JSONObject>()
    private val ownedActivity = AtomicReference<PhotoFramePreviewActivity?>()
    private var monitor: Instrumentation.ActivityMonitor? = null
    private var controller: PhotoFrameController? = null
    private var owner: Any? = null
    private var worker: Handler? = null
    private var before: ScreensaverConfig.Settings? = null
    private var fixtureOwned = false
    private var callbacksRegistered = false
    private var mainSamples = 0
    private var workerSamples = 0
    private var maxMainWaitMs = 0L
    private var maxWorkerWaitMs = 0L
    private var failed = false
    private val application = context.applicationContext as Application
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity is PhotoFramePreviewActivity &&
            activity.intent.getStringExtra("video_night_probe") == marker)
          ownedActivity.compareAndSet(null, activity)
      }
      override fun onActivityStarted(activity: Activity) = Unit
      override fun onActivityResumed(activity: Activity) = Unit
      override fun onActivityPaused(activity: Activity) = Unit
      override fun onActivityStopped(activity: Activity) = Unit
      override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
      override fun onActivityDestroyed(activity: Activity) = Unit
    }
    private val recordingContext = object : ContextWrapper(context) {
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val prefs = super.getSharedPreferences(name, mode)
        snapshots.getOrPut(name) { PreferenceSnapshot(prefs, prefs.all.mapValues { (_, value) ->
          if (value is Set<*>) value.toSet() else value
        }) }
        return prefs
      }
    }

    fun run(): JSONObject {
      try {
        demand("observer_thread_is_not_main", Looper.myLooper() !== Looper.getMainLooper())
        before = ScreensaverConfig.load(recordingContext)
        demand("fresh_fixture_directory", !folder.exists() && folder.mkdir())
        fixtureOwned = true
        val pixels = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        try {
          pixels.eraseColor(Color.rgb(20, 90, 160))
          photo.outputStream().use { demand("tiny_photo_written", pixels.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { pixels.recycle() }
        instrumentation.context.assets.open("slideshow/night-retention-clip.mp4").use { input ->
          clip.outputStream().use { output -> input.copyTo(output) }
        }
        demand("owned_video_asset_written", clip.length() > 0)
        configure()
        launch()
        exercise()
      } catch (error: Throwable) {
        failure(error, "probe")
      } finally {
        deadline = started + 75_000L
        cleanup()
        measurements.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("mainMeasurementCount", mainSamples).put("workerMeasurementCount", workerSamples)
            .put("maxMainRendezvousMs", maxMainWaitMs).put("maxWorkerRendezvousMs", maxWorkerWaitMs)
            .put("assertionCount", assertions.length()).put("checkBudgetMs", 60_000)
            .put("totalBudgetMs", 75_000).put("cachedPositionSamplingMs", 250)
        result.put("pass", !failed).put("assertions", assertions).put("measurements", measurements)
            .put("limitations", JSONArray(listOf(
                "Reflection contracts require the current controller and SlideshowVideoView",
                "Native resume sample is the first bounded worker observation, not a seek-complete callback",
                "No producer CPU, delivered FPS, moving-video pixel equality or hardware light-sensor claim",
                "Face extras hide weather; the current controller still schedules its legacy weather fetch")))
      }
      return result
    }

    private fun configure() {
      patches["screensaver"] = JSONObject().put("mode", "smartFrame").put("intervalSec", 120)
          .put("shuffle", false).put("includeVideo", true).put("photo_motion", false)
          .put("keep_people_in_frame", false).put("cacheEnabled", true)
          .put("overnightEnabled", false).put("darkRoomClock", false).put("idleSleepMin", 0)
          .put("sleepTimerEnabled", false).put("welcomeEnabled", false).put("ambientDashboard", false)
          .put("gestureWave", false).put("soundscape", "off").put("showNowPlaying", false)
          .put("antiBurnIn", false)
      patches["calendar"] = JSONObject().put("widgetOn", false)
      main("configure") {
        for ((domain, patch) in patches) {
          // applyValues intentionally avoids the domain's Dream/security/scheduler hook.
          val applied = checkNotNull(SettingsRegistry.domain(domain)).applyValues(recordingContext, patch)
          demand("${domain}_registry_applied", applied == patch.keys().asSequence().toSet())
        }
        ScreensaverConfig.setFolder(recordingContext, folder.path)
        demand("atomic_owned_folder_source", ScreensaverConfig.load(context).let {
          it.source == ScreensaverConfig.SOURCE_FOLDER && it.folderPath == folder.path && it.cacheEnabled
        })
      }
    }

    private fun launch() {
      main("launch") {
        application.registerActivityLifecycleCallbacks(callbacks)
        callbacksRegistered = true
        monitor = instrumentation.addMonitor(PhotoFramePreviewActivity::class.java.name, null, false)
        context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          putExtra("video_night_probe", marker)
          putExtra(PhotoFramePreviewActivity.EXTRA_SHOW_WELCOME, false)
          putExtra(PhotoFramePreviewActivity.EXTRA_LAUNCH_DISMISS_APP, false)
          putExtra("face_json", """{"clock":{"mode":"none"},"weather":{"enabled":false},
            "nowPlaying":{"enabled":false},"battery":{"enabled":false},"caption":{"enabled":false}}""")
        })
      }
      poll("fresh_real_preview_created", 8_000) { ownedActivity.get() != null }
      main("controller") {
        val host = checkNotNull(ownedActivity.get())
        demand("owned_activity_is_real_preview", !host.isFinishing && !host.isDestroyed)
        controller = field(host, "frame") as PhotoFrameController
      }
      poll("owned_photo_presented", 10_000) { main("photo_ready") {
        val stage = stage()
        stage.currentPhoto?.sourceKey?.contains(photo.path) == true &&
            stage.visibility == View.VISIBLE && !stage.currentPhoto!!.bitmap.isRecycled
      } }
      main("prepare_video") {
        val c = checkNotNull(controller)
        invokeBoolean(c, "setPaused", true)
        val held = checkNotNull(stage().currentPhoto)
        c.next()
        demand("preparation_preserves_retained_photo", stage().visibility == View.VISIBLE &&
            stage().currentPhoto?.bitmap === held.bitmap && !held.bitmap.isRecycled)
        val video = field(c, "videoView") as SlideshowVideoView
        owner = field(video, "owner")
        worker = field(checkNotNull(owner), "worker") as Handler
      }
      poll("native_player_prepared_while_user_paused", 10_000) { playerState().prepared }
      demand("prepared_video_keeps_photo_until_rendered", main("prepared_photo") {
        stage().visibility == View.VISIBLE && field(checkNotNull(controller), "videoReady") == false
      } && !playerState().nativePlaying)
      main("initial_resume") { invokeBoolean(checkNotNull(controller), "setPaused", false) }
    }

    private fun exercise() {
      poll("actual_native_playback_and_rendering_begin", 8_000) {
        val state = mainState()
        val native = playerState()
        state.rendered && state.cached > 250 && native.nativePlaying &&
            native.playing && native.position in 1 until native.duration
      }
      val session = mainState()
      val initial = playerState()
      demand("only_owned_video_source", field(checkNotNull(session.source), "uri") == Uri.fromFile(clip).toString())
      demand("long_fixture_duration", initial.duration in 29_000..31_000)
      measurements.put("clipDurationMs", initial.duration).put("initialNativePositionMs", initial.position)
          .put("beforeNightSeekRevision", session.seekRevision).put("beforeNightAppliedSeek", initial.appliedSeek)
      main("night_on") { invokeBoolean(checkNotNull(controller), "setAmbientNight", true) }
      settle(true)
      val paused = playerState()
      val retained = mainState()
      demand("night_does_not_set_user_pause", !retained.paused)
      sameSession("night", session, initial)
      measurements.put("nightPausedNativePositionMs", paused.position)
          .put("nightRetainedCachedPositionMs", retained.retained)
          .put("nightCacheLagMs", paused.position - retained.retained)
      hold("night", session, initial, 3_000)
      main("night_off") { invokeBoolean(checkNotNull(controller), "setAmbientNight", false) }
      var earliest: PlayerState? = null
      poll("night_uncover_resumes_native_player", 6_000) {
        val observed = playerState()
        if (observed.nativePlaying) { earliest = observed; true } else false
      }
      val resumed = checkNotNull(earliest)
      val returned = mainState()
      measurements.put("resumeNativePositionMs", resumed.position)
          .put("resumeNativeMinusPausedMs", resumed.position - paused.position)
          .put("resumeNativeMinusRetainedCacheMs", resumed.position - retained.retained)
          .put("resumeCachedMinusPausedMs", returned.cached - paused.position)
          .put("afterNightSeekRevision", returned.seekRevision)
          .put("afterNightAppliedSeek", resumed.appliedSeek)
          .put("nightResumeSeekRevisionDelta", returned.seekRevision - session.seekRevision)
      // Deliberately non-fatal: expose the production rewind/work regression while continuing
      // the user-pause checks and all cleanup. No seeking or listener replacement by the probe.
      val samePlayerAndSurface = resumed.player === initial.player &&
          returned.surfaceRevision == session.surfaceRevision
      record("retainedNightResumeDoesNotSeek", samePlayerAndSurface &&
          returned.seekRevision == session.seekRevision && resumed.appliedSeek == initial.appliedSeek)
      demand("resume_retention_drift_within_one_cache_tick",
          resumed.position - paused.position in -350..500)
      demand("resume_starts_at_retained_cached_anchor",
          resumed.position - retained.retained in -100..500)
      sameSession("resumed", session, initial)
      val resumePosition = resumed.position
      poll("resumed_native_and_cached_positions_advance", 2_000) {
        playerState().position > resumePosition + 200 && mainState().cached > retained.retained + 200
      }

      main("explicit_user_pause") { invokeBoolean(checkNotNull(controller), "setPaused", true) }
      poll("explicit_user_pause_acknowledged", 2_000) {
        val native = playerState()
        mainState().paused && !native.playing && !native.nativePlaying
      }
      main("user_paused_night_on") { invokeBoolean(checkNotNull(controller), "setAmbientNight", true) }
      settle(true)
      // External host visibility can change while the clock cover is already present.
      main("external_host_covered") { checkNotNull(controller).setHostActive(false) }
      hold("user_paused_external_cover", session, initial, 600)
      main("external_host_returned") { checkNotNull(controller).setHostActive(true) }
      demand("host_return_does_not_uncover_night", !mainState().active && mainState().night)
      main("user_paused_night_off") { invokeBoolean(checkNotNull(controller), "setAmbientNight", false) }
      settle(false)
      // A seek to the earlier cache may complete here without starting playback. Let it settle.
      sleep(350)
      demand("user_pause_survives_night_and_external_cover", mainState().paused)
      hold("user_paused_day", session, initial, 1_000)
      val userPausedPosition = playerState().position
      main("explicit_user_resume") { invokeBoolean(checkNotNull(controller), "setPaused", false) }
      poll("only_explicit_resume_restarts_user_paused_video", 2_000) {
        val native = playerState()
        !mainState().paused && native.nativePlaying && native.position > userPausedPosition + 200
      }
      sameSession("explicit_resume", session, initial)
      recreateSurface(session)
    }

    /** Deliberately destroy only the owned video surface to verify the recovery branch. */
    private fun recreateSurface(session: MainState) {
      main("recreation_pause") { checkNotNull(controller).setHostActive(false) }
      poll("surface_recreation_pause_acknowledged", 2_000) { !playerState().nativePlaying }
      val anchor = mainState()
      val priorPlayer = playerState().player
      demand("surface_recreation_has_a_nonzero_anchor", anchor.cached > 500)
      main("recreation_hide") { anchor.video.visibility = View.GONE }
      poll("actual_surface_destroyed_and_player_released", 3_000) {
        playerState().player == null && mainState().surfaceRevision != anchor.surfaceRevision
      }
      main("recreation_show") { anchor.video.visibility = View.VISIBLE }
      poll("actual_replacement_surface_created", 3_000) { main("surface_created") {
        val request = checkNotNull(field(anchor.video, "request"))
        field(request, "surface") != null &&
            (field(request, "surfaceRevision") as Number).toLong() > anchor.surfaceRevision + 1
      } }
      main("recreation_resume") { checkNotNull(controller).setHostActive(true) }
      poll("replacement_player_restores_retained_position", 6_000) {
        val native = playerState()
        native.player != null && native.player !== priorPlayer && native.nativePlaying &&
            native.position > 350 && native.position - anchor.cached in -350..500
      }
      val restored = mainState()
      val native = playerState()
      demand("surface_recovery_keeps_the_controller_and_clip", restored.controller === session.controller &&
          restored.source === session.source && restored.revision == session.revision &&
          restored.generation == session.generation && restored.index == session.index)
      demand("surface_recovery_requests_one_required_seek",
          restored.seekRevision - anchor.seekRevision == 1L)
      measurements.put("surfaceRecoveryAnchorMs", anchor.cached)
          .put("surfaceRecoveryNativePositionMs", native.position)
          .put("surfaceRecoveryNativeMinusAnchorMs", native.position - anchor.cached)
          .put("surfaceRecreationSeekRevisionDelta", restored.seekRevision - anchor.seekRevision)
    }

    private fun settle(night: Boolean) = poll(if (night) "clock_cover_settled" else "clock_uncover_settled", 6_000) {
      val s = mainState()
      s.night == night && s.reveal == (if (night) 1f else 0f) && s.active == !night &&
          (!night || !playerState().nativePlaying)
    }

    private fun hold(label: String, session: MainState, initial: PlayerState, duration: Long) {
      val baseline = playerState()
      val cached = mainState().cached
      val until = SystemClock.elapsedRealtime() + duration
      var count = 0
      var largestNativeDrift = 0
      var largestCachedDrift = 0
      do {
        sleep(minOf(200L, (until - SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
        val native = playerState()
        val state = mainState()
        largestNativeDrift = maxOf(largestNativeDrift, kotlin.math.abs(native.position - baseline.position))
        largestCachedDrift = maxOf(largestCachedDrift, kotlin.math.abs(state.cached - cached))
        demand("${label}_sample_${count++}", !native.playing && !native.nativePlaying &&
            native.position in 1 until native.duration && largestNativeDrift <= 100 &&
            largestCachedDrift <= 100)
        sameSession("${label}_identity_$count", session, initial)
      } while (SystemClock.elapsedRealtime() < until)
      measurements.put("${label}SampleCount", count).put("${label}SampleDurationMs", duration)
          .put("${label}MaxNativeDriftMs", largestNativeDrift).put("${label}MaxCachedDriftMs", largestCachedDrift)
    }

    private fun sameSession(label: String, expected: MainState, initial: PlayerState) {
      val actual = mainState()
      demand("${label}_same_controller_source_player", actual.controller === expected.controller &&
          actual.video === expected.video && actual.source === expected.source &&
          actual.revision == expected.revision && actual.surfaceRevision == expected.surfaceRevision &&
          actual.generation == expected.generation && actual.index == expected.index &&
          playerState().player === initial.player)
    }

    private fun mainState(): MainState = main("main_state") {
      mainSamples++
      val c = checkNotNull(controller)
      val video = field(c, "videoView") as SlideshowVideoView
      val request = checkNotNull(field(video, "request"))
      val transition = field(c, "nightTransition") as NightClockTransition
      MainState(c, video, field(request, "source"), (field(request, "sourceRevision") as Number).toLong(),
          (field(request, "surfaceRevision") as Number).toLong(),
          (field(request, "seekRevision") as Number).toLong(), video.currentPosition,
          field(c, "retainedVideoPosition") as Int, field(c, "paused") as Boolean,
          field(c, "hostActive") as Boolean, field(c, "ambientNight") as Boolean,
          transition.reveal, field(c, "videoReady") as Boolean, field(c, "gen") as Int,
          field(c, "localIndex") as Int)
    }

    private fun playerState(): PlayerState = task(checkNotNull(worker), "worker_state") {
      workerSamples++
      val o = checkNotNull(owner)
      val player = field(o, "player") as? MediaPlayer
      val prepared = field(o, "prepared") as Boolean
      PlayerState(player, prepared, field(o, "playing") as Boolean,
          if (prepared && player != null) player.isPlaying else false,
          if (prepared && player != null) player.currentPosition else 0,
          if (prepared && player != null) player.duration else 0,
          (field(o, "appliedSeek") as Number).toLong())
    }

    private fun cleanup() {
      attempt("ownedActivityClosed") {
        if (ownedActivity.get() != null) {
          main("finish_owned_activity") {
            ownedActivity.get()?.finish()
          }
          poll("owned_activity_destroyed", 3_000) {
            ownedActivity.get()?.let { main("destroyed") { it.isDestroyed } } ?: true
          }
        }
      }
      if (worker != null) attempt("ownedPlayerReleased") {
        poll("owned_player_released", 2_000) { playerState().player == null }
      }
      attempt("monitorRemoved") {
        main("remove_monitor") {
          monitor?.let(instrumentation::removeMonitor)
          if (callbacksRegistered) application.unregisterActivityLifecycleCallbacks(callbacks)
        }
      }
      attempt("preferencesRestored") {
        val restoring = object : ContextWrapper(context) {
          override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val saved = checkNotNull(snapshots[name])
            return ExactRestorePreferences(saved.prefs, saved.values)
          }
        }
        main("restore_registry_and_source") {
          for ((domain, patch) in patches)
            checkNotNull(SettingsRegistry.domain(domain)).applyValues(restoring, patch)
          // Only this atomic API's two keys were changed; the wrapper restores both exactly,
          // including a dormant folder field and an originally absent source key.
          if (before != null) ScreensaverConfig.setFolder(restoring, folder.path)
        }
        demand("exact_preference_values_and_key_presence_restored",
            snapshots.values.all { it.prefs.all == it.values })
      }
      attempt("sourceRestored") {
        demand("full_source_and_configuration_restored", before == null || ScreensaverConfig.load(context) == before)
      }
      attempt("ownedFixtureCleanup") {
        if (fixtureOwned) {
          // No recursive deletion or directory traversal; only the two known owned files.
          demand("owned_photo_deleted", !photo.exists() || photo.delete())
          demand("owned_video_deleted", !clip.exists() || clip.delete())
          demand("owned_fixture_directory_deleted", !folder.exists() || folder.delete())
        }
      }
    }

    private fun attempt(name: String, block: () -> Unit) {
      try { block(); result.put(name, true) }
      catch (error: Throwable) { result.put(name, false); failure(error, name) }
    }

    private fun failure(error: Throwable, fallback: String) {
      failed = true
      val actual = if (error is ExecutionException) error.cause ?: error else error
      if (!result.has("failure")) result.put("failure", JSONObject()
          .put("label", if (actual is Failure) actual.label else fallback)
          .put("class", actual.javaClass.simpleName))
    }

    private fun demand(name: String, pass: Boolean) {
      record(name, pass)
      if (!pass) throw Failure(name)
    }

    private fun record(name: String, pass: Boolean) {
      assertions.put(JSONObject().put("name", name).put("pass", pass))
      if (!pass) failure(Failure(name), name)
    }

    private fun poll(label: String, timeout: Long, condition: () -> Boolean) {
      val until = minOf(deadline, SystemClock.elapsedRealtime() + timeout)
      while (SystemClock.elapsedRealtime() < until) {
        if (condition()) { demand(label, true); return }
        sleep(60)
      }
      demand(label, false)
    }

    private fun sleep(ms: Long) {
      val remaining = deadline - SystemClock.elapsedRealtime()
      if (remaining <= 0) throw Failure("probe_budget")
      SystemClock.sleep(minOf(ms, remaining))
    }

    private fun <T> main(label: String, block: () -> T): T = task(mainHandler, label, block)

    private fun <T> task(handler: Handler, label: String, block: () -> T): T {
      if (Looper.myLooper() === handler.looper) throw Failure("${label}_wrong_caller")
      val remaining = deadline - SystemClock.elapsedRealtime()
      if (remaining <= 0) throw Failure("probe_budget")
      val begin = SystemClock.elapsedRealtime()
      val beganExecution = AtomicBoolean(false)
      val future = FutureTask(Callable { beganExecution.set(true); block() })
      if (!handler.post(future)) throw Failure("${label}_post")
      val limit = if (handler === mainHandler &&
          label in setOf("configure", "launch", "restore_registry_and_source")) 5_000L else 1_200L
      try { return future.get(minOf(limit, remaining), TimeUnit.MILLISECONDS) }
      catch (error: ExecutionException) { throw error.cause ?: Failure(label) }
      catch (_: java.util.concurrent.TimeoutException) {
        measurements.put("timedOutRendezvous", JSONObject().put("label", label)
            .put("mainThread", handler === mainHandler).put("beganExecution", beganExecution.get()))
        throw Failure("${label}_timeout")
      }
      finally {
        handler.removeCallbacks(future)
        future.cancel(false)
        val elapsed = SystemClock.elapsedRealtime() - begin
        if (handler === mainHandler) maxMainWaitMs = maxOf(maxMainWaitMs, elapsed)
        else maxWorkerWaitMs = maxOf(maxWorkerWaitMs, elapsed)
      }
    }

    private fun stage(): SlideshowStage = field(checkNotNull(controller), "nativeStage") as SlideshowStage
  }

  private fun field(target: Any, name: String): Any? =
      target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

  private fun invokeBoolean(target: Any, name: String, value: Boolean) {
    try {
      target.javaClass.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
          .apply { isAccessible = true }.invoke(target, value)
    } catch (error: java.lang.reflect.InvocationTargetException) {
      throw error.cause ?: Failure(name)
    }
  }

  /**
   * Restore-only context adapter: registry/source setters remain the only write entry points.
   * Their Editor writes replay original raw values, avoiding normalization and preserving absent
   * keys. It cannot clear a preference file or touch keys outside those reached by the APIs.
   */
  private class ExactRestorePreferences(
      private val delegate: SharedPreferences, private val original: Map<String, *>,
  ) : SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor {
      val editor = delegate.edit()
      return object : SharedPreferences.Editor by editor {
        private fun restore(key: String): SharedPreferences.Editor {
          when (val value = original[key]) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> {
              @Suppress("UNCHECKED_CAST")
              editor.putStringSet(key, value as Set<String>)
            }
            else -> throw Failure("restore_preference_type")
          }
          return this
        }
        override fun putBoolean(key: String, value: Boolean) = restore(key)
        override fun putInt(key: String, value: Int) = restore(key)
        override fun putLong(key: String, value: Long) = restore(key)
        override fun putFloat(key: String, value: Float) = restore(key)
        override fun putString(key: String, value: String?) = restore(key)
        override fun putStringSet(key: String, values: MutableSet<String>?) = restore(key)
        override fun remove(key: String) = restore(key)
        override fun clear(): SharedPreferences.Editor = throw Failure("restore_clear_forbidden")
      }
    }
  }
}
