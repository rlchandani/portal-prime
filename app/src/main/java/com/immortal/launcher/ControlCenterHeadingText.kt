/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.TextUnit

/** One line of the heading: Material `Text` parameters (one line, soft wrap on). */
@Immutable
internal data class CcHeadingLine(
    val color: Color, val fontSize: TextUnit, val fontFamily: FontFamily? = null,
    val lineHeight: TextUnit = TextUnit.Unspecified, val overflow: TextOverflow = TextOverflow.Clip,
) {
  /** The style Material `Text` lays this line out with over [base] ([LocalTextStyle]). */
  fun style(base: TextStyle): TextStyle = base.merge(TextStyle(color = color, fontSize = fontSize, fontFamily = fontFamily, lineHeight = lineHeight))
}

/**
 * The header's title and subtitle (a `Column` of two one-line `Text`s), drawn from prepared text
 * layouts, and one heading node saying "title. subtitle".
 *
 * A screen switch used to recompose both texts (new strings → paragraph layout, 1.25-3.25 ms of
 * the switch frame) and change the heading's spoken text in the switch frame, which made Compose
 * rebuild and re-sort the accessibility tree there. Here the drawn heading follows [CcUi.screen]
 * while drawing (a redraw of the header texture only), from layouts prepared on quiet frames for
 * every screen ([CcHeadingLayouts.prepare]); the node's text and its bounds follow
 * [CcUi.spokenScreen], which catches up a few frames later together with the rest of the switch's
 * accessibility changes. Once settled the node is exactly the old Column's (same bounds: the wider
 * line, both heights). Labels are drawn as `BasicText` draws them ([ccPaintText]).
 */
@Composable
internal fun CcHeadingText(inputs: CcHeadingInputs, ui: CcUi, title: CcHeadingLine, subtitle: CcHeadingLine, modifier: Modifier = Modifier) {
  val base = LocalTextStyle.current
  val measurer = rememberTextMeasurer(cacheSize = 0)
  val layouts = remember(measurer) { CcHeadingLayouts(measurer) }
  layouts.use(inputs, title.style(base), subtitle.style(base), title.overflow, subtitle.overflow)
  val spoken = rememberCcSpokenHeading(inputs, ui)
  Layout(modifier.drawBehind { layouts.draw(this, layouts.heading(ui.screen, ui.floorId), title.color, subtitle.color) }
      .ccSpeak(ccHeadingSpeech(spoken), heading = true)) { _, constraints ->
    val size = layouts.measure(spoken, constraints)
    layout(size.width, size.height) {}
  }
  // Every other screen's heading laid out ahead, one per frame (each is well under a millisecond).
  LaunchedEffect(layouts, inputs, ui.floorId) {
    for (screen in CcScreen.entries) {
      withFrameNanos { }
      layouts.prepare(layouts.heading(screen, ui.floorId))
    }
  }
}

/** The heading the screen reader is told about: [CcUi.spokenScreen]'s (read in the caller's small scope). */
@Composable
private fun rememberCcSpokenHeading(inputs: CcHeadingInputs, ui: CcUi): CcHeading {
  val screen = ui.spokenScreen
  val floorId = ui.floorId
  return remember(inputs, screen, floorId) { ccHeading(inputs, screen, floorId) }
}

/**
 * A line's constraints as the old `Column` measured it: no minimum, the full width, and the
 * height left below the lines above ([above] px).
 */
internal fun ccHeadingLineConstraints(c: Constraints, above: Int): Constraints =
    Constraints(maxWidth = c.maxWidth, maxHeight = if (c.hasBoundedHeight) (c.maxHeight - above).coerceAtLeast(0) else Constraints.Infinity)

/** The old `Column`'s size for its two lines: the wider line by both heights, within [c]. */
internal fun ccHeadingSize(c: Constraints, title: IntSize, subtitle: IntSize): IntSize =
    IntSize(c.constrainWidth(maxOf(title.width, subtitle.width)), c.constrainHeight(title.height + subtitle.height))

/** What the heading node says. */
internal fun ccHeadingSpeech(h: CcHeading): String = "${h.title}. ${h.subtitle}"

/**
 * The heading per screen and its two text layouts, for the current [CcHeadingInputs], styles and
 * constraints (any change clears them). Written by composition / measure / the prepare effect and
 * read while drawing, all on the main thread.
 */
internal class CcHeadingLayouts(private val measurer: TextMeasurer) {
  private var inputs: CcHeadingInputs? = null
  private var titleStyle: TextStyle? = null
  private var subtitleStyle: TextStyle? = null
  private var titleOverflow = TextOverflow.Clip
  private var subtitleOverflow = TextOverflow.Clip
  private var constraints: Constraints? = null
  private val headings = HashMap<Pair<CcScreen, String?>, CcHeading>()
  private val titles = HashMap<String, TextLayoutResult>()
  private val subtitles = HashMap<String, TextLayoutResult>()

  fun use(inputs: CcHeadingInputs, titleStyle: TextStyle, subtitleStyle: TextStyle, titleOverflow: TextOverflow, subtitleOverflow: TextOverflow) {
    if (inputs != this.inputs) { this.inputs = inputs; headings.clear() }
    if (titleStyle != this.titleStyle || subtitleStyle != this.subtitleStyle ||
        titleOverflow != this.titleOverflow || subtitleOverflow != this.subtitleOverflow) {
      this.titleStyle = titleStyle; this.subtitleStyle = subtitleStyle
      this.titleOverflow = titleOverflow; this.subtitleOverflow = subtitleOverflow
      titles.clear(); subtitles.clear()
    }
  }

  fun heading(screen: CcScreen, floorId: String?): CcHeading {
    val inputs = inputs ?: return CcHeading("", "")
    return headings.getOrPut(screen to floorId) { ccHeading(inputs, screen, floorId) }
  }

  /** Lays out [h] under [c] and returns the old Column's size: the wider line by both heights, within [c]. */
  fun measure(h: CcHeading, c: Constraints): IntSize {
    if (c != constraints) { constraints = c; titles.clear(); subtitles.clear() }
    val (t, s) = layoutsOf(h, c)
    return ccHeadingSize(c, t.size, s.size)
  }

  /** Lays out [h] ahead of its first draw (no-op before the first measure). */
  fun prepare(h: CcHeading) { constraints?.let { layoutsOf(h, it) } }

  /** Draws [h] as the Column would: title at the top left, subtitle under it. */
  fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, h: CcHeading, titleColor: Color, subtitleColor: Color) {
    val c = constraints ?: return
    val (t, s) = layoutsOf(h, c)
    with(scope) {
      ccPaintText(t, titleColor, titleOverflow)
      translate(top = t.size.height.toFloat()) { ccPaintText(s, subtitleColor, subtitleOverflow) }
    }
  }

  private fun layoutsOf(h: CcHeading, c: Constraints): Pair<TextLayoutResult, TextLayoutResult> {
    val t = titles.getOrPut(h.title) { lay(h.title, titleStyle, titleOverflow, ccHeadingLineConstraints(c, 0)) }
    val s = subtitles.getOrPut(h.subtitle) { lay(h.subtitle, subtitleStyle, subtitleOverflow, ccHeadingLineConstraints(c, t.size.height)) }
    return t to s
  }

  private fun lay(text: String, style: TextStyle?, overflow: TextOverflow, c: Constraints): TextLayoutResult =
      measurer.measure(text, style ?: TextStyle.Default, overflow, softWrap = true, maxLines = 1, constraints = c)
}
