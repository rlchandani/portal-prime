/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.net.URLEncoder
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Actual private /fs/read boundary; generated files only, no sockets, auth or Android services. */
class FleetFileReadSnapshotTest {
  @Test fun ordinaryFileKeepsItsDeclaredLengthAndBytes() = withDirectory { folder ->
    val file = File(folder, "data.bin").apply { writeText("OLD4") }
    val response = read(file)
    assertEquals(200, response.status)
    assertEquals("application/octet-stream", response.contentType)
    assertEquals(4L, response.streamLength)
    assertEquals("OLD4", body(response))
  }

  @Test fun longerAtomicReplacementCannotChangeAnAlreadySelectedDownload() =
      withDirectory { folder ->
        val file = File(folder, "data.bin").apply { writeText("OLD4") }
        val response = read(file)
        publish(file, "NEW-NEW8")
        assertEquals(4L, response.streamLength)
        assertEquals("OLD4", body(response))
        assertEquals("NEW-NEW8", file.readText())
      }

  @Test fun sameSizeAtomicReplacementCannotSilentlyChangeSelectedBytes() =
      withDirectory { folder ->
        val file = File(folder, "data.bin").apply { writeText("OLD4") }
        val response = read(file)
        publish(file, "NEW4")
        assertEquals(4L, response.streamLength)
        assertEquals("OLD4", body(response))
        assertEquals("NEW4", file.readText())
      }

  @Test fun unlinkAfterResponseCreationStillServesTheOpenedFile() = withDirectory { folder ->
    val file = File(folder, "data.bin").apply { writeText("OLD4") }
    val response = read(file)
    assertTrue(file.delete())
    assertEquals(4L, response.streamLength)
    assertEquals("OLD4", body(response))
    assertFalse(file.exists())
  }

  @Test fun appendingToTheSameInodeCannotExceedTheAdvertisedLength() =
      withDirectory { folder ->
        val file = File(folder, "data.bin").apply { writeText("OLD4") }
        val response = read(file)
        FileOutputStream(file, true).use { it.write("TAIL".toByteArray()) }
        assertEquals(4L, response.streamLength)
        assertEquals("OLD4", body(response))
        assertEquals("OLD4TAIL", file.readText())
      }

  @Test fun shortenedSameInodeFailsRatherThanCompletingAnUndersizedResponse() =
      withDirectory { folder ->
        val file = File(folder, "data.bin").apply { writeText("OLD4") }
        val response = read(file)
        RandomAccessFile(file, "rw").use { it.setLength(2) }
        val out = ByteArrayOutputStream()
        response.use {
          assertThrows(EOFException::class.java) { it.streamWriter!!(out) }
        }
        assertEquals(4L, response.streamLength)
        assertEquals("OL", out.toString("UTF-8"))
      }

  @Test fun emptyOrdinaryFileDoesNotAdmitBytesAppendedAfterSelection() =
      withDirectory { folder ->
        val file = File(folder, "empty.bin").apply { writeText("") }
        val response = read(file)
        FileOutputStream(file, true).use { it.write("TAIL".toByteArray()) }
        assertEquals(0L, response.streamLength)
        assertEquals("", body(response))
      }

  @Test fun unchangedEmptyOrdinaryFileHasAnEmptyFixedLengthResponse() =
      withDirectory { folder ->
        val file = File(folder, "empty.bin").apply { writeText("") }
        val response = read(file)
        assertEquals(200, response.status)
        assertEquals(0L, response.streamLength)
        assertEquals("", body(response))
      }

  @Test fun missingPathIsRejectedBeforeOpeningAnyFile() {
    val response = read(null)
    assertEquals(400, response.status)
    assertEquals("path_required", JSONObject(response.jsonBytes!!.toString(Charsets.UTF_8)).getString("error"))
    assertNull(response.streamWriter)
    response.close()
  }

  @Test fun directoriesAndMissingFilesKeepTheExistingReadableFileGuard() =
      withDirectory { folder ->
        for (file in listOf(folder, File(folder, "missing.bin"))) {
          val response = read(file)
          assertEquals(404, response.status)
          assertEquals("not_a_readable_file", JSONObject(response.jsonBytes!!.toString(Charsets.UTF_8)).getString("error"))
          assertNull(response.streamWriter)
          response.close()
        }
      }

  @Test fun abandonedResponseCloseRetiresTheCapturedDescriptor() = withDirectory { folder ->
    val file = File(folder, "data.bin").apply { writeText("OLD4") }
    val response = read(file)
    response.close()
    response.close()
    assertThrows(IOException::class.java) {
      response.streamWriter!!(ByteArrayOutputStream())
    }
  }

  @Test fun failedOutputRetiresTheDescriptorBeforeResponseClose() = withDirectory { folder ->
    val file = File(folder, "data.bin").apply { writeText("OLD4") }
    val response = read(file)
    try {
      assertThrows(IOException::class.java) {
        response.streamWriter!!(object : OutputStream() {
          override fun write(value: Int) { throw IOException("owned output failure") }
        })
      }
      // A second invocation cannot reopen the path after the first transfer failed.
      assertThrows(IOException::class.java) {
        response.streamWriter!!(ByteArrayOutputStream())
      }
    } finally {
      response.close()
    }
  }

  private fun publish(file: File, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    assertEquals(bytes.size.toLong(), FleetUploadWriter.write(file) {
      it.write(bytes)
      bytes.size.toLong()
    })
  }

  private fun read(file: File?): FleetHttpServer.Response {
    val context = Mockito.mock(Context::class.java)
    val routes = FleetRoutes(context)
    val query = if (file == null) "" else
      "path=" + URLEncoder.encode(file.absolutePath, "UTF-8")
    val request = FleetHttpServer.Request("GET", "/fs/read", query, emptyMap(),
        ByteArrayInputStream(byteArrayOf()), 0)
    val method = FleetRoutes::class.java.getDeclaredMethod(
        "fsRead", FleetHttpServer.Request::class.java)
    method.isAccessible = true
    val response = try {
      method.invoke(routes, request) as FleetHttpServer.Response
    } catch (error: InvocationTargetException) {
      throw error.targetException
    }
    Mockito.verifyNoInteractions(context)
    return response
  }

  private fun body(response: FleetHttpServer.Response): String {
    val out = ByteArrayOutputStream()
    response.use { it.streamWriter!!(out) }
    return out.toString("UTF-8")
  }

  private fun withDirectory(operation: (File) -> Unit) {
    val folder = Files.createTempDirectory("fleet-read-snapshot-").toFile()
    try { operation(folder) }
    finally { folder.deleteRecursively() }
  }
}
