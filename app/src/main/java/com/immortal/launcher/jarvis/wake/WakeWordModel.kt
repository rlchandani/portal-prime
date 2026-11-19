/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * The bundled microWakeWord `hey_jarvis` streaming model on TensorFlow Lite. The model keeps its own audio
 * context in resource variables, so it takes only the newest 30 ms of features per call; [push] buffers
 * frontend frames and runs it once every [WakeFeatures.stride] frames. One thread, single-threaded interpreter
 * (a ~50 KB int8 model: well under a millisecond per call). Not thread-safe; [load] never throws.
 */
internal class WakeWordModel private constructor(
    private val mapped: MappedByteBuffer,
    private var interpreter: Interpreter,
    private val features: WakeFeatures,
) : Closeable {
  private val input = ByteBuffer.allocateDirect(features.stride * CHANNELS).order(ByteOrder.nativeOrder())
  private val frame = ByteArray(features.stride * CHANNELS)
  private val output = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
  private var buffered = 0

  /** Add one 10 ms frame; returns the model's uint8 probability (0..255) when this frame completed an input. */
  fun push(frontendFrame: IntArray): Int? {
    features.quantize(frontendFrame, buffered, frame)
    if (++buffered < features.stride) return null
    buffered = 0
    input.rewind()
    input.put(frame)
    input.rewind()
    output.rewind()
    interpreter.run(input, output)
    return output.get(0).toInt() and BYTE
  }

  /**
   * Forget all audio context. The streaming state lives in resource variables an interpreter can't clear, so
   * this builds a fresh interpreter over the same mapped model (a few milliseconds, once per capture start).
   */
  fun reset() {
    buffered = 0
    runCatching { interpreter.close() }
    interpreter = newInterpreter(mapped)
  }

  override fun close() {
    runCatching { interpreter.close() }
  }

  companion object {
    private const val TAG = "HearthpaneWake"
    const val ASSET = "wake/hey_jarvis.tflite"
    private const val CHANNELS = MicroFilterbank.CHANNELS
    private const val BYTE = 0xFF

    /** The model, or null (logged) when it can't run here: a missing asset or no TFLite native library. */
    fun load(context: Context): WakeWordModel? = try {
      val mapped = map(context)
      val interpreter = newInterpreter(mapped)
      val tensor = interpreter.getInputTensor(0)
      val shape = tensor.shape()
      val q = tensor.quantizationParams()
      check(shape.size == 3 && shape[2] == CHANNELS) { "unexpected input shape ${shape.contentToString()}" }
      WakeWordModel(mapped, interpreter, WakeFeatures(q.scale, q.zeroPoint, shape[1]))
    } catch (e: Throwable) {
      // UnsatisfiedLinkError (no native library) is an Error, not an Exception: still just "unavailable".
      Log.w(TAG, "wake model unavailable: ${e.javaClass.simpleName}")
      null
    }

    private fun newInterpreter(model: MappedByteBuffer): Interpreter =
        Interpreter(model, Interpreter.Options().setNumThreads(1))

    private fun map(context: Context): MappedByteBuffer = context.assets.openFd(ASSET).use { fd ->
      FileInputStream(fd.fileDescriptor).channel.use { channel ->
        channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
      }
    }
  }
}
