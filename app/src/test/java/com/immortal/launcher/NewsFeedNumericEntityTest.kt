/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class NewsFeedNumericEntityTest {
  @Test fun decimalPunctuationInRssTitle() {
    assertEquals("Today’s news — more", NewsFeed.parse(
        "<rss><item><title>Today&#8217;s news &#8212; more</title></item></rss>").single().title)
  }

  @Test fun hexadecimalPunctuationInAtomTitle() {
    assertEquals("“News”", NewsFeed.parse(
        "<feed><entry><title>&#x201c;News&#X201D;</title></entry></feed>").single().title)
  }

  @Test fun supplementaryCharactersUseCompleteCodePoints() {
    assertEquals("🚀 😀", NewsFeed.clean("&#128640; &#x1F600;"))
  }

  @Test fun nonLatinTextAndLeadingZeroReferences() {
    assertEquals("日本 é", NewsFeed.clean("&#26085;&#x672C; &#000233;"))
  }

  @Test fun numericWhitespaceKeepsExistingCollapse() {
    assertEquals("one two three", NewsFeed.clean("one&#10;&#x09;two&#13; three"))
  }

  @Test fun numericReferencesAlsoDecodeInArticleLinks() {
    assertEquals("https://example.com/?a=1&b=2", NewsFeed.parse(
        "<rss><item><title>News</title><link>https://example.com/?a=1&#38;b=2</link></item></rss>")
        .single().link)
  }

  @Test fun malformedAndInvalidCodePointsRemainReadable() {
    val raw = "&#; &#x; &#-1; &#xZZ; &#0; &#55296; &#xDFFF; &#1114112; &#99999999999999999999;"
    assertEquals(raw, NewsFeed.clean(raw))
  }

  @Test fun validReferencesSurviveAdjacentInvalidReferences() {
    assertEquals("A&#xD800;B", NewsFeed.clean("&#65;&#xD800;&#66;"))
  }

  @Test fun existingNamedEntitiesAndApostrophesRemain() {
    assertEquals("& < > \" ' ' '", NewsFeed.clean(
        "&amp; &lt; &gt; &quot; &#39; &apos; &#x27;"))
  }

  @Test fun cdataAndPlainUnicodeRemain() {
    assertEquals("Already café —", NewsFeed.clean(" <![CDATA[Already café —]]> "))
  }

  @Test fun escapedNumericReferenceIsNotDecodedRecursively() {
    assertEquals("Literal &#8217;", NewsFeed.clean("Literal &amp;#8217;"))
  }

  @Test fun parsedHeadlinesRetainOrderAndCap() {
    val items = (1..12).joinToString("") { "<item><title>H$it&#8217;s</title></item>" }
    assertEquals((1..8).map { "H$it’s" }, NewsFeed.parse("<rss>$items</rss>").map { it.title })
  }
}
