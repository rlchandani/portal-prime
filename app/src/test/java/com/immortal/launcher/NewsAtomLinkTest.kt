/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Literal generated RSS/Atom entries call the actual parser without HTTP or browser work. */
class NewsAtomLinkTest {
  @Test fun selfLinkBeforeAlternateKeepsTheArticleTarget() {
    val result = NewsFeed.parse("""
      <feed xmlns="http://www.w3.org/2005/Atom"><entry>
        <title>Article one</title>
        <link rel="self" href="https://example.test/entries/one.atom"/>
        <link rel="alternate" href="https://example.test/articles/one"/>
      </entry></feed>
    """.trimIndent())
    assertEquals(listOf(NewsFeed.Headline("Article one", "https://example.test/articles/one")), result)
  }

  @Test fun enclosureBeforeAlternateKeepsTheArticleTarget() {
    val result = NewsFeed.parse("""
      <feed><entry>
        <title>Article with audio</title>
        <link rel="enclosure" href="https://example.test/audio/episode.mp3"/>
        <link rel="alternate" href="https://example.test/articles/audio"/>
      </entry></feed>
    """.trimIndent())
    assertEquals(listOf(NewsFeed.Headline("Article with audio", "https://example.test/articles/audio")), result)
  }

  @Test fun firstAlternateRemainsTheTargetWhenSeveralArePresent() {
    val result = NewsFeed.parse("""
      <feed><entry>
        <title>Two article representations</title>
        <link rel="alternate" href="https://example.test/articles/first"/>
        <link rel="alternate" href="https://example.test/articles/second"/>
        <link rel="self" href="https://example.test/entries/two.atom"/>
      </entry></feed>
    """.trimIndent())
    assertEquals(listOf(NewsFeed.Headline("Two article representations",
        "https://example.test/articles/first")), result)
  }

  @Test fun omittedRelationIsAnArticleLinkAfterSelf() {
    val result = NewsFeed.parse("""
      <feed><entry>
        <title>Default article relation</title>
        <link rel="self" href="https://example.test/entries/default.atom"/>
        <link href="https://example.test/articles/default"/>
      </entry></feed>
    """.trimIndent())
    assertEquals(listOf(NewsFeed.Headline("Default article relation",
        "https://example.test/articles/default")), result)
  }

  @Test fun nonArticleRelationsRetainTheHeadlineWithoutAFalseTarget() {
    val result = NewsFeed.parse("""
      <feed><entry>
        <title>Headline without an article link</title>
        <link rel="self" href="https://example.test/entries/no-article.atom"/>
        <link rel="enclosure" href="https://example.test/audio/no-article.mp3"/>
      </entry></feed>
    """.trimIndent())
    assertEquals(listOf(NewsFeed.Headline("Headline without an article link", null)), result)
  }

  @Test fun singleQuotedXmlAttributesKeepTheArticleTarget() {
    val result = NewsFeed.parse("""
      <feed><entry>
        <title>Single quote attributes</title>
        <link rel='self' href='https://example.test/entries/single.atom'/>
        <link href='https://example.test/articles/single?x=1&amp;y=2' rel='alternate'/>
      </entry></feed>
    """.trimIndent())
    assertEquals(listOf(NewsFeed.Headline("Single quote attributes",
        "https://example.test/articles/single?x=1&y=2")), result)
  }

  @Test fun blankEligibleHrefDoesNotHideTheLaterArticle() {
    val result = NewsFeed.parse("""
      <feed><entry>
        <title>Article after a blank target</title>
        <link rel="alternate" href=""/>
        <link href="   "/>
        <link rel="alternate" href="https://example.test/articles/nonblank"/>
      </entry></feed>
    """.trimIndent())
    assertEquals(listOf(NewsFeed.Headline("Article after a blank target",
        "https://example.test/articles/nonblank")), result)
  }

  @Test fun attributeOrderDoesNotMakeSelfAnArticleTarget() {
    val result = NewsFeed.parse("""
      <feed><entry>
        <title>Reordered attributes</title>
        <link href="https://example.test/entries/order.atom" type="application/atom+xml" rel="self"/>
        <link href="https://example.test/articles/order" type="text/html" rel="alternate"/>
      </entry></feed>
    """.trimIndent())
    assertEquals(listOf(NewsFeed.Headline("Reordered attributes",
        "https://example.test/articles/order")), result)
  }

  @Test fun rssTextLinkKeepsPriorityAndExistingEntityDecoding() {
    val result = NewsFeed.parse("""
      <rss><channel><item>
        <title><![CDATA[First & foremost]]></title>
        <link>https://example.test/rss/article?x=1&amp;y=2</link>
        <link rel="alternate" href="https://example.test/other-representation"/>
      </item></channel></rss>
    """.trimIndent())
    assertEquals(listOf(NewsFeed.Headline("First & foremost",
        "https://example.test/rss/article?x=1&y=2")), result)
  }

  @Test fun separateEntriesKeepTheirOwnTitleOrderAndArticleChoice() {
    val result = NewsFeed.parse("""
      <feed>
        <entry>
          <title>First &amp; current</title>
          <link rel="self" href="https://example.test/entries/first.atom"/>
          <link rel="alternate" href="https://example.test/articles/first"/>
        </entry>
        <entry>
          <title>Second without an article</title>
          <link rel="enclosure" href="https://example.test/media/second.mp4"/>
        </entry>
        <entry>
          <title>Third article</title>
          <link href="https://example.test/articles/third"/>
        </entry>
      </feed>
    """.trimIndent())
    assertEquals(listOf(
        NewsFeed.Headline("First & current", "https://example.test/articles/first"),
        NewsFeed.Headline("Second without an article", null),
        NewsFeed.Headline("Third article", "https://example.test/articles/third")), result)
  }
}
