/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * The drawn header heading ([CcHeadingText]) keeps the old Column's node (bounds, text), and a switch's
 * accessibility changes wait for [CcUi.spokenScreen].
 */
class ControlCenterHeadingTextTest {
  private lateinit var locale: Locale
  private lateinit var zone: TimeZone
  private val now = 1_795_000_000_000L

  @Before fun save() {
    locale = Locale.getDefault(); zone = TimeZone.getDefault()
    Locale.setDefault(Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
  }
  @After fun restore() { Locale.setDefault(locale); TimeZone.setDefault(zone) }

  private fun inputs(minute: Long = now / 60_000L) = CcHeadingInputs(minute, "Model Y", null, kids = false)
  private fun layouts() = CcHeadingLayouts(TextMeasurer(mock(FontFamily.Resolver::class.java), Density(1f), LayoutDirection.Ltr, 0))

  @Test fun theNodeIsTheWiderLineByBothHeights() {
    val c = Constraints(maxWidth = 900, maxHeight = 122)
    assertEquals(IntSize(240, 82), ccHeadingSize(c, IntSize(120, 56), IntSize(240, 26)))
    assertEquals(IntSize(300, 82), ccHeadingSize(c, IntSize(300, 56), IntSize(240, 26)))
  }

  /** As a Column in a weighted row (minimum = maximum width): the node fills the width. */
  @Test fun theNodeKeepsItsMinimumAndMaximum() {
    assertEquals(IntSize(700, 82), ccHeadingSize(Constraints(minWidth = 700, maxWidth = 700, maxHeight = 122), IntSize(120, 56), IntSize(240, 26)))
    assertEquals(IntSize(500, 60), ccHeadingSize(Constraints(maxWidth = 500, maxHeight = 60), IntSize(900, 56), IntSize(240, 26)))
  }

  @Test fun eachLineGetsTheFullWidthAndTheHeightLeft() {
    val c = Constraints(minWidth = 700, maxWidth = 700, maxHeight = 122)
    assertEquals(Constraints(maxWidth = 700, maxHeight = 122), ccHeadingLineConstraints(c, 0))
    assertEquals(Constraints(maxWidth = 700, maxHeight = 66), ccHeadingLineConstraints(c, 56))
    assertEquals(Constraints(maxWidth = 700, maxHeight = 0), ccHeadingLineConstraints(c, 200))
    assertEquals(Constraints.Infinity, ccHeadingLineConstraints(Constraints(maxWidth = 700), 56).maxHeight)
  }

  @Test fun theNodeSaysTitleThenSubtitle() {
    assertEquals("Alerts. Saturday · 7:42 pm", ccHeadingSpeech(CcHeading("Alerts", "Saturday · 7:42 pm")))
  }

  @Test fun eachScreensHeadingIsTheSameAsBefore() {
    val l = layouts()
    l.use(inputs(), TextStyle(fontSize = 46.sp), TextStyle(fontSize = 21.sp), TextOverflow.Clip, TextOverflow.Ellipsis)
    for (screen in CcScreen.entries) assertEquals(ccHeading(inputs(), screen, null), l.heading(screen, null))
  }

  /** Headings are kept per screen and floor until the inputs (minute, car, plan, kids) change. */
  @Test fun headingsAreKeptUntilTheInputsChange() {
    val l = layouts()
    l.use(inputs(), TextStyle.Default, TextStyle.Default, TextOverflow.Clip, TextOverflow.Ellipsis)
    val alerts = l.heading(CcScreen.ALERTS, null)
    assertSame(alerts, l.heading(CcScreen.ALERTS, null))
    l.use(inputs(), TextStyle.Default, TextStyle.Default, TextOverflow.Clip, TextOverflow.Ellipsis)
    assertSame(alerts, l.heading(CcScreen.ALERTS, null))
    l.use(inputs(minute = now / 60_000L + 1), TextStyle.Default, TextStyle.Default, TextOverflow.Clip, TextOverflow.Ellipsis)
    assertNotSame(alerts, l.heading(CcScreen.ALERTS, null))
  }

  @Test fun noHeadingBeforeTheInputsArrive() {
    assertEquals(CcHeading("", ""), layouts().heading(CcScreen.MAP, null))
  }

  /** A switch moves the shown screen at once; what accessibility services are told follows only when caught up. */
  @Test fun theSpokenScreenFollowsOnlyWhenCaughtUp() {
    val ui = CcUi(mock(Context::class.java), mutableStateOf(CcNav()), { CcUiState() })
    assertEquals(CcScreen.MAP, ui.spokenScreen)
    ui.go(CcScreen.ALERTS)
    assertEquals(CcScreen.ALERTS, ui.screen)
    assertEquals(CcScreen.MAP, ui.spokenScreen)
    ui.spoke(CcScreen.ALERTS)
    assertEquals(CcScreen.ALERTS, ui.spokenScreen)
  }

  @Test fun theSpokenScreenStartsAtTheOpeningScreen() {
    val ui = CcUi(mock(Context::class.java), mutableStateOf(CcNav(screen = CcScreen.CAMERAS)), { CcUiState() })
    assertEquals(CcScreen.CAMERAS, ui.spokenScreen)
  }
}
