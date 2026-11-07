/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private object PaneStyle {
  val frame = Color(0xFF2A2621)
  val tabTrack = Color(0xFF221F1B)
  val tabSelected = Color(0xFFF3EEE4)
  val onSelected = Color(0xFF1B1915)
  val tabText = Color(0xFFB9B2A6)
  val caption = Color(0xFFB9B2A6)
  val captionDim = Color(0xFF8C867C)
  val page = Color(0xFF0F0F0F)
  val body = Color(0xFFA9A49B)
  val title = Color(0xFFF3EEE4)
  const val BLOCKED_TAB_ALPHA = 0.45f
}

/**
 * The right half of the split music player: native Queue / Library / Search tabs with NATIVE rows
 * read from the retained YouTube Music page ([YtMusicWebHost]). The page stays attached and laid
 * out at full size (playback and the page's lazy lists need it) but sits under an opaque native
 * surface; "Show YouTube page" reveals it (e.g. to sign in). Tapping a row plays it through the
 * page. If Google refuses sign-in in the WebView, the pane gives way to one sentence and one
 * button ([onOpenInBrowser]). Leaving composition only un-hosts the page; call
 * [YtMusicWebHost.release] to stop music.
 */
@Composable
fun YtMusicPane(onOpenInBrowser: () -> Unit, modifier: Modifier = Modifier) {
  val status = YtMusicWebHost.status
  val url = YtMusicWebHost.currentUrl
  val lists = YtMusicWebHost.lists
  val blocked = status == YtMusicPageStatus.BLOCKED
  var pane by remember { mutableStateOf(YtMusicPaneState()) }
  var container by remember { mutableStateOf<FrameLayout?>(null) }
  LaunchedEffect(Unit) { YtMusicWebHost.selectTab(pane.tab) }
  val onSelect: (YtMusicTab) -> Unit = { tab ->
    pane = YtMusicPaneReducer.reduce(pane, YtMusicPaneEvent.SelectTab(tab))
    YtMusicWebHost.selectTab(tab)
    // Coming back to Search after the page moved on: re-run the last query.
    val query = pane.submittedQuery
    if (tab == YtMusicTab.SEARCH && query != null &&
        !YtMusicSearch.sameQuery(YtMusicSearch.queryFromUrl(YtMusicWebHost.currentUrl), query)) {
      YtMusicWebHost.search(query)
    }
  }
  val togglePage = {
    pane = YtMusicPaneReducer.reduce(pane, YtMusicPaneEvent.TogglePage)
    if (!pane.showPage) {
      YtMusicWebHost.clearInputFocus()
      YtMusicWebHost.selectTab(pane.tab)
    }
  }
  Column(
      modifier
          .clip(RoundedCornerShape(40.dp))
          .background(PaneStyle.frame)
          .padding(horizontal = 20.dp, vertical = 20.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      PaneTabs(pane.tab, enabled = !blocked, onSelect = onSelect)
      Spacer(Modifier.weight(1f))
      if (!blocked) PageLink(if (pane.showPage) "Back to list" else "Show YouTube page", togglePage)
    }
    Spacer(Modifier.height(14.dp))
    PaneCaption(url, pageShown = pane.showPage)
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(32.dp))
            .background(PaneStyle.page)) {
      // Always attached and full size; only covered. Alpha/visibility would stop it rendering.
      AndroidView(
          factory = { ctx -> FrameLayout(ctx).also { YtMusicWebHost.attach(it); container = it } },
          onRelease = { YtMusicWebHost.detach(it) },
          modifier = Modifier.fillMaxSize(),
      )
      if (!blocked && !pane.showPage) {
        YtMusicNativeLists(
            pane = pane,
            view = YtMusicPaneReducer.view(pane, lists, pageReady = status == YtMusicPageStatus.READY),
            pageReady = status == YtMusicPageStatus.READY,
            onSearch = { raw ->
              val next = YtMusicPaneReducer.reduce(pane, YtMusicPaneEvent.SubmitSearch(raw))
              if (next != pane) {
                pane = next
                next.submittedQuery?.let { YtMusicWebHost.search(it) }
              }
            },
            onPlay = { list, item -> YtMusicWebHost.playItem(list, item) },
            onShowPage = togglePage,
        )
      }
      when (status) {
        YtMusicPageStatus.BLOCKED -> BlockedFallback(onOpenInBrowser)
        YtMusicPageStatus.ERROR -> PaneError { container?.let { YtMusicWebHost.retry(it) } }
        YtMusicPageStatus.LOADING, YtMusicPageStatus.IDLE -> if (pane.showPage) PaneLoading()
        YtMusicPageStatus.READY -> Unit
      }
    }
  }
}

@Composable
private fun PaneTabs(selected: YtMusicTab, enabled: Boolean, onSelect: (YtMusicTab) -> Unit) {
  Row(
      Modifier
          .alpha(if (enabled) 1f else PaneStyle.BLOCKED_TAB_ALPHA)
          .clip(RoundedCornerShape(50))
          .background(PaneStyle.tabTrack)
          .padding(4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    YtMusicTab.entries.forEach { tab ->
      val isSelected = tab == selected
      Box(
          Modifier
              .clip(RoundedCornerShape(50))
              .background(if (isSelected) PaneStyle.tabSelected else Color.Transparent)
              .clickable(enabled = enabled, role = Role.Tab) { onSelect(tab) }
              .semantics { this.selected = isSelected }
              .padding(horizontal = 26.dp, vertical = 12.dp),
          contentAlignment = Alignment.Center) {
        Text(tab.label, fontSize = 22.sp,
            color = if (isSelected) PaneStyle.onSelected else PaneStyle.tabText)
      }
    }
  }
}

@Composable
private fun PaneCaption(url: String?, pageShown: Boolean) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(if (pageShown) "YouTube Music page (embedded)" else "From YouTube Music", fontSize = 15.sp,
        color = PaneStyle.caption, maxLines = 1)
    Spacer(Modifier.size(10.dp))
    Text(YtMusicUrls.caption(url), fontSize = 15.sp, color = PaneStyle.captionDim,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun PaneLoading() {
  Column(Modifier.fillMaxSize().background(PaneStyle.page),
      verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    CircularProgressIndicator(color = PaneStyle.title, modifier = Modifier.size(40.dp))
    Spacer(Modifier.height(18.dp))
    Text("Opening YouTube Music…", fontSize = 20.sp, color = PaneStyle.body)
  }
}

@Composable
private fun PaneError(onRetry: () -> Unit) {
  Column(Modifier.fillMaxSize().background(PaneStyle.page),
      verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Text("Couldn’t connect", fontSize = 26.sp, color = PaneStyle.title, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(10.dp))
    Text("Check your connection, then try again.", fontSize = 18.sp, color = PaneStyle.body)
    Spacer(Modifier.height(24.dp))
    PaneButton("Try again", onRetry)
  }
}

@Composable
private fun BlockedFallback(onOpenInBrowser: () -> Unit) {
  Column(Modifier.fillMaxSize().background(PaneStyle.page).padding(horizontal = 52.dp),
      verticalArrangement = Arrangement.Center) {
    Text("Google won’t allow sign-in here", fontSize = 28.sp, color = PaneStyle.title,
        fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(14.dp))
    Text("Sign in and choose music in the Portal browser. What plays still shows on the left " +
        "and on the frame.", fontSize = 19.sp, lineHeight = 28.sp, color = PaneStyle.body,
        modifier = Modifier.widthIn(max = 520.dp))
    Spacer(Modifier.height(28.dp))
    PaneButton("Sign in on the Portal browser", onOpenInBrowser)
  }
}

@Composable
private fun PaneButton(label: String, onClick: () -> Unit) {
  Button(
      onClick = onClick,
      shape = RoundedCornerShape(50),
      colors = ButtonDefaults.buttonColors(
          containerColor = PaneStyle.tabSelected, contentColor = PaneStyle.onSelected),
      modifier = Modifier.height(64.dp).semantics { contentDescription = label }) {
    Text(label, fontSize = 21.sp, modifier = Modifier.padding(horizontal = 18.dp))
  }
}
