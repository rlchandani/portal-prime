/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Directory listing JSON and the download Content-Disposition header (header-injection safety). */
class FleetFsCoverageTest {
  private fun <T> withDir(block: (File) -> T): T {
    val dir = Files.createTempDirectory("fleetfs").toFile()
    try { return block(dir) } finally { dir.deleteRecursively() }
  }

  @Test fun missingPathIsNotFound() = withDir { dir ->
    val out = FleetFs.list(File(dir, "nope").path)
    assertFalse(out.getBoolean("ok"))
    assertEquals("not_found", out.getString("error"))
  }

  @Test fun aFileIsNotADirectory() = withDir { dir ->
    val f = File(dir, "a.txt").apply { writeText("x") }
    assertEquals("not_a_directory", FleetFs.list(f.path).getString("error"))
  }

  @Test fun listsDirectoriesFirstThenFilesCaseInsensitively() = withDir { dir ->
    File(dir, "b.txt").writeText("12345")
    File(dir, "A.txt").writeText("1")
    File(dir, "zdir").mkdir()
    File(dir, "Cdir").mkdir()
    val out = FleetFs.list(dir.path)
    assertTrue(out.getBoolean("ok"))
    assertEquals(dir.absolutePath, out.getString("path"))
    val entries = out.getJSONArray("entries")
    val names = (0 until entries.length()).map { entries.getJSONObject(it).getString("name") }
    assertEquals(listOf("Cdir", "zdir", "A.txt", "b.txt"), names)
    val dirEntry = entries.getJSONObject(0)
    assertEquals("dir", dirEntry.getString("type"))
    assertEquals(0L, dirEntry.getLong("size"))
    val file = entries.getJSONObject(3)
    assertEquals("file", file.getString("type"))
    assertEquals(5L, file.getLong("size"))
    assertTrue(file.getBoolean("readable"))
    assertTrue(file.has("mtime"))
  }

  @Test fun emptyDirectoryListsNoEntries() = withDir { dir ->
    assertEquals(0, FleetFs.list(dir.path).getJSONArray("entries").length())
  }

  @Test fun unreadableDirectoryIsReported() = withDir { dir ->
    val locked = File(dir, "locked").apply { mkdir() }
    try {
      if (!locked.setReadable(false, false) || locked.listFiles() != null) return@withDir // e.g. running as root
      assertEquals("not_readable", FleetFs.list(locked.path).getString("error"))
    } finally {
      locked.setReadable(true, false)
    }
  }

  @Test fun dispositionKeepsPlainAsciiNames() {
    assertEquals("attachment; filename=\"report-1.txt\"; filename*=UTF-8''report-1.txt",
        FleetFs.attachmentDisposition("report-1.txt"))
  }

  @Test fun dispositionNeutralisesHeaderInjectionAndQuotes() {
    val header = FleetFs.attachmentDisposition("a\r\nSet-Cookie: x\"y\\z.txt")
    assertFalse(header.contains("\r") || header.contains("\n"))
    assertTrue(header.startsWith("attachment; filename=\"a__Set-Cookie: x_y_z.txt\";"))
    assertTrue(header.endsWith("filename*=UTF-8''a__Set-Cookie%3A%20x%22y%5Cz.txt"))
  }

  @Test fun dispositionPercentEncodesUtf8AndFallsBackForNonAscii() {
    val header = FleetFs.attachmentDisposition("café ü.txt")
    assertEquals("attachment; filename=\"caf_ _.txt\"; filename*=UTF-8''caf%C3%A9%20%C3%BC.txt", header)
  }

  @Test fun dispositionKeepsRfc5987AttrCharsUnescaped() {
    val header = FleetFs.attachmentDisposition("!#$&+-.^_`|~")
    assertTrue(header.endsWith("UTF-8''!#$&+-.^_`|~"))
  }

  @Test fun emptyNameBecomesDownload() {
    assertEquals("attachment; filename=\"download\"; filename*=UTF-8''download", FleetFs.attachmentDisposition(""))
  }

  @Test fun deleteCharIsControlAndReplaced() {
    assertEquals("attachment; filename=\"a_b\"; filename*=UTF-8''a_b", FleetFs.attachmentDisposition("a\u007fb"))
  }
}
