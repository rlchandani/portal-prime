/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Screen-reader names for the shared settings collection row ([ApprovedEditorsCollectionRow]). Every
 * list row (stocks, feeds, countdowns, HA entities, cameras…) draws the same "Remove" button, so on
 * one screen a reader would hear "Remove" once per row with nothing to tell them apart. The spoken
 * name says which row it acts on — "Remove Apple", "Remove Kitchen camera" — while the visible word
 * stays "Remove" so sighted use and voice control are unchanged. Pure and unit tested; the composable
 * only renders what this returns (the same style as [HomeRefinements.widgetEditSpoken]).
 */
internal object ApprovedEditorsCopy {
  /** A collection row's action named for its row ("Remove Apple"); the visible verb stays the start. */
  fun rowActionSpoken(action: String, title: String): String = "$action $title"
}
