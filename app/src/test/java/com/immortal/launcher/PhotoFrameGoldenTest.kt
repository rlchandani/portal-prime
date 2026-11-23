/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.view.MotionEvent
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Replays scripted screensaver sessions through the real [PhotoFrameController] on
 * [PhotoFrameGoldenRig] and compares everything it did with `photoframe/photo_frame_golden.txt`,
 * recorded before the controller was split. Any difference is a behaviour change. Re-record only on
 * purpose: `GOLDEN_RECORD=1 ./gradlew :app:testDebugUnitTest --tests '*PhotoFrameGoldenTest*'`.
 */
class PhotoFrameGoldenTest {
  @Test fun everyRecordedSessionReplaysTheSame() {
    val got = LinkedHashMap<String, List<String>>()
    for ((name, scenario) in PhotoFrameGoldenScenarios.all) got[name] = run(scenario)
    if (System.getenv("GOLDEN_RECORD") == "1") { GoldenText.write(File(RECORD_PATH), got); return }
    val want = GoldenText.parse(ControlCenterEngineGoldenTest.resource("photoframe/photo_frame_golden.txt"))
    assertTrue("fixture has too few scenarios", want.size >= MIN_SCENARIOS)
    val diffs = GoldenText.diff(want, got)
    if (diffs.isNotEmpty()) fail(diffs.joinToString("\n", "${diffs.size} scenario(s) differ:\n"))
  }

  private fun run(s: PhotoFrameGoldenScenarios.Scenario): List<String> {
    val rig = PhotoFrameGoldenRig(s.prefs(), s.testSource, s.options)
    return try {
      s.steps(rig)
      rig.out.toList()
    } catch (t: Throwable) {
      rig.out + "rig error ${t.javaClass.simpleName}: ${t.message?.let(rig::clean)} at ${t.stackTrace.take(8).joinToString(" < ")}"
    } finally {
      rig.close()
    }
  }

  companion object {
    private const val RECORD_PATH = "src/test/resources/photoframe/photo_frame_golden.txt"
    private const val MIN_SCENARIOS = 20
  }
}

internal object PhotoFrameGoldenScenarios {
  class Scenario(
      val prefs: () -> Map<String, Any?> = { emptyMap() },
      val testSource: PhotoFrameGoldenRig.TestSource? = null,
      val options: PhotoFrameGoldenRig.Options = PhotoFrameGoldenRig.Options(),
      val steps: (PhotoFrameGoldenRig) -> Unit,
  )

  private const val UP = MotionEvent.ACTION_UP
  private const val DOWN = MotionEvent.ACTION_DOWN
  private val O = PhotoFrameGoldenRig.Options()
  private fun mode(m: String, interval: Int = 17, extra: Map<String, Any?> = emptyMap()): () -> Map<String, Any?> =
      { mapOf<String, Any?>("mode" to m, "interval_sec" to interval) + extra }

  /** create, build the views quietly, start. */
  private fun PhotoFrameGoldenRig.up() { create(); buildQuietly(); start() }

  private fun folderPrefs(rig: PhotoFrameGoldenRig, m: String, vararg files: String, extra: Map<String, Any?> = emptyMap()) =
      mapOf<String, Any?>("mode" to m, "interval_sec" to 17, "source" to ScreensaverConfig.SOURCE_FOLDER,
          "folder_path" to rig.folder(*files)) + extra

  val all: Map<String, Scenario> = linkedMapOf(
      // --- mode selection on the built-in feed -----------------------------------------------
      "smart frame on the built-in feed: native stage, first photo, dwell and advance" to Scenario(mode(ScreensaverConfig.MODE_SMART)) { r ->
        r.up(); r.deliver(); r.pending(); r.advance(0); r.deliver(); r.pending(); r.advance(17_000); r.deliver(); r.pending()
      },
      "classic on the built-in feed: web feed worker and a rotate every interval" to Scenario(mode(ScreensaverConfig.MODE_CLASSIC, 23)) { r ->
        r.up(); r.pending(); r.advance(23_000); r.pending()
      },
      "engine style on the built-in feed: keyless Picsum batch sized to the panel" to Scenario(mode("kenBurns", 40)) { r ->
        r.up(); r.prepareEngine(); r.pending()
      },
      "engine style on a portrait Mini panel caps the batch at its long edge" to Scenario(mode("diptych"),
          options = O.copy(width = 800, height = 1280)) { r -> r.up(); r.prepareEngine() },
      "filmstrip uses the native filmstrip stage" to Scenario(mode("filmstrip", 12)) { r -> r.up(); r.pending() },
      "shuffle style (engine) with photo motion off" to Scenario(mode(ScreensaverConfig.MODE_SHUFFLE, 9, mapOf("photo_motion" to false))) { r ->
        r.up(); r.prepareEngine()
      },
      "whole photo: no fill or motion, no people framing" to Scenario(mode(ScreensaverConfig.MODE_WHOLE, 20, mapOf("fit" to "fill"))) { r ->
        r.up(); r.deliver()
      },
      "gallery print and spread flags reach the stage" to Scenario(mode(ScreensaverConfig.MODE_GALLERY_SPREAD)) { r ->
        r.up(); r.deliver(); r.advance(0); r.deliver()
      },
      "split glance: clock panel instead of the face, split feature on the stage" to Scenario(mode(ScreensaverConfig.MODE_SPLIT_GLANCE)) { r ->
        r.up(); r.deliver()
      },
      // --- host shapes ----------------------------------------------------------------------
      "scheduled night clock only: quiet clock, no photo pipeline" to Scenario(mode(ScreensaverConfig.MODE_SMART),
          options = O.copy(nightClockOnly = true)) { r ->
        r.create(); r.buildQuietly(); r.start(); r.pending(); r.touch(DOWN, 10f, 10f); r.touch(UP, 12f, 14f)
      },
      "overnight night photo: warm dim photos, longer dwell, no soundscape" to Scenario(
          mode(ScreensaverConfig.MODE_SMART, 20, mapOf("soundscape" to "rain")), options = O.copy(overnightNightPhoto = true)) { r ->
        r.up(); r.deliver(); r.advance(0); r.deliver(); r.pending()
      },
      "night photo under fit=fit: the stage is told to hold it whole" to Scenario(
          mode(ScreensaverConfig.MODE_SMART, 20, mapOf("fit" to ScreensaverConfig.FIT_FIT)),
          options = O.copy(overnightNightPhoto = true)) { r ->
        r.up(); r.deliver(); r.advance(0); r.deliver(); r.pending()
      },
      "music photos (photos only): no overlays, the operator pin is ignored" to Scenario(mode("kenBurns", 45),
          testSource = PhotoFrameGoldenRig.TestSource(pinMode = ScreensaverConfig.MODE_WHOLE, pinIntervalSec = 9),
          options = O.copy(photosOnly = true)) { r -> r.up(); r.deliver(); r.advance(0); r.deliver(); r.pending() },
      "welcome overlay with a spoken greeting on a presence start" to Scenario(
          mode(ScreensaverConfig.MODE_CLASSIC, 30, mapOf("welcome_enabled" to true)), options = O.copy(showWelcome = true)) { r ->
        r.up(); r.pending(); r.touch(DOWN, 100f, 100f); r.touch(UP, 101f, 101f)
      },
      "ambient dashboard cycles on classic, never on an engine style" to Scenario(
          mode(ScreensaverConfig.MODE_CLASSIC, 30, mapOf("ambient_dashboard" to true))) { r -> r.up(); r.pending() },
      "ambient dashboard is off under an engine style" to Scenario(mode("framed", 30, mapOf("ambient_dashboard" to true))) { r ->
        r.up(); r.pending()
      },
      "soundscape and wave-to-advance follow the host" to Scenario(
          mode(ScreensaverConfig.MODE_SMART, 17, mapOf("soundscape" to "rain", "gesture_wave" to true))) { r ->
        r.up(); r.hostActive(false); r.hostActive(true)
      },
      // --- deck building: local folder ---------------------------------------------------------
      "folder on smart frame: deck keys, partners, dwell, next and previous" to Scenario(steps = { r ->
        r.reconfigure(folderPrefs(r, ScreensaverConfig.MODE_SMART, "a.jpg", "b.jpg", "clip.mp4", "c.png", "notes.txt"))
        r.up(); r.deliver(); r.drain("io"); r.deliver(); r.pending()
        r.advance(17_000); r.deliver(); r.next(); r.deliver(); r.prev(); r.deliver(); r.pending()
      }),
      "folder on portrait pair asks for partner pairs" to Scenario(steps = { r ->
        r.reconfigure(folderPrefs(r, ScreensaverConfig.MODE_PORTRAITS, "a.jpg", "b.jpg", "c.jpg"))
        r.up(); r.deliver(); r.drain("io"); r.deliver(count = 2, partner = "x")
      }),
      "folder video plays through the video view with a five-minute safety net" to Scenario(steps = { r ->
        r.reconfigure(folderPrefs(r, ScreensaverConfig.MODE_SMART, "clip.mp4", "z.jpg"))
        r.up(); r.deliver(); r.drain("io"); r.pending()
      }),
      "folder on an engine style: oriented sizes for a seed, junk dropped, videos skipped" to Scenario(steps = { r ->
        r.reconfigure(folderPrefs(r, "kenBurns", "a_3000x2000.jpg", "rot90_4000x3000.jpg", "tiny_300x200.jpg", "clip.mp4",
            "unknown.jpg"))
        r.up(); r.drain("io"); r.advance(0); r.prepareEngine(); r.pending()
      }),
      "folder on classic: decode on the worker, fallback after a full failed pass" to Scenario(steps = { r ->
        r.reconfigure(folderPrefs(r, ScreensaverConfig.MODE_CLASSIC, "a.jpg", "b.jpg"))
        r.up(); r.drain("io"); r.drain("io"); r.advance(0); r.drain("io"); r.advance(0); r.pending()
      }),
      "empty folder falls back to the built-in feed" to Scenario(steps = { r ->
        r.reconfigure(folderPrefs(r, ScreensaverConfig.MODE_SMART, "notes.txt"))
        r.up(); r.deliver(); r.drain("io"); r.advance(0); r.deliver(); r.pending()
      }),
      "local decode sizes: native bounded bytes with identity, classic file decode" to Scenario(steps = { r ->
        r.reconfigure(folderPrefs(r, ScreensaverConfig.MODE_SMART, "p_3000x4000.jpg", "q_4000x3000.jpg"))
        r.up(); r.decodeAsset(2400); r.deliver(); r.drain("io"); r.advance(0); r.decodeAsset(2400); r.decodeAsset(1400)
      }),
      // --- remote sources -----------------------------------------------------------------------
      "Immich on smart frame: remote deck, videos marked, cache on" to Scenario(mode(ScreensaverConfig.MODE_SMART, 17,
          mapOf("source" to ScreensaverConfig.SOURCE_IMMICH, "immich_url" to "https://immich.test", "immich_key" to "k1"))) { r ->
        r.up(); r.deliver()
        r.discover(listOf(ImmichSource.Media("https://immich.test/a", false), ImmichSource.Media("https://immich.test/v", true),
            ImmichSource.Media("https://immich.test/b", false)))
        r.advance(0); r.deliver(); r.pending(); r.advance(17_000); r.pending(); r.advance(17_000); r.deliver(); r.failLoad(); r.pending()
      },
      "Immich on an engine style hands images (no videos) to the engine with the key header" to Scenario(mode("mosaic", 17,
          mapOf("source" to ScreensaverConfig.SOURCE_IMMICH, "immich_url" to "https://immich.test", "immich_key" to "k1"))) { r ->
        r.up()
        r.discover(listOf(ImmichSource.Media("https://immich.test/a", false), ImmichSource.Media("https://immich.test/v", true)))
        r.advance(0); r.prepareEngine()
      },
      "Immich with nothing to show falls back to the built-in feed" to Scenario(mode(ScreensaverConfig.MODE_SMART, 17,
          mapOf("source" to ScreensaverConfig.SOURCE_IMMICH, "immich_url" to "https://immich.test", "immich_key" to "k1"))) { r ->
        r.up(); r.deliver(); r.discover(null); r.advance(0); r.pending()
      },
      "shared album on smart frame refreshes on its interval" to Scenario(mode(ScreensaverConfig.MODE_SMART, 17,
          mapOf("source" to ScreensaverConfig.SOURCE_URL, "album_url" to "https://share.test/album", "album_refresh_min" to 15))) { r ->
        r.up(); r.deliver(); r.discover(RemoteAlbum.Album("Trip", listOf("https://cdn.test/1", "https://cdn.test/2")))
        r.advance(0); r.deliver(); r.pending()
      },
      "shared album on an engine style schedules the engine's album refresh" to Scenario(mode("polaroid", 17,
          mapOf("source" to ScreensaverConfig.SOURCE_URL, "album_url" to "https://share.test/album", "album_refresh_min" to 30))) { r ->
        r.up(); r.discover(RemoteAlbum.Album("Trip", listOf("https://cdn.test/1"), mapOf("Cookie" to "c"))); r.advance(0); r.prepareEngine(); r.pending()
      },
      // --- scheduling: pause, host suspension ---------------------------------------------------
      "viewer pause holds the remaining dwell and resumes it" to Scenario(mode(ScreensaverConfig.MODE_SMART, 20),
          options = O.copy(interactivePreview = true)) { r ->
        r.up(); r.deliver(); r.advance(0); r.deliver(); r.advance(5_000); r.viewerPause(true); r.pending()
        r.advance(60_000); r.viewerPause(false); r.pending()
      },
      "host suspension freezes the dwell and shifts the deadline" to Scenario(mode(ScreensaverConfig.MODE_SMART, 20)) { r ->
        r.up(); r.deliver(); r.advance(0); r.deliver(); r.advance(4_000); r.hostActive(false); r.pending()
        r.advance(90_000); r.hostActive(true); r.pending(); r.advance(16_000); r.pending()
      },
      // --- night and dark room ------------------------------------------------------------------
      "dark room: the sensor fades to the quiet clock and back" to Scenario(mode(ScreensaverConfig.MODE_SMART, 17,
          mapOf("dark_room_clock" to true, "dark_room_lux" to 5))) { r ->
        r.up(); r.deliver(); r.room(true); r.pending(); r.advance(20_000); r.touch(DOWN, 1f, 1f); r.touch(UP, 1f, 1f)
        r.room(false); r.advance(20_000); r.pending()
      },
      "dark room in the photo tone keeps photos, warm and dim" to Scenario(mode(ScreensaverConfig.MODE_SMART, 20,
          mapOf("dark_room_clock" to true, "night_clock_tone" to ScreensaverConfig.NIGHT_TONE_PHOTO))) { r ->
        r.up(); r.deliver(); r.room(true); r.advance(0); r.deliver(); r.pending(); r.room(false)
      },
      "night settings broadcast: lux, tone, brightness and turning the sensor off" to Scenario(mode(ScreensaverConfig.MODE_SMART, 17,
          mapOf("dark_room_clock" to true, "dark_room_lux" to 3))) { r ->
        r.up(); r.deliver()
        r.nightSettings(mapOf("dark_room_lux" to 8))
        r.room(true)
        r.nightSettings(mapOf("night_clock_tone" to "red"))
        r.nightSettings(mapOf("night_clock_brightness" to 1))
        r.nightSettings(mapOf("dark_room_clock" to false))
        r.advance(20_000)
      },
      "dark room switched on by a night settings broadcast" to Scenario(mode(ScreensaverConfig.MODE_CLASSIC, 17)) { r ->
        r.up(); r.nightSettings(mapOf("dark_room_clock" to true, "dark_room_lux" to 2)); r.room(true); r.pending()
      },
      // --- the test source and its mode pin -----------------------------------------------------
      "test source replaces the household folder with generated scenes" to Scenario(steps = { r ->
        r.reconfigure(folderPrefs(r, ScreensaverConfig.MODE_SMART, "a.jpg"))
        r.withTestSource(PhotoFrameGoldenRig.TestSource())
        r.up(); r.decodeAsset(2400); r.deliver(); r.advance(0); r.decodeAsset(2400); r.deliver(); r.pending()
      }),
      "test source forces the dark room after the day frame draws" to Scenario(mode(ScreensaverConfig.MODE_SMART, 17,
          mapOf("dark_room_clock" to true)), testSource = PhotoFrameGoldenRig.TestSource(night = true)) { r ->
        r.up(); r.deliver(); r.pending(); r.advance(12_000); r.pending()
      },
      "test source pin holds one mode at a fixed interval" to Scenario(mode(ScreensaverConfig.MODE_CLASSIC, 30),
          testSource = PhotoFrameGoldenRig.TestSource(pinMode = ScreensaverConfig.MODE_PORTRAITS, pinIntervalSec = 9)) { r ->
        r.up(); r.deliver(); r.advance(0); r.deliver(); r.pending()
      },
      "test source pin to an engine style writes scene files for the engine" to Scenario(mode(ScreensaverConfig.MODE_SMART),
          testSource = PhotoFrameGoldenRig.TestSource(pinMode = "cinema", pinIntervalSec = 15)) { r ->
        r.up(); r.drain("io"); r.advance(0); r.prepareEngine()
      },
      "an invalid pin leaves the household mode" to Scenario(mode(ScreensaverConfig.MODE_WHOLE, 25),
          testSource = PhotoFrameGoldenRig.TestSource(pinMode = "noSuchMode", pinIntervalSec = 9)) { r -> r.up(); r.deliver() },
      // --- touch ----------------------------------------------------------------------------------
      "touch: swipes navigate, a tap exits, small moves are ignored" to Scenario(mode(ScreensaverConfig.MODE_SMART)) { r ->
        r.up(); r.deliver(); r.advance(0); r.deliver()
        r.touch(DOWN, 500f, 300f); r.touch(UP, 300f, 310f); r.deliver()
        r.touch(DOWN, 300f, 300f); r.touch(UP, 500f, 290f); r.deliver()
        r.touch(DOWN, 300f, 300f); r.touch(UP, 360f, 300f)
        r.touch(DOWN, 300f, 300f); r.touch(UP, 330f, 330f)
        r.touch(DOWN, 300f, 300f); r.touch(UP, 310f, 320f)
      },
      "touch in the interactive viewer toggles its controls" to Scenario(mode(ScreensaverConfig.MODE_SMART),
          options = O.copy(interactivePreview = true)) { r ->
        r.up(); r.deliver(); r.touch(DOWN, 300f, 300f); r.touch(UP, 305f, 305f)
      },
      "stop releases everything" to Scenario(mode(ScreensaverConfig.MODE_SMART, 17, mapOf("dark_room_clock" to true))) { r ->
        r.up(); r.deliver(); r.stop(); r.pending()
      },
  )
}
