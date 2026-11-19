/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry, BlazeFace decoding and frame sampling, on synthetic numbers (no images of people). */
class WhosTherePipelineTest {

  private fun near(e: Pt, a: Pt, tol: Float = 1e-3f) {
    assertEquals(e.x, a.x, tol)
    assertEquals(e.y, a.y, tol)
  }

  @Test fun affineInverseAndComposition() {
    val m = Affine(2f, 0.5f, 3f, -0.25f, 1.5f, -7f)
    val p = Pt(11f, -4f)
    near(p, m.inverse().map(m.map(p)))
    near(m.map(Affine.IDENTITY.map(p)), Affine.IDENTITY.then(m).map(p))
    val n = Affine(0f, 1f, 2f, -1f, 0f, 5f)
    near(n.map(m.map(p)), m.then(n).map(p))
  }

  @Test(expected = IllegalArgumentException::class)
  fun singularAffineHasNoInverse() {
    Affine(1f, 2f, 0f, 2f, 4f, 0f).inverse()
  }

  @Test fun similarityRecoversAKnownTransform() {
    val s = 1.7f
    val th = 0.3f
    val truth = Affine(s * cos(th), -s * sin(th), 12f, s * sin(th), s * cos(th), -5f)
    val src = listOf(Pt(10f, 10f), Pt(40f, 12f), Pt(25f, 30f), Pt(26f, 45f))
    val fit = FaceGeometry.similarity(src, src.map(truth::map))!!
    src.forEach { near(truth.map(it), fit.map(it), 1e-2f) }
  }

  @Test fun similarityRejectsDegenerateInput() {
    assertNull(FaceGeometry.similarity(listOf(Pt(1f, 1f)), listOf(Pt(1f, 1f))))
    assertNull(FaceGeometry.similarity(listOf(Pt(1f, 1f), Pt(1f, 1f)), listOf(Pt(0f, 0f), Pt(2f, 2f))))
    assertNull(FaceGeometry.similarity(listOf(Pt(1f, 1f), Pt(2f, 2f)), listOf(Pt(0f, 0f))))
  }

  @Test fun alignmentMapsKeypointsOntoTheTemplateEitherEyeOrder() {
    val t = FaceGeometry.TEMPLATE_4
    // A face at 3× the template size, shifted, in a 640×480 frame.
    val kp = t.map { Pt(it.x * 3 + 200, it.y * 3 + 100) }
    listOf(kp[0] to kp[1], kp[1] to kp[0]).forEach { (a, b) ->
      val m = FaceGeometry.alignment(a, b, kp[2], kp[3])!!
      kp.zip(t).forEach { (src, dst) -> near(dst, m.map(src), 0.05f) }
    }
  }

  @Test fun poseAndEyeDistance() {
    assertEquals(10f, FaceGeometry.eyeDistance(Pt(0f, 0f), Pt(6f, 8f)), 1e-5f)
    val (yaw, pitch) = FaceGeometry.pose(Pt(0f, 0f), Pt(10f, 0f), Pt(7f, 6f))
    assertEquals(0.2f, yaw, 1e-5f)
    assertEquals(0.6f, pitch, 1e-5f)
  }

  @Test fun letterboxKeepsAspectAndCentres() {
    val m = FaceGeometry.letterbox(640, 480, 128, 0)
    near(Pt(0f, -80f), m.map(Pt(0f, 0f)), 1e-2f)      // 16 px top pad → −80 frame px
    near(Pt(640f, 560f), m.map(Pt(128f, 128f)), 1e-2f)
    near(Pt(320f, 240f), m.map(Pt(64f, 64f)), 1e-2f)
  }

  @Test fun letterboxRotationsLandInsideTheFrame() {
    for (rot in listOf(90, 180, 270, -90, 450)) {
      val m = FaceGeometry.letterbox(640, 480, 128, rot)
      near(Pt(320f, 240f), m.map(Pt(64f, 64f)), 1e-2f)
    }
    // 90° clockwise: the upright image's top edge is the raw frame's left edge.
    val cw = FaceGeometry.letterbox(640, 480, 128, 90)
    val top = cw.map(Pt(64f, 0f))
    assertEquals(0f, top.x, 1f)
    assertEquals(240f, top.y, 1f)
  }

  @Test fun anchorsMatchTheShortRangeLayout() {
    val a = BlazeFaceDecoder.anchors
    assertEquals(896, a.size)
    near(Pt(1f / 32, 1f / 32), a[0])
    near(Pt(1f / 32, 1f / 32), a[1])
    near(Pt(3f / 32, 1f / 32), a[2])
    near(Pt(1f / 16, 1f / 16), a[512])
    near(Pt(1f / 16, 1f / 16), a[517])
    near(Pt(15f / 16, 15f / 16), a[895])
  }

  private fun output(hits: Map<Int, FloatArray>): Pair<FloatArray, FloatArray> {
    val boxes = FloatArray(896 * 16)
    val scores = FloatArray(896) { -10f }
    hits.forEach { (i, v) ->
      scores[i] = 5f
      v.copyInto(boxes, i * 16)
    }
    return boxes to scores
  }

  @Test fun decodeOneFace() {
    // Anchor 600 is in the stride-16 grid; a 32-px box offset 6 px right, keypoints around it.
    val raw = FloatArray(16).also {
      it[0] = 6f; it[1] = -4f; it[2] = 32f; it[3] = 40f
      for (k in 0 until 6) { it[4 + 2 * k] = k.toFloat(); it[5 + 2 * k] = -k.toFloat() }
    }
    val (b, s) = output(mapOf(600 to raw))
    val faces = BlazeFaceDecoder.decode(b, s)
    assertEquals(1, faces.size)
    val f = faces.single()
    val anchor = BlazeFaceDecoder.anchors[600]
    assertEquals(anchor.x + 6f / 128, (f.xMin + f.xMax) / 2, 1e-5f)
    assertEquals(32f / 128, f.width, 1e-5f)
    assertEquals(40f / 128, f.height, 1e-5f)
    near(Pt(anchor.x + 5f / 128, anchor.y - 5f / 128), f.keypoints[5], 1e-5f)
    assertTrue(f.score > 0.99f)
  }

  @Test fun weightedNmsMergesOverlapsAndKeepsSeparateFaces() {
    val box = FloatArray(16).also { it[2] = 30f; it[3] = 30f }
    val (b, s) = output(mapOf(600 to box, 601 to box.copyOf().also { it[0] = 2f }, 100 to box))
    s[601] = 2f
    val faces = BlazeFaceDecoder.decode(b, s)
    assertEquals(2, faces.size)
    // Merged centre is pulled towards the stronger box, not dropped.
    val merged = faces.maxBy { it.xMin + it.xMax }
    val c = (merged.xMin + merged.xMax) / 2 - BlazeFaceDecoder.anchors[600].x
    assertTrue(c > 0f && c < 2f / 128)
  }

  @Test fun lowScoresAndEmptyBoxesAreDropped() {
    val (b, s) = output(mapOf(10 to FloatArray(16)))
    assertTrue(BlazeFaceDecoder.decode(b, s).isEmpty()) // zero-size box
    assertTrue(BlazeFaceDecoder.decode(FloatArray(896 * 16), FloatArray(896) { -3f }).isEmpty())
    assertEquals(0.5f, BlazeFaceDecoder.sigmoid(0f), 1e-6f)
    assertEquals(1f, BlazeFaceDecoder.sigmoid(1e9f), 1e-6f)
  }

  @Test(expected = IllegalArgumentException::class)
  fun decodeRejectsWrongShapes() {
    BlazeFaceDecoder.decode(FloatArray(10), FloatArray(10))
  }

  @Test fun iouBasics() {
    val a = FaceBox(0f, 0f, 1f, 1f, 1f, emptyList())
    assertEquals(1f, a.iou(a), 1e-6f)
    assertEquals(0f, a.iou(FaceBox(2f, 2f, 3f, 3f, 1f, emptyList())), 1e-6f)
    assertEquals(1f / 3, a.iou(FaceBox(0.5f, 0f, 1.5f, 1f, 1f, emptyList())), 1e-5f)
    assertEquals(0f, FaceBox(0f, 0f, 0f, 0f, 1f, emptyList()).iou(FaceBox(0f, 0f, 0f, 0f, 1f, emptyList())), 0f)
  }

  /** A synthetic YUV frame: luma is a horizontal ramp, chroma neutral (grey, no colour). */
  private fun ramp(w: Int = 64, h: Int = 48, chromaU: Int = 128, chromaV: Int = 128): YuvFrame {
    val y = ByteArray(w * h) { i -> ((i % w) * 255 / (w - 1)).toByte() }
    val uv = (w / 2) * (h / 2)
    return YuvFrame(w, h, y, w, ByteArray(uv) { chromaU.toByte() }, ByteArray(uv) { chromaV.toByte() }, w / 2, 1, 0L)
  }

  @Test fun samplerReadsGreyAndColourAndLeavesOutsideBlack() {
    val f = ramp()
    val px = FaceFrameSampler.rgbAt(f, 0f, 0f)
    assertEquals(0, px)
    val white = FaceFrameSampler.rgbAt(f, 63f, 10f)
    assertEquals(0xFFFFFF, white)
    assertEquals(0, FaceFrameSampler.rgbAt(f, -1f, 0f))
    assertEquals(0, FaceFrameSampler.rgbAt(f, 64.5f, 0f))
    val red = FaceFrameSampler.rgbAt(ramp(chromaV = 255), 32f, 10f)
    assertTrue((red shr 16 and 0xFF) > (red and 0xFF))
  }

  @Test fun sampleFillsBothTensorFormats() {
    val f = ramp()
    val identity = Affine(64f / 8, 0f, 0f, 0f, 48f / 8, 0f) // 8×8 input spanning the frame
    val rgb = FloatArray(8 * 8 * 3)
    FaceFrameSampler.sample(f, identity, 8, TensorFormat.RGB_SIGNED, rgb)
    assertTrue(rgb.all { it in -1f..1f })
    assertTrue(rgb[0] < rgb[(7) * 3]) // brighter to the right
    val bgr = FloatArray(8 * 8 * 3)
    FaceFrameSampler.sample(f, identity, 8, TensorFormat.BGR_255, bgr)
    assertTrue(bgr.all { it in 0f..255f })
    assertEquals(bgr[0], bgr[2], 1f) // grey: B == R
  }

  @Test fun meanLumaAndSharpness() {
    val f = ramp()
    assertEquals(127f, f.meanLuma(1), 3f)
    val flat = FloatArray(16 * 16 * 3) { 100f }
    assertEquals(0f, FaceFrameSampler.sharpness(flat, 16), 1e-3f)
    val checker = FloatArray(16 * 16 * 3) { i -> val p = i / 3; if ((p % 16 + p / 16) % 2 == 0) 0f else 255f }
    assertTrue(FaceFrameSampler.sharpness(checker, 16) > 1000f)
    assertEquals(0f, FaceFrameSampler.sharpness(FloatArray(3), 1), 0f)
  }

  @Test(expected = IllegalArgumentException::class)
  fun frameGeometryIsValidated() {
    YuvFrame(10, 10, ByteArray(100), 5, ByteArray(25), ByteArray(25), 5, 1, 0L)
  }

  @Test fun alignedWarpOfASyntheticFrameStaysFinite() {
    val f = ramp(640, 480)
    val m = FaceGeometry.alignment(Pt(300f, 200f), Pt(340f, 200f), Pt(320f, 225f), Pt(320f, 250f))
    assertNotNull(m)
    val out = FloatArray(112 * 112 * 3)
    FaceFrameSampler.sample(f, m!!.inverse(), 112, TensorFormat.BGR_255, out)
    assertTrue(out.all { it.isFinite() })
  }
}
