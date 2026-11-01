/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

class FaceFocusStoreTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun `legacy face entry with no faces on reanalysis is not analyzed again by a peer`() {
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.filesDir).thenReturn(temporary.root)
    val url = "file://${temporary.newFile("old-face.jpg").apply { writeBytes(byteArrayOf(1)) }.path}"
    val file = temporary.root.resolve("face_focus.json")
    file.writeText(JSONObject().put(url, "0.2,0.3,0.8").toString())
    val first = FaceFocusStore(context)
    val peer = FaceFocusStore(context)
    try {
      withDetector { first.warmUp(listOf(url)) }
      Mockito.mockStatic(FaceDetection::class.java).use { detection ->
        detection.`when`<FaceDetector> { FaceDetection.getClient(Mockito.any()) }
            .thenThrow(AssertionError("Legacy no-face upgrade was retried"))
        peer.warmUp(listOf(url))
      }
      assertEquals(FaceFocus.Focus(.2f, .3f), peer.focusFor(url))
      assertEquals(.8f, peer.weightFor(url), 0f)
      assertEquals("0.2,0.3,0.8", JSONObject(file.readText()).getString(url))
    } finally { first.close(); peer.close() }
  }

  @Test fun `seven field regions are validated without losing legacy point or weight`() {
    val file = temporary.root.resolve("face_focus.json")
    val json = JSONObject().put("valid", "0.4,0.6,0.8,0.1,0.2,0.7,0.9")
    val invalid = listOf("NaN,0.2,0.7,0.9", "0.1,Infinity,0.7,0.9",
        "-0.1,0.2,0.7,0.9", "0.1,0.2,1.1,0.9", "0.7,0.2,0.1,0.9",
        "0.1,0.9,0.7,0.2", "0.1,0.2,0.1,0.9", "0.1,0.2,0.7,0.2",
        "bad,0.2,0.7,0.9")
    invalid.forEachIndexed { i, bounds -> json.put("bad$i", "0.4,0.6,0.8,$bounds") }
    file.writeText(json.toString())
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.filesDir).thenReturn(temporary.root)
    val store = FaceFocusStore(context)
    try {
      assertEquals(FaceFocus.Focus(.4f, .6f, FaceFocus.Bounds(.1f, .2f, .7f, .9f)),
          store.focusFor("valid"))
      invalid.indices.forEach { i ->
        assertEquals(FaceFocus.Focus(.4f, .6f), store.focusFor("bad$i"))
        assertEquals(.8f, store.weightFor("bad$i"), 0f)
      }
    } finally { store.close() }
  }

  @Test fun `weighted legacy metadata is upgraded once and persisted with detected bounds`() {
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.filesDir).thenReturn(temporary.root)
    val url = "file://${temporary.newFile("face.jpg").apply { writeBytes(byteArrayOf(1)) }.path}"
    val file = temporary.root.resolve("face_focus.json")
    file.writeText(JSONObject().put(url, "0.2,0.3,0.8").toString())
    val store = FaceFocusStore(context)
    val face = Mockito.mock(Face::class.java)
    val rect = Mockito.mock(Rect::class.java).apply {
      left = 32; top = 48; right = 128; bottom = 144
    }
    Mockito.`when`(face.boundingBox).thenReturn(rect)
    try {
      withDetector(listOf(face)) { store.warmUp(listOf(url, url)) }
      val first = JSONObject(file.readText()).getString(url)
      val parts = first.split(",").map { it.toFloat() }
      assertEquals(7, parts.size)
      assertEquals(listOf(.25f, .4f, .1f, .2f, .4f, .6f),
          listOf(parts[0], parts[1]) + parts.drop(3))
      assertEquals(.73f, parts[2], 1e-5f)
      // A complete region skips SDK work entirely on subsequent warm-ups.
      Mockito.mockStatic(FaceDetection::class.java).use { detection ->
        detection.`when`<FaceDetector> { FaceDetection.getClient(Mockito.any()) }
            .thenThrow(AssertionError("Complete region was reanalyzed"))
        store.warmUp(listOf(url))
      }
      assertEquals(first, JSONObject(file.readText()).getString(url))
    } finally { store.close() }
  }

  @Test fun `stores hydrated before either analysis preserve both cache updates`() {
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.filesDir).thenReturn(temporary.root)
    val first = FaceFocusStore(context)
    val second = FaceFocusStore(context)
    val a = "file://${temporary.newFile("a.jpg").apply { writeBytes(byteArrayOf(1)) }.path}"
    val b = "file://${temporary.newFile("b.jpg").apply { writeBytes(byteArrayOf(1)) }.path}"
    // Both stores saw the same old snapshot. A later warm-up must not erase its peer's result.
    assertNull(first.focusFor(a))
    assertNull(second.focusFor(b))
    withDetector {
      first.warmUp(listOf(a))
      second.warmUp(listOf(b))
    }
    val disk = JSONObject(temporary.root.resolve("face_focus.json").readText())
    assertTrue("First successful analysis was overwritten", disk.has(a))
    assertTrue(disk.has(b))
    assertEquals("", disk.getString(a))
    assertEquals("", disk.getString(b))
  }

  @Test fun `failed analysis does not rewrite or create a cache snapshot`() {
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.filesDir).thenReturn(temporary.root)
    withDetector { FaceFocusStore(context).warmUp(listOf("unsupported://photo")) }
    assertFalse(temporary.root.resolve("face_focus.json").exists())
  }

  @Test fun `closed store does not start analysis or publish no-face results`() {
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.filesDir).thenReturn(temporary.root)
    val url = "file://${temporary.newFile("photo.jpg").apply { writeBytes(byteArrayOf(1)) }.path}"
    val store = FaceFocusStore(context)
    store.close()
    withDetector { store.warmUp(listOf(url)) }
    assertFalse(temporary.root.resolve("face_focus.json").exists())
  }

  @Test fun `legacy focus and people weight remain readable without analysis`() {
    temporary.root.resolve("face_focus.json").writeText(
        """{"old":"0.25,0.75","weighted":"0.4,0.6,0.8","empty":""}""")
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.filesDir).thenReturn(temporary.root)
    val store = FaceFocusStore(context)
    assertEquals(FaceFocus.Focus(.25f, .75f), store.focusFor("old"))
    assertEquals(0f, store.weightFor("old"), 0f)
    assertEquals(FaceFocus.Focus(.4f, .6f), store.focusFor("weighted"))
    assertEquals(.8f, store.weightFor("weighted"), 0f)
    assertNull(store.focusFor("empty"))
  }

  /** Mock only Android decoding and the SDK. Cache and warm-up behavior stay real. */
  private fun withDetector(faces: List<Face> = emptyList(), block: () -> Unit) {
    val bitmap = Mockito.mock(Bitmap::class.java)
    Mockito.`when`(bitmap.width).thenReturn(320)
    Mockito.`when`(bitmap.height).thenReturn(240)
    val detector = Mockito.mock(FaceDetector::class.java)
    @Suppress("UNCHECKED_CAST")
    val task = Mockito.mock(Task::class.java) as Task<List<Face>>
    Mockito.`when`(task.isComplete).thenReturn(true)
    Mockito.`when`(detector.process(Mockito.any(InputImage::class.java))).thenReturn(task)
    Mockito.mockStatic(Log::class.java).use {
      Mockito.mockConstruction(BitmapFactory.Options::class.java).use {
        Mockito.mockStatic(BitmapFactory::class.java).use { decoding ->
          decoding.`when`<Bitmap> {
            BitmapFactory.decodeByteArray(Mockito.any(ByteArray::class.java), Mockito.anyInt(),
                Mockito.anyInt(), Mockito.any(BitmapFactory.Options::class.java))
          }.thenAnswer { call ->
            val options = call.getArgument<BitmapFactory.Options>(3)
            options.outWidth = 640
            options.outHeight = 480
            if (options.inJustDecodeBounds) null else bitmap
          }
          Mockito.mockStatic(InputImage::class.java).use { images ->
            images.`when`<InputImage> { InputImage.fromBitmap(bitmap, 0) }
                .thenReturn(Mockito.mock(InputImage::class.java))
            Mockito.mockStatic(FaceDetection::class.java).use { detection ->
              detection.`when`<FaceDetector> { FaceDetection.getClient(Mockito.any()) }.thenReturn(detector)
              Mockito.mockStatic(Tasks::class.java).use { tasks ->
                tasks.`when`<List<Face>> {
                  Tasks.await(Mockito.eq(task), Mockito.anyLong(), Mockito.any())
                }.thenReturn(faces)
                block()
              }
            }
          }
        }
      }
    }
  }
}
