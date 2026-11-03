/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

/**
 * Keyless news headlines from public RSS 2.0 / Atom feeds — the "paste a public URL" model, like
 * [CalendarFeed]'s ICS links. No API key, no NewsAPI (which bans production use).
 *
 * Deliberately hand-rolled string parsing rather than `XmlPullParser`, matching [CalendarFeed]'s
 * approach: [parse] is pure and unit-testable on the plain JVM (no Android XML runtime / Robolectric).
 * It's tolerant by design — it locates `<item>` (RSS) or `<entry>` (Atom) blocks, pulls the title +
 * link, strips CDATA, decodes the common HTML entities, and skips anything it can't make sense of
 * rather than throwing. A feed that half-parses still shows the headlines it could read.
 *
 * Results are cached via [HttpCache] so the widget renders instantly and survives brief offline
 * periods by serving the last-fetched headlines.
 */
object NewsFeed {

  private const val CACHE_KEY = "news_feed"
  // Headlines don't change minute-to-minute on a wall display; a 15-min freshness window keeps
  // network use light while staying current enough.
  private const val TTL_MS = 15L * 60 * 1000

  /** Greatest number of headlines we keep per feed, to bound the widget. */
  const val MAX_HEADLINES = 8

  /** One parsed headline: the title and (optional) article link. */
  data class Headline(val title: String, val link: String?)

  /**
   * Fetch and parse headlines for a single feed [url]. Blocking — call from a background dispatcher.
   * Returns an empty list only when there's no cached data AND the network/parse fails.
   */
  fun fetch(context: Context, url: String): List<Headline> {
    val clean = url.trim()
    if (clean.isBlank()) return emptyList()
    val body =
        HttpCache.getOrFetch(context, "$CACHE_KEY:$clean", TTL_MS) {
          Http.getString(clean, headers = mapOf("Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml, */*"))
        } ?: return emptyList()
    return parse(body)
  }

  // --- parsing (pure; unit-tested) --------------------------------------------

  /**
   * Parse an RSS 2.0 or Atom document into ordered headlines. Handles both `<item>` (RSS) and
   * `<entry>` (Atom) blocks; for each it extracts the `<title>` and a link (`<link>href</link>`
   * for RSS, or an Atom `<link href="…">`). CDATA and HTML entities are decoded. Malformed input
   * yields an empty list rather than throwing.
   */
  internal fun parse(xml: String): List<Headline> =
      runCatching {
            val blocks = extractBlocks(xml, "item").ifEmpty { extractBlocks(xml, "entry") }
            blocks
                .mapNotNull { block ->
                  val title = firstTag(block, "title")?.let { clean(it) }
                  if (title.isNullOrBlank()) return@mapNotNull null
                  Headline(title = title, link = extractLink(block))
                }
                .take(MAX_HEADLINES)
          }
          .getOrDefault(emptyList())

  /** All inner-text spans for `<tag …>…</tag>` occurrences (handles attributes on the open tag). */
  private fun extractBlocks(xml: String, tag: String): List<String> {
    val out = ArrayList<String>()
    val open = "<$tag"
    var searchFrom = 0
    while (true) {
      val openIdx = xml.indexOf(open, searchFrom, ignoreCase = true)
      if (openIdx < 0) break
      // Ensure this is the element, not a longer name (e.g. <item> vs <itemssomething>).
      val after = xml.getOrNull(openIdx + open.length)
      if (after != null && after != '>' && after != ' ' && after != '\t' && after != '\n' && after != '\r' && after != '/') {
        searchFrom = openIdx + open.length
        continue
      }
      val openEnd = xml.indexOf('>', openIdx)
      if (openEnd < 0) break
      val closeIdx = xml.indexOf("</$tag>", openEnd, ignoreCase = true)
      if (closeIdx < 0) break
      out.add(xml.substring(openEnd + 1, closeIdx))
      searchFrom = closeIdx + tag.length + 3
    }
    return out
  }

  /** Inner text of the first `<tag …>…</tag>` in [block], or null. */
  private fun firstTag(block: String, tag: String): String? {
    val open = "<$tag"
    val openIdx = block.indexOf(open, ignoreCase = true)
    if (openIdx < 0) return null
    val openEnd = block.indexOf('>', openIdx)
    if (openEnd < 0) return null
    val closeIdx = block.indexOf("</$tag>", openEnd, ignoreCase = true)
    if (closeIdx < 0) return null
    return block.substring(openEnd + 1, closeIdx)
  }

  /**
   * Pull the article link from an entry block: RSS `<link>URL</link>` (inner text), or an Atom
   * `<link href="URL" …/>` attribute. Prefer non-empty RSS text, then the first Atom alternate
   * link. An omitted Atom relation defaults to alternate; self and enclosure links are not articles.
   */
  private fun extractLink(block: String): String? {
    firstTag(block, "link")?.let { inner ->
      val text = clean(inner)
      if (text.startsWith("http")) return text
    }
    val linkTags = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(block)
    // Read complete quoted attributes so data-href or text inside another value cannot become href.
    val attributes = Regex("""\s+([A-Za-z_:][A-Za-z0-9_.:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    for (linkTag in linkTags) {
      var href: String? = null
      var relation: String? = null
      for (attribute in attributes.findAll(linkTag.value)) {
        val name = attribute.groupValues[1]
        val value = attribute.groups[2]?.value ?: attribute.groupValues[3]
        when {
          name.equals("href", ignoreCase = true) -> href = value
          name.equals("rel", ignoreCase = true) -> relation = value
        }
      }
      if (relation != null && relation != "alternate") continue
      val link = href?.let { clean(it) }?.takeIf { it.isNotBlank() } ?: continue
      return link
    }
    return null
  }

  /** Strip CDATA wrappers, decode the common entities, collapse whitespace, trim. */
  internal fun clean(raw: String): String {
    var s = raw.trim()
    // Unwrap one or more CDATA sections.
    s = s.replace(Regex("<!\\[CDATA\\[(.*?)]]>", setOf(RegexOption.DOT_MATCHES_ALL))) { it.groupValues[1] }
    s =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&#x27;", "'")
    return s.replace(Regex("\\s+"), " ").trim()
  }
}
