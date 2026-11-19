/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.abs

/**
 * The guided 4-look capture (front, left, right, chin up) as a pure reducer. Each look needs
 * [PER_LOOK] good frames that pass the quality gate and match the requested pose. A look that
 * can't get its pose within [LOOK_PATIENCE_MS] accepts any good frame, so set-up never stalls on
 * someone who can't turn their head; the look is then marked relaxed.
 */
internal enum class EnrolLook(val title: String, val hint: String, val kidTitle: String, val kidHint: String) {
  FRONT("Look at the screen", "Hold still about an arm’s length away", "Big smile!", "Look at the screen"),
  LEFT("Slowly turn left", "Just a little — like checking a mirror", "Peek left like a penguin", "Slowly…"),
  RIGHT("Now to the right", "Nearly there", "Now peek right!", "You’re a natural"),
  UP("Tip your chin up", "Last one", "Look up at the stars", "Last one!"),
}

/** What the gate measured on one frame (all values are numbers; the frame itself is gone). */
internal data class EnrolFrame(
    val faces: Int,
    val score: Float,
    /** Face box width as a fraction of the frame width. */
    val widthFraction: Float,
    val meanLuma: Float,
    val sharpness: Float,
    val yaw: Float,
    val pitch: Float,
    val embedding: Embedding?,
    val atMs: Long,
)

internal enum class EnrolHint(val text: String) {
  NONE(""),
  NO_FACE("Step into view"),
  TWO_FACES("Just one person at a time, please"),
  TOO_FAR("Come a little closer"),
  TOO_DARK("It’s a bit dark — turn on a light"),
  TOO_BRIGHT("Too much light behind you"),
  BLURRY("Hold still for a moment"),
  TURN("Follow the prompt"),
}

internal data class EnrolState(
    val look: Int = 0,
    val collected: List<Embedding> = emptyList(),
    val inLook: Int = 0,
    val lookStartMs: Long = 0L,
    val frontPitch: Float? = null,
    val leftYawSign: Int = 0,
    val relaxedLooks: Int = 0,
    val hint: EnrolHint = EnrolHint.NONE,
) {
  val done: Boolean get() = look >= EnrolLook.entries.size
  val currentLook: EnrolLook? get() = EnrolLook.entries.getOrNull(look)
}

internal object WhosThereEnrolPolicy {
  const val PER_LOOK = 2
  const val LOOK_PATIENCE_MS = 8_000L
  const val MIN_SCORE = 0.75f
  const val MIN_WIDTH = 0.12f
  const val MIN_LUMA = 45f
  const val MAX_LUMA = 215f
  const val MIN_SHARPNESS = 25f
  const val FRONT_YAW = 0.15f
  const val TURN_YAW = 0.12f
  const val UP_PITCH_DROP = 0.06f
  /** Two consecutive kept frames must differ at least this much, so a look isn't one frame twice. */
  const val MAX_DUPLICATE_COSINE = 0.995f

  fun start(nowMs: Long): EnrolState = EnrolState(lookStartMs = nowMs)

  fun quality(f: EnrolFrame): EnrolHint = when {
    f.faces == 0 || f.embedding == null -> EnrolHint.NO_FACE
    f.faces > 1 -> EnrolHint.TWO_FACES
    f.score < MIN_SCORE -> EnrolHint.NO_FACE
    f.widthFraction < MIN_WIDTH -> EnrolHint.TOO_FAR
    f.meanLuma < MIN_LUMA -> EnrolHint.TOO_DARK
    f.meanLuma > MAX_LUMA -> EnrolHint.TOO_BRIGHT
    f.sharpness < MIN_SHARPNESS -> EnrolHint.BLURRY
    else -> EnrolHint.NONE
  }

  /** Does the pose satisfy the current look? Left/right are judged by sign, so mirroring can't trip it. */
  fun poseOk(s: EnrolState, f: EnrolFrame): Boolean = when (s.currentLook) {
    EnrolLook.FRONT -> abs(f.yaw) < FRONT_YAW
    EnrolLook.LEFT -> abs(f.yaw) >= TURN_YAW
    EnrolLook.RIGHT -> abs(f.yaw) >= TURN_YAW && (s.leftYawSign == 0 || sign(f.yaw) == -s.leftYawSign)
    EnrolLook.UP -> s.frontPitch == null || f.pitch <= s.frontPitch - UP_PITCH_DROP
    null -> false
  }

  fun onFrame(s: EnrolState, f: EnrolFrame): EnrolState {
    if (s.done) return s
    val quality = quality(f)
    if (quality != EnrolHint.NONE) return s.copy(hint = quality)
    val patienceOver = f.atMs - s.lookStartMs >= LOOK_PATIENCE_MS
    val pose = poseOk(s, f)
    if (!pose && !patienceOver) return s.copy(hint = EnrolHint.TURN)
    val embedding = f.embedding ?: return s.copy(hint = EnrolHint.NO_FACE)
    val last = s.collected.lastOrNull()
    if (s.inLook > 0 && last != null && last.cosine(embedding) > MAX_DUPLICATE_COSINE) return s.copy(hint = EnrolHint.NONE)
    val collected = s.collected + embedding
    val inLook = s.inLook + 1
    val look = s.currentLook
    val frontPitch = if (look == EnrolLook.FRONT) average(s.frontPitch, f.pitch, inLook) else s.frontPitch
    val leftSign = if (look == EnrolLook.LEFT && s.leftYawSign == 0 && pose) sign(f.yaw) else s.leftYawSign
    if (inLook < PER_LOOK) {
      return s.copy(collected = collected, inLook = inLook, frontPitch = frontPitch, leftYawSign = leftSign, hint = EnrolHint.NONE)
    }
    return s.copy(look = s.look + 1, collected = collected, inLook = 0, lookStartMs = f.atMs, frontPitch = frontPitch,
        leftYawSign = leftSign, relaxedLooks = s.relaxedLooks + if (pose) 0 else 1, hint = EnrolHint.NONE)
  }

  /** Progress 0…1 for the ring (64 ticks in the mock). */
  fun progress(s: EnrolState): Float =
      if (s.done) 1f else (s.look * PER_LOOK + s.inLook).toFloat() / (EnrolLook.entries.size * PER_LOOK)

  private fun average(prev: Float?, next: Float, count: Int): Float =
      if (prev == null || count <= 1) next else prev + (next - prev) / count

  private fun sign(v: Float): Int = if (v < 0f) -1 else 1
}
