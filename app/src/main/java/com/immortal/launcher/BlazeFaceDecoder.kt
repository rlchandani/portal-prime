/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** One detected face in model-input coordinates (0…1). Keypoints: eye, eye, nose, mouth, ear, ear. */
internal data class FaceBox(
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
    val score: Float,
    val keypoints: List<Pt>,
) {
  val width: Float get() = xMax - xMin
  val height: Float get() = yMax - yMin
  val area: Float get() = max(0f, width) * max(0f, height)

  fun iou(o: FaceBox): Float {
    val ix = max(0f, min(xMax, o.xMax) - max(xMin, o.xMin))
    val iy = max(0f, min(yMax, o.yMax) - max(yMin, o.yMin))
    val inter = ix * iy
    val union = area + o.area - inter
    return if (union <= 0f) 0f else inter / union
  }
}

/**
 * Decodes MediaPipe BlazeFace short-range output (896 anchors × 16 regressors + 896 scores),
 * matching MediaPipe's `face_detection_short_range` graph: SSD anchors (strides 8,16,16,16,
 * 0.5 offset, fixed size), sigmoid scores clipped at ±100, weighted non-max suppression.
 */
internal object BlazeFaceDecoder {
  const val INPUT = 128
  const val ANCHORS = 896
  const val VALUES = 16
  const val SCORE_THRESHOLD = 0.5f
  const val NMS_IOU = 0.3f
  private const val KEYPOINTS = 6

  /** Anchor centres (x, y) in 0…1, in the model's output order. */
  val anchors: List<Pt> by lazy { buildAnchors() }

  private fun buildAnchors(): List<Pt> {
    val out = ArrayList<Pt>(ANCHORS)
    // MediaPipe merges consecutive layers with the same stride: 8 → 2 anchors, 16 ×3 → 6 anchors.
    for ((stride, perCell) in listOf(8 to 2, 16 to 6)) {
      val cells = INPUT / stride
      for (y in 0 until cells) for (x in 0 until cells) {
        val cx = (x + 0.5f) / cells
        val cy = (y + 0.5f) / cells
        repeat(perCell) { out += Pt(cx, cy) }
      }
    }
    check(out.size == ANCHORS)
    return out
  }

  fun sigmoid(raw: Float): Float = 1f / (1f + exp(-raw.coerceIn(-100f, 100f)))

  /** [boxes] is `896 × 16` flattened, [scores] is `896` raw logits. */
  fun decode(boxes: FloatArray, scores: FloatArray, threshold: Float = SCORE_THRESHOLD): List<FaceBox> {
    require(boxes.size >= ANCHORS * VALUES && scores.size >= ANCHORS) { "unexpected BlazeFace output size" }
    val found = ArrayList<FaceBox>()
    val scale = INPUT.toFloat()
    for (i in 0 until ANCHORS) {
      val score = sigmoid(scores[i])
      if (score < threshold) continue
      val a = anchors[i]
      val o = i * VALUES
      val cx = boxes[o] / scale + a.x
      val cy = boxes[o + 1] / scale + a.y
      val w = boxes[o + 2] / scale
      val h = boxes[o + 3] / scale
      if (w <= 0f || h <= 0f) continue
      val kps = (0 until KEYPOINTS).map { k -> Pt(boxes[o + 4 + 2 * k] / scale + a.x, boxes[o + 5 + 2 * k] / scale + a.y) }
      found += FaceBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, score, kps)
    }
    return weightedNms(found)
  }

  /** MediaPipe's WEIGHTED suppression: overlapping boxes are averaged by score, not dropped. */
  fun weightedNms(boxes: List<FaceBox>, iou: Float = NMS_IOU): List<FaceBox> {
    var remaining = boxes.sortedByDescending { it.score }
    val out = ArrayList<FaceBox>()
    while (remaining.isNotEmpty()) {
      val top = remaining.first()
      val (cluster, rest) = remaining.partition { it === top || it.iou(top) > iou }
      out += average(cluster, top.score)
      remaining = rest
    }
    return out
  }

  private fun average(cluster: List<FaceBox>, score: Float): FaceBox {
    val total = cluster.sumOf { it.score.toDouble() }.toFloat()
    fun avg(f: (FaceBox) -> Float) = cluster.sumOf { (f(it) * it.score).toDouble() }.toFloat() / total
    val kps = (0 until KEYPOINTS).map { k -> Pt(avg { it.keypoints[k].x }, avg { it.keypoints[k].y }) }
    return FaceBox(avg { it.xMin }, avg { it.yMin }, avg { it.xMax }, avg { it.yMax }, score, kps)
  }
}
