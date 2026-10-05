/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

class NewsSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme(darkTheme = false) { NewsSettingsScreen() } }
    }
}

private val FEED_SUGGESTIONS =
    listOf(
        "BBC" to "https://feeds.bbci.co.uk/news/world/rss.xml",
        "NPR" to "https://feeds.npr.org/1001/rss.xml",
        "Reuters" to "https://www.reutersagency.com/feed/",
        "Hacker News" to "https://hnrss.org/frontpage",
        "The Verge" to "https://www.theverge.com/rss/index.xml",
        "Ars Technica" to "https://feeds.arstechnica.com/arstechnica/index",
    )


@Composable
private fun NewsSettingsScreen() {
    val onBack = rememberApprovedSettingsBack()
    SettingsScaffold(title = "News", onBack = onBack) { NewsSettingsContent() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsSettingsContent() {
    val context = LocalContext.current
    val onBack = rememberApprovedSettingsBack()
    var feeds by remember { mutableStateOf(NewsConfig.load(context)) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<NewsConfig.Feed?>(null) }
    val subscribed = remember(feeds) { feeds.map { it.url }.toSet() }
    fun refresh() { feeds = NewsConfig.load(context) }
    Column {
        ApprovedEditorsCollection("Your news", feeds, key = { it.id }) { feed ->
            ApprovedEditorsCollectionRow(feed.name.ifBlank { "Feed" }, feed.url,
                onRemove = { removal = feed })
        }
        ApprovedEditorsForm(actions = {
            ApprovedEditorsActions(error = error, onCancel = {
                name = ""; url = ""; error = null; onBack()
            }) {
                if (!approvedEditorsHttpUrl(url)) error = "Enter a public http:// or https:// RSS / Atom URL."
                else { NewsConfig.add(context, name, url); name = ""; url = ""; error = null; refresh() }
            }
        }) {
            ApprovedEditorsField("Name (optional)", name, { name = it })
            Divider()
            ApprovedEditorsField("RSS / Atom URL", url, { url = it; error = null }, placeholder = "https://…")
        }
        SectionLabel("Popular choices")
        Card {
            FlowRow(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FEED_SUGGESTIONS.forEach { (label, feedUrl) ->
                    val added = feedUrl in subscribed
                    ApprovedEditorsButton(if (added) "$label ✓" else label, enabled = !added) {
                        NewsConfig.add(context, label, feedUrl); refresh()
                    }
                }
            }
        }
    }
    removal?.let { feed ->
        ApprovedEditorsRemoveDialog(feed.name.ifBlank { "Feed" }, onCancel = { removal = null }) {
            NewsConfig.remove(context, feed.id); removal = null; refresh()
        }
    }
}
