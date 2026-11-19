/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/** A detection mapped into frame pixels, with what the enrolment gate needs. */
internal data class FrameFace(val box: FaceBox, val frameWidth: Int, val upright: List<Pt> = box.keypoints) {
  val widthFraction: Float get() = box.width / frameWidth
  /** Head pose (yaw, pitch) measured in the upright model space, so rotation can't swap them. */
  val pose: Pair<Float, Float> get() = FaceGeometry.pose(upright[0], upright[1], upright[2])
  val eyeA: Pt get() = box.keypoints[0]
  val eyeB: Pt get() = box.keypoints[1]
  val nose: Pt get() = box.keypoints[2]
  val mouth: Pt get() = box.keypoints[3]
}

/** One face's embedding plus its quality measurements. The aligned pixels are dropped here. */
internal data class FaceSample(val face: FrameFace, val embedding: Embedding?, val sharpness: Float)

/**
 * The two bundled TFLite models. Not thread-safe: one analysis thread owns an instance.
 * [load] never throws: any failure (missing asset, 32-bit process without the native library,
 * corrupt model) returns a user-facing reason and recognition degrades to "off".
 */
internal class FaceModels private constructor(
    private val detector: Interpreter,
    private val embedder: Interpreter,
) : Closeable {
  private val detIn = ByteBuffer.allocateDirect(4 * DET * DET * 3).order(ByteOrder.nativeOrder())
  private val detPixels = FloatArray(DET * DET * 3)
  private val detBoxes = Array(1) { Array(BlazeFaceDecoder.ANCHORS) { FloatArray(BlazeFaceDecoder.VALUES) } }
  private val detScores = Array(1) { Array(BlazeFaceDecoder.ANCHORS) { FloatArray(1) } }
  private val detOutputs = HashMap<Int, Any>()
  private val embIn = ByteBuffer.allocateDirect(4 * EMB * EMB * 3).order(ByteOrder.nativeOrder())
  private val embPixels = FloatArray(EMB * EMB * 3)
  private val embOut = Array(1) { FloatArray(WhosThereLimits.EMBEDDING_SIZE) }
  private val boxIndex: Int
  private val scoreIndex: Int

  init {
    val names = (0 until detector.outputTensorCount).associateBy { detector.getOutputTensor(it).name() }
    boxIndex = names["regressors"] ?: 0
    scoreIndex = names["classificators"] ?: 1
  }

  /** Faces in [frame], largest first, at [rotation] degrees (0/90/180/270) to make them upright. */
  fun detect(frame: YuvFrame, rotation: Int): List<FrameFace> {
    val toFrame = FaceGeometry.letterbox(frame.width, frame.height, DET, rotation)
    FaceFrameSampler.sample(frame, toFrame, DET, TensorFormat.RGB_SIGNED, detPixels)
    detIn.rewind()
    detIn.asFloatBuffer().put(detPixels)
    detOutputs[boxIndex] = detBoxes
    detOutputs[scoreIndex] = detScores
    detector.runForMultipleInputsOutputs(arrayOf<Any>(detIn), detOutputs)
    val flatBoxes = FloatArray(BlazeFaceDecoder.ANCHORS * BlazeFaceDecoder.VALUES)
    val flatScores = FloatArray(BlazeFaceDecoder.ANCHORS)
    for (i in 0 until BlazeFaceDecoder.ANCHORS) {
      detBoxes[0][i].copyInto(flatBoxes, i * BlazeFaceDecoder.VALUES)
      flatScores[i] = detScores[0][i][0]
    }
    return BlazeFaceDecoder.decode(flatBoxes, flatScores).map { toFramePixels(it, toFrame, frame.width) }
        .sortedByDescending { it.box.area }
  }

  /** Aligns [face] to 112×112 and embeds it with SFace. Null embedding if alignment fails. */
  fun embed(frame: YuvFrame, face: FrameFace): FaceSample {
    val align = FaceGeometry.alignment(face.eyeA, face.eyeB, face.nose, face.mouth)
        ?: return FaceSample(face, null, 0f)
    FaceFrameSampler.sample(frame, align.inverse(), EMB, TensorFormat.BGR_255, embPixels)
    val sharp = FaceFrameSampler.sharpness(embPixels, EMB)
    embIn.rewind()
    embIn.asFloatBuffer().put(embPixels)
    embedder.run(embIn, embOut)
    return FaceSample(face, Embedding.of(embOut[0]), sharp)
  }

  /** Runs both models once on a synthetic, non-person input; returns (detect ms, embed ms). */
  fun benchmark(runs: Int = 5): Pair<Double, Double> {
    val w = 640
    val h = 480
    val y = ByteArray(w * h) { i -> (((i % w) xor (i / w)) and 0xFF).toByte() } // XOR texture, no faces
    val uv = ByteArray(w * h / 4) { 128.toByte() }
    val frame = YuvFrame(w, h, y, w, uv, uv.copyOf(), w / 2, 1, 0L)
    val fake = FrameFace(FaceBox(200f, 120f, 440f, 360f, 1f,
        listOf(Pt(280f, 200f), Pt(360f, 200f), Pt(320f, 250f), Pt(320f, 300f), Pt(220f, 210f), Pt(420f, 210f))), w)
    detect(frame, 0); embed(frame, fake) // warm-up
    var det = 0L
    var emb = 0L
    repeat(runs) {
      val t0 = SystemClock.elapsedRealtimeNanos()
      detect(frame, 0)
      val t1 = SystemClock.elapsedRealtimeNanos()
      embed(frame, fake)
      det += t1 - t0
      emb += SystemClock.elapsedRealtimeNanos() - t1
    }
    return det / runs / 1e6 to emb / runs / 1e6
  }

  override fun close() {
    runCatching { detector.close() }
    runCatching { embedder.close() }
  }

  private fun toFramePixels(b: FaceBox, toFrame: Affine, width: Int): FrameFace {
    fun px(p: Pt) = toFrame.map(Pt(p.x * DET, p.y * DET))
    val c1 = px(Pt(b.xMin, b.yMin))
    val c2 = px(Pt(b.xMax, b.yMax))
    val box = FaceBox(minOf(c1.x, c2.x), minOf(c1.y, c2.y), maxOf(c1.x, c2.x), maxOf(c1.y, c2.y), b.score, b.keypoints.map(::px))
    return FrameFace(box, width, b.keypoints)
  }

  companion object {
    private const val TAG = "HearthpaneWhosThere"
    const val DET = BlazeFaceDecoder.INPUT
    const val EMB = FaceGeometry.ALIGNED_SIZE
    const val DETECTOR_ASSET = "whos_there/blaze_face_short_range.tflite"
    const val EMBEDDER_ASSET = "whos_there/face_recognition_sface_2021dec_int8dr.tflite"
    const val UNAVAILABLE = "Face recognition can’t run on this Portal"

    sealed interface Loaded {
      data class Ready(val models: FaceModels, val loadMs: Long) : Loaded
      data class Failed(val message: String) : Loaded
    }

    fun load(context: Context, threads: Int = 2): Loaded {
      val t0 = SystemClock.elapsedRealtime()
      var detector: Interpreter? = null
      return try {
        detector = Interpreter(map(context, DETECTOR_ASSET), Interpreter.Options().setNumThreads(1))
        val embedder = Interpreter(map(context, EMBEDDER_ASSET), Interpreter.Options().setNumThreads(threads))
        check(detector.getInputTensor(0).shape().contentEquals(intArrayOf(1, DET, DET, 3))) { "detector input" }
        check(embedder.getInputTensor(0).shape().contentEquals(intArrayOf(1, EMB, EMB, 3))) { "embedder input" }
        Loaded.Ready(FaceModels(detector, embedder), SystemClock.elapsedRealtime() - t0)
      } catch (t: Throwable) {
        // UnsatisfiedLinkError (32-bit), IOException (asset), IllegalArgumentException (model): all degrade.
        runCatching { detector?.close() }
        Log.w(TAG, "face models unavailable: ${t.javaClass.simpleName}")
        Loaded.Failed(UNAVAILABLE)
      }
    }

    private fun map(context: Context, asset: String): MappedByteBuffer =
        context.assets.openFd(asset).use { fd ->
          FileInputStream(fd.fileDescriptor).use { input ->
            input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
          }
        }
  }
}
