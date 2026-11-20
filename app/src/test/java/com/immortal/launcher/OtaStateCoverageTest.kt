/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** OTA state wire, Settings row copy for every phase, and the transport's pure guards. */
class OtaStateCoverageTest {
  private val base = OtaState(installedVersionCode = 223, installedVersionName = "1.228")
  private val available = OtaManifest("com.immortal.launcher", 224, "1.229", "a".repeat(64), 1_000,
      "https://github.com/x/y/releases/download/v/app.apk", 29, "Wireless updates")

  @Test fun busyOnlyWhileWorking() {
    val busy = OtaPhase.entries.filter { base.copy(phase = it).busy }.toSet()
    assertEquals(setOf(OtaPhase.CHECKING, OtaPhase.DOWNLOADING, OtaPhase.INSTALLING), busy)
  }

  @Test fun decodeIgnoresNonBooleanForceAndNonStringAction() {
    assertEquals(OtaRequest(OtaAction.CHECK, false), OtaWire.decode(JSONObject().put("action", "check").put("force", "maybe")))
    assertNull(OtaWire.decode(JSONObject().put("action", 1)))
    assertNull(OtaWire.decode(JSONObject().put("action", "CHECK")))
  }

  @Test fun retryBlockAppearsOnlyAfterAFailedConfirmation() {
    assertTrue(OtaWire.toJson(base).isNull("retry"))
    val scheduled = OtaWire.toJson(base.copy(confirmFailures = 2, nextRetryAtMs = 1_700_000_000_000)).getJSONObject("retry")
    assertEquals(2, scheduled.getInt("failures"))
    assertEquals(1_700_000_000_000, scheduled.getLong("nextAt"))
    val unscheduled = OtaWire.toJson(base.copy(confirmFailures = 1)).getJSONObject("retry")
    assertTrue(unscheduled.isNull("nextAt"))
  }

  @Test fun idleStateJsonHasNullWaitAndDevModeFlag() {
    val json = OtaWire.toJson(base.copy(devMode = true))
    assertEquals("idle", json.getString("status"))
    assertTrue(json.isNull("waitingFor"))
    assertTrue(json.isNull("error"))
    assertTrue(json.getBoolean("devMode"))
    assertEquals(1_000L, OtaWire.toJson(base.copy(available = available)).getJSONObject("available").getLong("size"))
  }

  @Test fun rowCopyForTheRemainingPhases() {
    assertEquals("Not checked yet", OtaCopy.row(base, null).subtitle)
    assertEquals("Checking for updates…", OtaCopy.row(base.copy(phase = OtaPhase.CHECKING), "3:12 PM").subtitle)
    assertEquals("Up to date", OtaCopy.row(base.copy(phase = OtaPhase.UP_TO_DATE), null).subtitle)
    val downloading = OtaCopy.row(base.copy(phase = OtaPhase.DOWNLOADING, available = available), null)
    assertEquals("Downloading Hearthpane 1.229…", downloading.subtitle)
    assertFalse(downloading.enabled)
    assertEquals(OtaCopy.UPDATE_NOW, downloading.action)
    val installing = OtaCopy.row(base.copy(phase = OtaPhase.INSTALLING, available = available), null)
    assertEquals("Installing Hearthpane 1.229. Hearthpane restarts when it's done.", installing.subtitle)
    assertEquals("Downloading Hearthpane…", OtaCopy.row(base.copy(phase = OtaPhase.DOWNLOADING), null).subtitle)
  }

  @Test fun availableWithoutNotesAndFailedWithoutErrorFallBack() {
    assertEquals("Hearthpane 1.229 available", OtaCopy.row(base.copy(phase = OtaPhase.AVAILABLE, available = available.copy(notes = " ")), null).subtitle)
    assertEquals(OtaError.INSTALL.message, OtaCopy.row(base.copy(phase = OtaPhase.FAILED), null).subtitle)
    // Up to date with a stale manifest still offers only a check.
    assertEquals(OtaCopy.CHECK, OtaCopy.row(base.copy(phase = OtaPhase.UP_TO_DATE, available = available), null).action)
    // A failure with an update still known offers to update.
    assertEquals(OtaCopy.UPDATE_NOW, OtaCopy.row(base.copy(phase = OtaPhase.FAILED, available = available), null).action)
  }

  @Test fun titleDropsABlankInstalledVersion() {
    assertEquals("Hearthpane", OtaCopy.row(base.copy(installedVersionName = ""), null).title)
    assertEquals("Hearthpane", OtaCopy.row(base.copy(installedVersionName = "  ", devMode = true), null).title)
  }

  @Test fun transportRefusesNonGithubUrlsBeforeOpeningAnything() {
    val e = assertThrows(IOException::class.java) { OtaHttp.fetchManifest("http://example.invalid/feed.json") }
    assertEquals("update URL not allowed", e.message)
    val apk = available.copy(apkUrl = "https://example.invalid/app.apk")
    val dest = File.createTempFile("ota-test", ".apk").apply { deleteOnExit() }
    assertThrows(IOException::class.java) { OtaHttp.downloadApk(apk, dest) }
    dest.delete()
  }

  @Test fun copyMakesProgressWhenABulkReadReturnsZero() {
    val data = byteArrayOf(1, 2, 3)
    // Returns 0 from every bulk read; only single-byte reads deliver data.
    val stubborn = object : InputStream() {
      var i = 0
      override fun read(): Int = if (i < data.size) data[i++].toInt() else -1
      override fun read(b: ByteArray, off: Int, len: Int): Int = 0
    }
    val out = ByteArrayOutputStream()
    val result = OtaHttp.copyBounded(stubborn, out, 3)
    assertEquals(3L, result.bytes)
    assertArrayEquals(data, out.toByteArray())
    assertEquals(OtaHttp.copyBounded(ByteArrayInputStream(data), ByteArrayOutputStream(), 3).sha256, result.sha256)
  }

  @Test fun anEmptyBodyHashesToTheEmptyDigest() {
    val r = OtaHttp.copyBounded(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), 0)
    assertEquals(0L, r.bytes)
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", r.sha256)
  }

  @Test fun anInterruptedThreadStopsTheCopy() {
    Thread.currentThread().interrupt()
    try {
      val e = assertThrows(IOException::class.java) {
        OtaHttp.copyBounded(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 1)
      }
      assertEquals("download interrupted", e.message)
    } finally {
      Thread.interrupted() // clear the flag so other tests are unaffected
    }
  }

  @Test fun integrityExceptionIsAnIoException() {
    val e: IOException = OtaIntegrityException("too big")
    assertEquals("too big", e.message)
  }
}
