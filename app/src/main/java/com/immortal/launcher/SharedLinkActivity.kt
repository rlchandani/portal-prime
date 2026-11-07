/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.delay

/**
 * A shared link's 5-second heads-up ("Rohit's Pixel sent a link"), then the page opens; Cancel
 * reaches the phone through `GET /remote/share`. Not exported. INTEGRATOR: declare in the manifest.
 */
class SharedLinkActivity : ComponentActivity() {
  private var settled = false

  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.Theme_SampleApp_Translucent)
    super.onCreate(savedInstanceState)
    val id = intent.getStringExtra(EXTRA_ID).orEmpty()
    val url = intent.getStringExtra(EXTRA_URL)?.takeIf(SharePayloads::validUrl)
    if (url == null || id.isEmpty()) { finish(); return }
    val from = SharePayloads.cleanName(intent.getStringExtra(EXTRA_FROM)) ?: "A phone"
    val title = intent.getStringExtra(EXTRA_TITLE)
    setContent {
      PortalPrimeTheme(darkTheme = true) {
        var left by remember { mutableIntStateOf(COUNTDOWN_S) }
        LaunchedEffect(Unit) {
          while (left > 0) { delay(1_000); left -= 1 }
          settle(id, url, opened = true)
        }
        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
          ApprovedHomeSurface(Modifier.widthIn(max = 960.dp)) {
            Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
              Text("$from sent a link", color = ApprovedHomePalette.Secondary, fontSize = 24.sp)
              Text(title ?: url, color = ApprovedHomePalette.Text, fontSize = 34.sp, fontWeight = FontWeight.Medium, maxLines = 2)
              Text(android.net.Uri.parse(url).host.orEmpty(), color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
              Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Opening in $left s", color = ApprovedHomePalette.Text, fontSize = 24.sp, modifier = Modifier.weight(1f))
                ApprovedHomeTextButton("Cancel", onClick = { settle(id, url, opened = false) })
                ApprovedHomeTextButton("Open now", onClick = { settle(id, url, opened = true) })
              }
            }
          }
        }
      }
    }
  }

  private fun settle(id: String, url: String, opened: Boolean) {
    if (settled) return
    settled = true
    ShareFeature.linkFinished(this, id, opened)
    if (opened) ShareFeature.openLink(this, url)
    finish()
  }

  override fun onStop() {
    super.onStop()
    // Leaving the heads-up without choosing counts as Cancel, so nothing opens later by surprise.
    if (!settled && !isChangingConfigurations) { settled = true; ShareFeature.linkFinished(this, intent.getStringExtra(EXTRA_ID).orEmpty(), false); finish() }
  }

  companion object {
    private const val COUNTDOWN_S = 5
    private const val EXTRA_ID = "id"
    private const val EXTRA_URL = "url"
    private const val EXTRA_FROM = "from"
    private const val EXTRA_TITLE = "title"

    internal fun show(context: Context, link: ShareRequest.Link) {
      runCatching {
        context.startActivity(Intent(context, SharedLinkActivity::class.java)
            .putExtra(EXTRA_ID, link.id).putExtra(EXTRA_URL, link.url).putExtra(EXTRA_FROM, link.from)
            .putExtra(EXTRA_TITLE, link.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }.onFailure {
        // Could not show the heads-up: keep the link as a note rather than losing it.
        HomeNoteStore.add(context, HomeNote(link.id, link.from, HomeNoteStore.clock(), url = link.url, title = link.title))
        HomeNoteStore.link(context, link.id, "note")
      }
    }
  }
}
