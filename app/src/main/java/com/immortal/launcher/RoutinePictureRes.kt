/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.graphics.Color

/** Bundled VectorDrawables for [RoutinePictures] ids and kid avatars, plus the takeover palette. */
internal object RoutinePictureRes {
  private val pics = mapOf(
      "shirt" to R.drawable.routine_pic_shirt, "bowl" to R.drawable.routine_pic_bowl,
      "toothbrush" to R.drawable.routine_pic_toothbrush, "shoes" to R.drawable.routine_pic_shoes,
      "backpack" to R.drawable.routine_pic_backpack, "coat" to R.drawable.routine_pic_coat,
      "bed" to R.drawable.routine_pic_bed, "bottle" to R.drawable.routine_pic_bottle,
      "socks" to R.drawable.routine_pic_socks, "lunch" to R.drawable.routine_pic_lunch,
      "hair" to R.drawable.routine_pic_hair, "wash" to R.drawable.routine_pic_wash,
      "toilet" to R.drawable.routine_pic_toilet, "hat" to R.drawable.routine_pic_hat,
      "apple" to R.drawable.routine_pic_apple, "bath" to R.drawable.routine_pic_bath,
      "pyjamas" to R.drawable.routine_pic_pyjamas, "book" to R.drawable.routine_pic_book,
      "teddy" to R.drawable.routine_pic_teddy, "moon" to R.drawable.routine_pic_moon,
      "toys" to R.drawable.routine_pic_toys, "cup" to R.drawable.routine_pic_cup,
      "car" to R.drawable.routine_pic_car,
  )

  fun pic(id: String): Int = pics[id] ?: R.drawable.routine_pic_shirt
  fun avatar(id: String?): Int = when (id) {
    "fox" -> R.drawable.routine_avatar_fox
    "owl" -> R.drawable.routine_avatar_owl
    else -> R.drawable.routine_avatar_everyone
  }
}

/** The prototype's dark kit palette (the takeover is its own full-screen surface). */
internal object RoutineColors {
  val Bg = Color(0xFF14161B)
  val Card = Color(0xFF2B2B2B)
  val Raised = Color(0xFF363636)
  val Selected = Color(0xFF414141)
  val Border = Color(0xFF484848)
  val Text = Color.White
  val Muted = Color(0xFFB8B8B8)
  val Green = Color(0xFF66C49E)
  val OnGreen = Color(0xFF0E2A1C)
  val Sun = Color(0xFFF4DB93)
  val Sheet = Color(0xFF1C1E23)
  val DoneCard = Color(0xFF364A41) // 20% green over the card colour
  val LateTrack = Color(0xFFBDAC79) // 70% sun over raised
}
