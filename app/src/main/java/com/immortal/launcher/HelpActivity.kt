/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * A friendly, non-technical guided tour of Immortal. Opened from the Help tile,
 * and shown once automatically on first launch. Plain language only — written for
 * someone who just wants to use their Portal, not a developer.
 *
 * Works with a finger (swipe or tap Next) and with the Portal TV remote (D-pad
 * on the Back/Next buttons, BACK to leave).
 */
class HelpActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    markSeen(this)
    setContent { PortalPrimeTheme(darkTheme = false) { HelpTour() } }
  }

  companion object {
    private const val PREFS = "immortal_help"
    private const val KEY_SEEN = "seen"

    fun hasSeen(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(c: Context) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
  }
}

private data class HelpPage(
    val title: String,
    val body: String,
    val linkLabel: String? = null,
    val linkUrl: String? = null,
)

private val helpPages =
    listOf(
        HelpPage(
            title = "Welcome to Immortal",
            body =
                "Your Portal has a second life. Immortal is a free, community-made home " +
                    "screen, photo frame, and app store that keeps it useful now that it's no " +
                    "longer supported. Here's a quick tour — it only takes a minute.",
        ),
        HelpPage(
            title = "Finding your way around",
            body =
                "Tap any tile to open it. Tiles that look like a little stack — like " +
                    "Settings — are folders, so tap to see what's inside. From inside any app, " +
                    "tap the round home button or the Immortal icon to come straight back here.",
        ),
        HelpPage(
            title = "Making video calls",
            body =
                "Calling still works! Tap the Calls tile, then pick a person from the " +
                    "Portal's original screen that appears. When you've finished your call, tap " +
                    "the home button or the Immortal icon to return to Immortal.",
        ),
        HelpPage(
            title = "Your personal photo frame",
            body =
                "When you're not using it, your Portal becomes a gentle photo frame. To " +
                    "show your own pictures, open Settings, choose Screensaver, and pick a " +
                    "folder of your photos. On the Portal Go, it can quietly sleep when no one's " +
                    "nearby to save battery — that's a setting there too.",
        ),
        HelpPage(
            title = "Adding more apps",
            body =
                "Tap App Store to add hand-picked apps — media players, a browser, " +
                    "smart-home dashboards and more. Want something specific? Install Aurora " +
                    "Store from there. It opens the door to most of the apps you'd expect on an " +
                    "Android device, with no Google account required.",
        ),
        HelpPage(
            title = "Installing apps after a restart",
            body =
                "Adding new apps uses a clever helper that runs until your Portal is " +
                    "switched off or restarted. Everything you've already installed keeps " +
                    "working — but to install something NEW after a restart, reconnect your " +
                    "Portal to your computer and run the Immortal setup again. It's quick, and " +
                    "completely safe to repeat as often as you like.",
        ),
        HelpPage(
            title = "A few honest limits",
            body =
                "Immortal does a lot, but it can't do everything. Some apps that depend on " +
                    "Google's services may not fully work, and we can't change the Portal's " +
                    "built-in software. We focus on the things that work well and reliably — " +
                    "and we're finding more all the time.",
        ),
        HelpPage(
            title = "Built by fans, for fans",
            body =
                "Immortal is made by volunteers who didn't want these lovely devices to end " +
                    "up in a drawer. We add new apps and features as the community creates " +
                    "them. Want to help, suggest an app, or just say hello? Come find us on " +
                    "GitHub.",
            linkLabel = "Open our GitHub page",
            linkUrl = "https://github.com/starbrightlab/immortal",
        ),
    )

@Composable
private fun HelpTour() {
  val context = LocalContext.current
  val activity = context as? Activity
  var page by remember { mutableIntStateOf(0) }
  val last = helpPages.lastIndex
  val nextFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { nextFocus.requestFocus() } }

  fun go(delta: Int) {
    page = (page + delta).coerceIn(0, last)
  }

  Box(
      Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        if (e.key == Key.Back || e.key == Key.Escape) {
          if (e.type == KeyEventType.KeyUp) activity?.finish()
          true
        } else false
      },
  ) {
    SettingsScaffold(
        title = "Welcome to Immortal",
        onBack = { activity?.finish() },
    ) {
      Surface(
          color = IosCard,
          shape = RoundedCornerShape(24.dp),
          border = BorderStroke(1.dp, IosDivider),
          modifier = Modifier.fillMaxWidth().padding(bottom = 26.dp)
              .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onHorizontalDrag = { change, d -> change.consume(); dx += d },
                    onDragEnd = { if (dx < -80) go(1) else if (dx > 80) go(-1) },
                    onDragCancel = { dx = 0f },
                )
              },
      ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 34.dp)) {
          // Only the current topic is composed; the shared scaffold owns bounded scrolling.
          PageContent(helpPages[page])
          Row(
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            PillButton(label = "Back", filled = false) { go(-1) }
            PillButton(label = "Skip", filled = false) { activity?.finish() }
            PillButton(
                label = if (page < last) "Next" else "Done",
                filled = true,
                focusRequester = nextFocus,
            ) {
              if (page < last) go(1) else activity?.finish()
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PageContent(p: HelpPage) {
  val context = LocalContext.current
  Column(Modifier.fillMaxWidth()) {
    Text(
        p.title,
        color = IosLabel,
        fontSize = 34.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Start,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        p.body,
        color = IosSecondary,
        fontSize = 24.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Start,
    )
    Spacer(Modifier.height(28.dp))
    if (p.linkLabel != null && p.linkUrl != null) {
      PillButton(label = p.linkLabel, filled = false) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.linkUrl))) }
      }
      Spacer(Modifier.height(16.dp))
    }
  }
}

@Composable
private fun PillButton(
    label: String,
    filled: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
  val base = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
  Surface(
      color = if (filled) IosBlue else IosSegmentTrack,
      shape = RoundedCornerShape(16.dp),
      modifier = base.heightIn(min = 68.dp)
          .tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f, ringColor = IosBlue, onClick = onClick),
  ) {
    Box(contentAlignment = Alignment.Center,
        modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp)) {
      Text(
          label,
          color = if (filled) MaterialTheme.colorScheme.onPrimary else IosLabel,
          fontSize = 24.sp,
          lineHeight = 32.sp,
          fontWeight = FontWeight.Medium,
          textAlign = TextAlign.Center,
      )
    }
  }
}
