/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RSS 2.0 / Atom headline parsing (pure, no device/network needed). */
class NewsFeedTest {

  private val rss =
      """
      <?xml version="1.0"?>
      <rss version="2.0"><channel>
        <title>Example News</title>
        <item>
          <title><![CDATA[First & foremost headline]]></title>
          <link>https://example.com/1</link>
        </item>
        <item>
          <title>Second headline &amp; more</title>
          <link>https://example.com/2</link>
        </item>
      </channel></rss>
      """.trimIndent()

  private val atom =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <feed xmlns="http://www.w3.org/2005/Atom">
        <title>Atom Example</title>
        <entry>
          <title>Atom headline one</title>
          <link href="https://example.com/a1" rel="alternate"/>
        </entry>
        <entry>
          <title>Atom headline two</title>
          <link href="https://example.com/a2"/>
        </entry>
      </feed>
      """.trimIndent()

  @Test
  fun `parses rss items with cdata and entities decoded`() {
    val out = NewsFeed.parse(rss)
    assertEquals(2, out.size)
    assertEquals("First & foremost headline", out[0].title)
    assertEquals("https://example.com/1", out[0].link)
    assertEquals("Second headline & more", out[1].title)
  }

  @Test
  fun `parses atom entries with href link`() {
    val out = NewsFeed.parse(atom)
    assertEquals(listOf("Atom headline one", "Atom headline two"), out.map { it.title })
    assertEquals("https://example.com/a1", out[0].link)
    assertEquals("https://example.com/a2", out[1].link)
  }

  @Test
  fun `caps at MAX_HEADLINES`() {
    val items = (1..20).joinToString("") { "<item><title>H$it</title><link>https://x/$it</link></item>" }
    val out = NewsFeed.parse("<rss><channel>$items</channel></rss>")
    assertEquals(NewsFeed.MAX_HEADLINES, out.size)
  }

  @Test
  fun `an item without a title is skipped`() {
    val body = "<rss><channel><item><link>https://x/1</link></item><item><title>Real</title></item></channel></rss>"
    val out = NewsFeed.parse(body)
    assertEquals(1, out.size)
    assertEquals("Real", out[0].title)
    assertNull(out[0].link) // no link tag on the real item
  }

  @Test
  fun `garbage input yields an empty list, never throws`() {
    assertTrue(NewsFeed.parse("not xml at all").isEmpty())
    assertTrue(NewsFeed.parse("").isEmpty())
    assertTrue(NewsFeed.parse("<rss><channel></channel></rss>").isEmpty())
  }

  @Test
  fun `clean strips cdata, decodes entities, collapses whitespace`() {
    assertEquals("a & b", NewsFeed.clean("  <![CDATA[a & b]]>  "))
    assertEquals("x's \"y\"", NewsFeed.clean("x&#39;s &quot;y&quot;"))
    assertEquals("one two", NewsFeed.clean("one\n\t  two"))
  }
}
