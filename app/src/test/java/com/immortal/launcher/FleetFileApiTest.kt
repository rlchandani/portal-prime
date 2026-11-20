/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/fs/list`, `/fs/read` and `/fs/write`: the private-path rules, the size cap and the error codes. */
class FleetFileApiTest {
  private val dir: File = Files.createTempDirectory("fleet-fs").toFile().canonicalFile
  private val files = FleetTestFiles()
  private val api = FleetFileApi(files)

  @After fun cleanUp() { dir.deleteRecursively() }

  private fun q(path: String) = "path=" + URLEncoder.encode(path, "UTF-8")

  /** Paths every file route must refuse, as requested, by alias and by `..`. */
  private val privatePaths = listOf(
      "/data/data/com.immortal.launcher.debug", "/data/data/com.immortal.launcher.debug/shared_prefs/fleet_agent.xml",
      "/data/user/0/com.immortal.launcher.debug/databases/x.db", "/data/user_de/0/com.immortal.launcher.debug/files",
      "/data_mirror/data_ce/null/0/com.immortal.launcher.debug/no_backup", "/data/data/other.app/files",
      "/sdcard/Android/data/com.immortal.launcher.debug/files/fleet", "/sdcard/Android/data/com.immortal.launcher.debug/files/fleet/agent.json",
      "/storage/emulated/0/Android/data/com.immortal.launcher/files/fleet/agent.json",
      "/proc/self/fd", "/proc/self/fd/3", "/proc/1/environ", "/proc/self/mem", "/proc/self/root/data/data/x",
      "/proc/self/cwd", "/proc/self/map_files", "/sdcard/../data/data/com.immortal.launcher.debug/shared_prefs",
      "/sdcard/DCIM/../../data/user/0/x/y")

  @Test fun everyPrivatePathIsRefusedByListReadAndWrite() {
    for (path in privatePaths) {
      assertEquals("list $path", "403 protected_path", FleetReq.outcome(api.list(FleetReq.get("/fs/list", q(path)))))
      assertEquals("read $path", "403 protected_path", FleetReq.outcome(api.read(FleetReq.get("/fs/read", q(path)))))
      val write = FleetReq.of("POST", "/fs/write", "x", q(path))
      assertEquals("write $path", "403 protected_path", FleetReq.outcome(api.write(write)))
      assertEquals("x", write.bodyText()) // refused before reading the body
    }
    assertTrue(files.listed.isEmpty())
  }

  @Test fun theToolsPathsStayOpen() {
    for (path in listOf("/sdcard", "/sdcard/Download/a.apk", "/data/app/com.immortal.launcher-1/base.apk",
        "/proc/self/maps", "/proc/self/cmdline", "/sdcard/Android/data/other.app/files/x", dir.path)) {
      assertFalse(path, JvmFleetFiles.isPrivate(File(path)))
    }
  }

  @Test fun listDefaultsToSdcardAndReportsWhatItFinds() {
    api.list(FleetReq.get("/fs/list"))
    assertEquals(listOf("/sdcard"), files.listed)
    File(dir, "b.txt").writeText("12345")
    File(dir, "A").mkdirs()
    val o = FleetReq.json(api.list(FleetReq.get("/fs/list", q(dir.path))))
    assertEquals(dir.absolutePath, o.getString("path"))
    val entries = o.getJSONArray("entries")
    assertEquals("A", entries.getJSONObject(0).getString("name"))
    assertEquals("dir", entries.getJSONObject(0).getString("type"))
    assertEquals(5L, entries.getJSONObject(1).getLong("size"))
    // A missing path or a file is reported in the body, with 200.
    assertEquals("200 not_found", FleetReq.outcome(api.list(FleetReq.get("/fs/list", q("$dir/none")))))
    assertEquals("200 not_a_directory", FleetReq.outcome(api.list(FleetReq.get("/fs/list", q("$dir/b.txt")))))
  }

  @Test fun readNeedsAReadableFile() {
    assertEquals("400 path_required", FleetReq.outcome(api.read(FleetReq.get("/fs/read"))))
    assertEquals("404 not_a_readable_file", FleetReq.outcome(api.read(FleetReq.get("/fs/read", q("$dir/none")))))
    assertEquals("404 not_a_readable_file", FleetReq.outcome(api.read(FleetReq.get("/fs/read", q(dir.path)))))
    val f = File(dir, "notes é.txt").apply { writeText("hello") }
    val resp = api.read(FleetReq.get("/fs/read", q(f.path)))
    assertEquals(200, resp.status)
    assertEquals("application/octet-stream", resp.contentType)
    assertEquals(5L, resp.streamLength)
    assertEquals(FleetFs.attachmentDisposition("notes é.txt"), resp.extraHeaders["Content-Disposition"])
    assertEquals("hello", FleetReq.text(resp))
  }

  @Test fun anUnreadableFileIsRefused() {
    val f = File(dir, "locked").apply { writeText("x"); setReadable(false) }
    if (f.canRead()) return // running as root: nothing to check
    assertEquals("404 not_a_readable_file", FleetReq.outcome(api.read(FleetReq.get("/fs/read", q(f.path)))))
  }

  @Test fun writeNeedsAPath() {
    assertEquals("400 path_required", FleetReq.outcome(api.write(FleetReq.of("POST", "/fs/write", "abc"))))
  }

  @Test fun theSizeCapIsCheckedBeforeAByteIsRead() {
    val max = FsWritePolicy.MAX_BYTES
    assertEquals(512L * 1024 * 1024, max)
    // A body that fails the test if anything reads it.
    val untouchable = object : java.io.InputStream() { override fun read(): Int = error("body read") }
    val over = FleetHttpServer.Request("POST", "/fs/write", q("$dir/big"), emptyMap(), untouchable, (max + 1).toInt())
    val resp = api.write(over)
    val o = FleetReq.json(resp)
    assertEquals(413, resp.status)
    assertEquals("body_too_large", o.getString("error"))
    assertEquals(max, o.getLong("maxBytes"))
    assertEquals(FsWritePolicy.MIN_FREE_AFTER_BYTES, o.getLong("minFreeAfterBytes"))
    assertFalse(File(dir, "big").exists())
  }

  @Test fun theWriteMustLeaveTheMinimumFree() {
    val min = FsWritePolicy.MIN_FREE_AFTER_BYTES
    files.usableBytes = min + 2
    val tooMuch = api.write(FleetReq.of("POST", "/fs/write", "abc", q("$dir/a")))
    assertEquals("507 insufficient_storage", FleetReq.outcome(tooMuch))
    assertEquals(FsWritePolicy.MAX_BYTES, FleetReq.json(api.write(FleetReq.of("POST", "/fs/write", "abc", q("$dir/a")))).getLong("maxBytes"))
    // Exactly the minimum left after the write is enough.
    files.usableBytes = min + 3
    assertEquals("200 ok", FleetReq.outcome(api.write(FleetReq.of("POST", "/fs/write", "abc", q("$dir/a")))))
  }

  @Test fun writeCreatesParentsAndReportsTheBytes() {
    val target = File(dir, "new dir/deeper/up.bin")
    val o = FleetReq.json(api.write(FleetReq.of("POST", "/fs/write", "uploaded", q(target.path))))
    assertEquals(target.absolutePath, o.getString("path"))
    assertEquals(8L, o.getLong("bytes"))
    assertEquals("uploaded", target.readText())
    // An empty body writes an empty file.
    assertEquals(0L, FleetReq.json(api.write(FleetReq.of("POST", "/fs/write", "", q(target.path)))).getLong("bytes"))
    assertEquals(0L, target.length())
  }

  @Test fun aTruncatedBodyIsA400AndLeavesTheOldFile() {
    val target = File(dir, "keep.txt").apply { writeText("old") }
    val resp = api.write(FleetReq.of("POST", "/fs/write", "abc", q(target.path), contentLength = 10))
    val o = FleetReq.json(resp)
    assertEquals(400, resp.status)
    assertEquals("write_failed", o.getString("error"))
    assertEquals("Incomplete upload body", o.getString("detail"))
    assertEquals("old", target.readText())
  }

  @Test fun otherWriteFailuresAre500WithTheirMessage() {
    files.writeError = IOException("disk on fire")
    val o = api.write(FleetReq.of("POST", "/fs/write", "abc", q("$dir/x")))
    assertEquals(500, o.status)
    assertEquals("disk on fire", FleetReq.json(o).getString("detail"))
    files.writeError = IllegalStateException()
    assertEquals("", FleetReq.json(api.write(FleetReq.of("POST", "/fs/write", "abc", q("$dir/x")))).getString("detail"))
  }

  @Test fun usableSpaceIsMeasuredOnTheNearestExistingAncestor() {
    assertTrue(JvmFleetFiles.usableSpaceFor(File(dir, "a/b/c/d.bin")) > 0)
    assertEquals(dir.usableSpace > 0, JvmFleetFiles.usableSpaceFor(File(dir, "x")) > 0)
    assertTrue(JvmFleetFiles.usableSpaceFor(File("/")) >= 0)
  }
}
