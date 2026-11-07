/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Picture routines: the bundled picture set (23 generated drawings, the same on the Portal and in
 * Toolkit, so a picture never turns into a blank emoji box on Android 10). Ids are part of the
 * phone↔Portal contract and stored in routines: never rename or reorder them.
 */
internal object RoutinePictures {
  data class Picture(val id: String, val label: String, val group: String)

  val all: List<Picture> = listOf(
      Picture("shirt", "Get dressed", "Morning"), Picture("bowl", "Breakfast", "Morning"),
      Picture("toothbrush", "Brush teeth", "Morning"), Picture("shoes", "Shoes on", "Morning"),
      Picture("backpack", "Backpack", "Morning"), Picture("coat", "Coat on", "Morning"),
      Picture("bed", "Make bed", "Morning"), Picture("bottle", "Water bottle", "Morning"),
      Picture("socks", "Socks on", "Morning"), Picture("lunch", "Lunchbox", "Morning"),
      Picture("hair", "Brush hair", "Morning"), Picture("wash", "Wash hands", "Any time"),
      Picture("toilet", "Toilet", "Any time"), Picture("hat", "Hat on", "Morning"),
      Picture("apple", "Snack", "Morning"), Picture("bath", "Bath", "Evening"),
      Picture("pyjamas", "Pyjamas", "Evening"), Picture("book", "Story", "Evening"),
      Picture("teddy", "Teddy", "Evening"), Picture("moon", "Lights out", "Evening"),
      Picture("toys", "Tidy toys", "Evening"), Picture("cup", "Drink milk", "Any time"),
      Picture("car", "Into the car", "Morning"),
  )
  val ids: List<String> = all.map { it.id }
  val avatars: List<String> = listOf("fox", "owl")
  private val byId = all.associateBy { it.id }

  fun isValid(id: String): Boolean = id in byId
  fun defaultLabel(id: String): String = byId[id]?.label ?: ""
  fun group(id: String): String = byId[id]?.group ?: ""
}
