/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * Editor for the news widget's RSS/Atom feed subscriptions. Light iOS layout matching the other
 * widget editors: a card of current feeds (tap ✕ to remove), a palette of popular keyless feeds,
 * and a manual name/URL add.
 */
class NewsSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { NewsSettingsScreen() } }
  }
}

/** Popular keyless RSS feeds as (display name, url) pairs for one-tap add. */
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
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "News",
      subtitle = "Headlines from public RSS/Atom feeds on the home screen. No account needed.",
      onBack = { activity?.finish() },
  ) { NewsSettingsContent() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsSettingsContent() {
  val context = LocalContext.current
  var feeds by remember { mutableStateOf(NewsConfig.load(context)) }
  var name by remember { mutableStateOf("") }
  var url by remember { mutableStateOf("") }

  fun refresh() {
    feeds = NewsConfig.load(context)
  }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    // Current feeds.
    if (feeds.isNotEmpty()) {
      Card {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
          feeds.forEach { f ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                    f.name.ifBlank { "Feed" },
                    color = IosLabel,
                    fontSize = Sizes.rowTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(f.url, color = IosSecondary, fontSize = Sizes.rowSub, maxLines = 1)
              }
              Box(
                  modifier =
                      Modifier.size(40.dp).clickable {
                        NewsConfig.remove(context, f.id)
                        refresh()
                      },
                  contentAlignment = Alignment.Center,
              ) {
                Text("✕", color = Color(0xFFFF3B30), fontSize = 20.sp)
              }
            }
          }
        }
      }
    }

    // Popular feeds palette.
    Card {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Popular feeds", color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          FEED_SUGGESTIONS.forEach { (label, feedUrl) ->
            val added = feeds.any { it.url == feedUrl }
            Surface(
                color = if (added) IosDivider else Color(0xFFE5F0FF),
                shape = RoundedCornerShape(12.dp),
                modifier =
                    Modifier.clickable(enabled = !added) {
                      NewsConfig.add(context, label, feedUrl)
                      refresh()
                    },
            ) {
              Text(
                  if (added) "$label ✓" else "+ $label",
                  color = if (added) IosSecondary else IosBlue,
                  fontSize = 15.sp,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
              )
            }
          }
        }
      }
    }

    // Manual add.
    Card {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add a feed URL", color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            colors = iosTextFieldColors(),
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            colors = iosTextFieldColors(),
            value = url,
            onValueChange = { url = it },
            label = { Text("RSS / Atom URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Surface(
            color = IosBlue,
            shape = RoundedCornerShape(14.dp),
            modifier =
                Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) {
                  NewsConfig.add(context, name, url)
                  name = ""
                  url = ""
                  refresh()
                },
        ) {
          Text(
              "Add feed",
              color = Color.White,
              fontSize = 18.sp,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
          )
        }
      }
    }
    Spacer(Modifier.size(8.dp))
  }
}
