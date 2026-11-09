/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.settings.BoolSpec
import com.immortal.launcher.settings.DerivedSpec
import com.immortal.launcher.settings.EnumSpec
import com.immortal.launcher.settings.IntSpec
import com.immortal.launcher.settings.NavSpec
import com.immortal.launcher.settings.SettingSpec
import com.immortal.launcher.settings.SettingsPresentation
import com.immortal.launcher.settings.SettingsRegistry
import com.immortal.launcher.settings.StringSpec

/** One searchable page or row. [control] is null for a page; picking a row scrolls to and highlights it. */
internal data class ApprovedSettingsSearchResult(
    val title: String,
    val page: String,
    val control: String? = null,
    val crumb: String = "",
    /** Ids, option labels and synonyms; these rank above help-text and breadcrumb matches. */
    val keywords: String = "",
    val advanced: Boolean = false,
    /** An old name that now lives elsewhere ("Slideshow" → Photo Frame). */
    val alias: Boolean = false,
    /** A read-only value the app manages for you. */
    val automatic: Boolean = false,
    val help: String = "",
) {
    /** Kept for callers that expect a blank id for page results. */
    val id: String get() = control.orEmpty()
}

/** Read-only values shown on the Automatically managed page (hidden registry specs). */
internal val ApprovedManagedControls = setOf(
    "screensaver.cacheBudgetGb", "screensaver.antiBurnIn",
    "mqtt.validateCert", "digitalclock.style", "digitalclock.color", "digitalclock.font",
    "digitalclock.glow",
)

private const val SEARCH_RESULT_LIMIT = 24
private const val KEYWORD_ONLY = 3.0
private const val CRUMB_SEPARATOR = " › "
private val SEARCH_WHITESPACE = Regex("\\s+")
private val HYPHENS = Regex("[-‑]")

/** Title, help and option labels of a registry spec; null for derived values. */
internal fun settingsSpecText(spec: SettingSpec<*>): Triple<String, String, String>? = when (spec) {
    is BoolSpec<*> -> Triple(spec.title, spec.help.orEmpty(), "")
    is IntSpec<*> -> Triple(spec.title, spec.help.orEmpty(), "")
    is EnumSpec<*> -> Triple(spec.title, spec.help.orEmpty(), spec.options.joinToString(" ") { it.second })
    is StringSpec<*> -> Triple(spec.title, spec.help.orEmpty(), "")
    is NavSpec<*> -> Triple(spec.title, spec.help.orEmpty(), "")
    is DerivedSpec<*> -> null
}

/** Every registry control a block or row list covers, as `domain.key` with its spec. */
internal fun settingsClaimedSpecs(block: SettingsBlock): List<Pair<String, SettingSpec<*>>> =
    SettingsRegistry.domains.flatMap { domain ->
        domain.specs.filter { it !is DerivedSpec<*> && block.claimsControl("${domain.id}.${it.key}") }
            .map { "${domain.id}.${it.key}" to it }
    }

/** The registry's presentation policy for a `domain.key` id (native ids share the namespace). */
internal fun settingsControlIsAdvanced(id: String): Boolean =
    SettingsPresentation.isAdvanced(id.substringBefore('.'), id.substringAfter('.'))

private fun pageCrumb(page: String): String =
    SettingsTree.chainOf(page).mapNotNull { SettingsTree.page(it)?.title }.joinToString(CRUMB_SEPARATOR)

private fun specResult(id: String, spec: SettingSpec<*>, page: String, crumb: String, advanced: Boolean):
    ApprovedSettingsSearchResult? {
    val (title, help, options) = settingsSpecText(spec) ?: return null
    // One Wake time editor writes both sunrise scalars; the minute has no row of its own.
    if (id == "sunrise.minute") return null
    val wakeTime = id == "sunrise.hour"
    return ApprovedSettingsSearchResult(
        title = if (wakeTime) "Wake time" else title, page = page, control = id, crumb = crumb,
        keywords = listOf(id, options, if (wakeTime) "hour minute sunrise.minute" else "").joinToString(" "),
        advanced = advanced || settingsControlIsAdvanced(id),
        automatic = id in ApprovedManagedControls,
        help = help,
    )
}

private fun sectionResults(page: String, section: SettingsSection, pageAdvanced: Boolean): List<ApprovedSettingsSearchResult> {
    val crumb = listOfNotNull(pageCrumb(page), section.header).joinToString(CRUMB_SEPARATOR)
    val advanced = pageAdvanced || section.advanced
    return section.items.flatMap { item ->
        when (item) {
            is SettingsDomainRows -> item.keys.mapNotNull { key ->
                SettingsRegistry.domain(item.domain)?.specs?.firstOrNull { it.key == key }
                    ?.let { specResult("${item.domain}.$key", it, page, crumb, advanced) }
            }
            is SettingsBlockItem -> item.block.rows.map {
                ApprovedSettingsSearchResult(it.title, page, it.id, crumb, "${it.id} ${it.keywords}",
                    advanced || it.advanced || settingsControlIsAdvanced(it.id))
            } + settingsClaimedSpecs(item.block).mapNotNull { (id, spec) -> specResult(id, spec, page, crumb, advanced) }
            is SettingsLinkItem -> listOf(ApprovedSettingsSearchResult(item.title, page, item.key, crumb,
                item.keywords, advanced || SettingsTree.needsAdvanced(item.target)))
            is SettingsExternalItem -> listOf(ApprovedSettingsSearchResult(item.title, page, item.key, crumb,
                item.keywords, advanced, help = item.subtitle))
        }
    }
}

/** Every page and row of the tree (not only composed pages), plus old names as aliases. */
internal fun approvedSettingsSearchIndex(): List<ApprovedSettingsSearchResult> =
    FramePerformanceCapture.measureSettingsSearchIndex {
        val entries = ArrayList<ApprovedSettingsSearchResult>()
        SettingsTree.pages.forEach { page ->
            val pageAdvanced = SettingsTree.needsAdvanced(page.id)
            val parents = SettingsTree.chainOf(page.id).dropLast(1)
                .mapNotNull { SettingsTree.page(it)?.title }.joinToString(CRUMB_SEPARATOR)
            entries += ApprovedSettingsSearchResult(page.title, page.id, null, parents.ifEmpty { "Page" },
                page.keywords, pageAdvanced)
            SettingsPageSections.of(page.id).forEach { entries += sectionResults(page.id, it, pageAdvanced) }
        }
        SettingsSearchAliases.forEach { alias ->
            val target = SettingsTree.page(alias.page)?.title.orEmpty()
            entries += ApprovedSettingsSearchResult(alias.oldName, alias.page, alias.control,
                "Was ${alias.oldPlace} → $target", alias.oldPlace, SettingsTree.needsAdvanced(alias.page), alias = true)
        }
        entries
    }

/** Breadcrumb shown under a result. */
internal fun approvedSettingsSearchBreadcrumb(result: ApprovedSettingsSearchResult): String =
    result.crumb + if (result.automatic) " · Automatic" else ""

private fun normalize(text: String) = text.lowercase().replace(HYPHENS, "")

/**
 * Every word must appear in the title, keywords, help or breadcrumb. Titles that start with the
 * query rank first, then titles containing it, then keyword matches, then help/breadcrumb matches;
 * old-name aliases rank after real rows, and are dropped when the same row is already listed.
 */
internal fun approvedSettingsSearch(
    index: List<ApprovedSettingsSearchResult>,
    query: String,
): List<ApprovedSettingsSearchResult> {
    val phrase = normalize(query.trim().replace(SEARCH_WHITESPACE, " "))
    if (phrase.isBlank()) return emptyList()
    val words = phrase.split(' ')
    val scored = index.mapNotNull { entry ->
        val title = normalize(entry.title)
        val named = normalize("${entry.title} ${entry.keywords}")
        val haystack = normalize("$named ${entry.help} ${entry.crumb}")
        if (!words.all { it in haystack }) return@mapNotNull null
        var score = when {
            title == phrase -> 0.0
            title.startsWith(phrase) -> 1.0
            title.contains(phrase) -> 2.0
            words.all { it in title } -> 2.5
            words.all { it in named } -> KEYWORD_ONLY
            else -> KEYWORD_ONLY + 0.5
        }
        if (entry.alias) score += 3.0
        // A page whose title matches leads its rows; one matched only by keywords follows them.
        if (entry.control == null) score += if (score < KEYWORD_ONLY) -0.5 else 0.5
        score to entry
    }.sortedBy { it.first }
    val seen = HashSet<String>()
    return scored.asSequence().map { it.second }
        .filter { seen.add("${it.page}|${it.control.orEmpty()}") }
        .take(SEARCH_RESULT_LIMIT).toList()
}

/** Why a highlighted row may be hidden, so search can explain instead of landing on nothing. */
internal fun approvedSettingsPrerequisite(context: android.content.Context, control: String): String? {
    val domainId = control.substringBefore('.')
    val key = control.substringAfter('.', "")
    val raw = SettingsRegistry.domain(domainId) ?: return null
    @Suppress("UNCHECKED_CAST")
    val domain = raw as com.immortal.launcher.settings.SettingsDomain<Any>
    val spec = domain.specs.firstOrNull { it.key == key } ?: return null
    if (control in ApprovedManagedControls) return "This value is managed by the existing app and remains read-only."
    // Registry rows of the screensaver domain only show while the photo frame screensaver is on.
    if (domainId == "screensaver" && key != "enabled" && SettingsPageSections.domainRowPage(control) != null &&
        !ScreensaverConfig.load(context).enabled)
        return "Turn on the photo frame screensaver in Photo Frame to change this setting."
    if (spec.visibleWhen(context, domain.load(context))) return null
    return when {
        domainId == "calendar" -> "Add a calendar feed and turn on Show on screensaver to see these options."
        domainId == "digitalclock" -> "Turn on the digital clock to see this setting."
        key == "cacheEnabled" -> "Connect an Immich or WebDAV photo source to see cache settings."
        key == "batterySaver" -> "This setting is unavailable on a Portal without a battery."
        key.startsWith("overnight") -> "Turn on Overnight screen-off to set its times."
        key.startsWith("sleepTimer") -> "Turn on Sleep timer to set its duration."
        key == "fit" -> "Choose the Classic screensaver style to change Fit."
        key == "soundscapeVolume" -> "Choose an ambient sound to set its volume."
        domainId == "quickbar" -> "Turn on the app-switcher button to see this option."
        domainId == "chime" && key.startsWith("quiet") -> "Turn on Quiet hours to set its times."
        domainId == "chime" -> "Turn on the corresponding sound to see its options."
        domainId == "immortal" && (key.startsWith("ma") || key == "snapcastHost") ->
            "Turn on multi-room audio to edit the Music Assistant connection."
        else -> "This setting depends on the options above it on this page."
    }
}
