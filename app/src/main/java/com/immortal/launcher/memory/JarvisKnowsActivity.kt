/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ApprovedHomeIcon
import com.immortal.launcher.ApprovedHomePillButton
import com.immortal.launcher.ApprovedHomeTextButton
import com.immortal.launcher.ApprovedSettingsSwitch
import com.immortal.launcher.DinnerCall
import com.immortal.launcher.R
import com.immortal.launcher.WhosThere
import com.immortal.launcher.currentLayoutClass
import com.immortal.launcher.jarvis.wake.JarvisWakeConfig
import com.immortal.launcher.settings.JarvisSettings
import com.immortal.launcher.settings.SettingsRegistry
import com.immortal.launcher.tvFocusable
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.time.ZoneId
import org.json.JSONObject

/**
 * "What Jarvis knows" (plan §4.3, mock `mocks/jarvis-personalization-20261122` screen 2): a categories rail and the
 * learned items, each with where it came from and how sure Jarvis is; dashed rows are still being learned (tick to
 * keep, bin to forget, both with a 4 s Undo). Learning on/off and Forget all. No add form, nothing to set up. Shows
 * only the scope of whoever is in front right now (the recognised adult, or the household), never anyone else's,
 * and nothing for guests and children.
 */
class JarvisKnowsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.Theme_SampleApp)
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { KnowsScreen(onClose = ::finish) } }
  }

  companion object {
    fun open(context: Context) {
      runCatching {
        context.startActivity(Intent(context, JarvisKnowsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
      }
    }
  }
}

private const val UNDO_MS = 4_000L
private const val LEARN_KEY = "learnFromConversations"

/** One undoable change on the screen: what to say and how to put it back. */
private data class Undoable(val text: String, val undo: (() -> Unit)?)

@Composable
private fun KnowsScreen(onClose: () -> Unit) {
  BackHandler(onBack = onClose)
  val context = LocalContext.current.applicationContext
  val palette = LocalApprovedPalette.current
  val who by WhosThere.state.collectAsState()
  val runtime = remember { JarvisMemory.runtime(context) }
  val revision by runtime.revision.collectAsState()
  val access = remember(who, revision) { JarvisMemory.accessNow(context) }
  var category by remember { mutableStateOf(KnowsRows.Category.EVERYTHING) }
  var toast by remember { mutableStateOf<Undoable?>(null) }
  LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(UNDO_MS); toast = null } }
  Box(Modifier.fillMaxSize().background(palette.background)) {
    when (val a = access) {
      is MemoryAccess.Off -> KnowsLocked(a.reason, onClose)
      is MemoryAccess.On -> {
        val profile = remember(a.scope, revision) { runtime.profile(a.scope) }
        val rows = remember(profile) { KnowsRows.rows(profile, System.currentTimeMillis(), ZoneId.systemDefault()) }
        val portal = remember { runCatching { DinnerCall.selfName(context) }.getOrDefault("Portal") }
        // The real "Jarvis learns from conversations" setting, never the operator's force (re-read on every change).
        val settingOn = remember(revision) { JarvisWakeConfig.load(context).learnFromConversations }
        val actions = KnowsActions(context, runtime, a.scope, portal, settingOn) { toast = it }
        val rail = !currentLayoutClass().compactPortrait
        Row(Modifier.fillMaxSize()) {
          if (rail) KnowsRail(rows, category, onClose) { category = it }
          KnowsMain(a, profile, rows.filter { category == KnowsRows.Category.EVERYTHING || it.category == category },
              category, actions, onBack = if (rail) null else onClose)
        }
      }
    }
    toast?.let { t -> KnowsToast(t, Modifier.align(Alignment.BottomCenter).padding(32.dp)) { toast = null } }
  }
}

/** The screen's changes, each immediate (Forget writes at once) and each reported with its Undo. */
private class KnowsActions(
    private val context: Context,
    private val runtime: MemoryRuntime,
    private val scope: MemoryScope,
    private val portal: String,
    val settingOn: Boolean,
    private val report: (Undoable) -> Unit,
) {
  private fun now() = System.currentTimeMillis()

  fun forget(row: KnowsRows.Row) {
    val before = runtime.profile(scope)
    runtime.update(scope, immediate = true) { MemoryLearner.forget(it, setOf(row.id), now()) }
    report(Undoable("Forgot ${row.title}") { runtime.update(scope, immediate = true) { _ -> before } })
  }

  fun keep(row: KnowsRows.Row) {
    val before = runtime.profile(scope)
    runtime.update(scope) { MemoryLearner.keep(it, row.id, portal, now()) }
    report(Undoable("Kept ${row.title}") { runtime.update(scope) { _ -> before } })
  }

  fun setLearning(on: Boolean) {
    val change = KnowsLearning.change(on, settingOn)
    // Through the registry, so the remote, validation and side effects stay in step with Settings › Jarvis.
    if (change.turnSettingOn) {
      SettingsRegistry.apply(context, JarvisSettings.domain.id, JSONObject().put(LEARN_KEY, true))
    }
    runtime.update(scope) { MemoryLearner.setPaused(it, change.paused) }
    report(Undoable(if (on) "Learning on" else "Learning paused · Jarvis keeps what it knows", null))
  }

  fun forgetAll() {
    val before = runtime.profile(scope)
    runtime.forgetScope(scope)
    report(Undoable("Forgot everything") { runtime.update(scope, immediate = true) { _ -> before } })
  }
}

@Composable
private fun KnowsRail(rows: List<KnowsRows.Row>, selected: KnowsRows.Category, onBack: () -> Unit,
    onSelect: (KnowsRows.Category) -> Unit) {
  val p = LocalApprovedPalette.current
  Row(Modifier.width(320.dp).fillMaxHeight().background(p.sidebar)) {
    Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoundButton("chevronleft", "Back", onBack)
        Text("Jarvis knows", color = p.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
      }
      KnowsRows.Category.entries.forEach { c ->
        val on = c == selected
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp))
            .background(if (on) p.surface else androidx.compose.ui.graphics.Color.Transparent)
            .semantics { this.selected = on; role = Role.Tab }
            .tvFocusable(RoundedCornerShape(18.dp), focusScale = 1f, ringColor = p.accent) { onSelect(c) }
            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(c.title, color = if (on) p.text else p.secondary, fontSize = 24.sp, modifier = Modifier.weight(1f))
          Text("${KnowsRows.count(rows, c)}", color = p.secondary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
      }
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(p.divider))
  }
}

@Composable
private fun KnowsMain(access: MemoryAccess.On, profile: MemoryProfile, rows: List<KnowsRows.Row>,
    category: KnowsRows.Category, actions: KnowsActions, onBack: (() -> Unit)?) {
  val p = LocalApprovedPalette.current
  val household = access.scope is MemoryScope.Household
  Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      onBack?.let { RoundButton("chevronleft", "Back", it) }
      Text(if (household) "Household · ${category.title}" else category.title, color = p.text, fontSize = 34.sp,
          fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Row(Modifier.heightIn(min = 68.dp).clip(RoundedCornerShape(34.dp)).background(p.surface)
          .padding(start = 22.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Learning", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        ApprovedSettingsSwitch(checked = KnowsLearning.checked(actions.settingOn, profile.paused),
            onCheckedChange = { actions.setLearning(it) })
      }
      ApprovedHomePillButton("Forget all") { actions.forgetAll() }
    }
    KnowsBanner(if (household) {
      "Learned automatically while nobody was recognised, from what's said to Jarvis on this Portal. Shared by " +
          "everyone here. Dashed items are still being learned."
    } else {
      "Learned automatically from what ${access.firstName ?: "you"} says to Jarvis on this Portal. Kept sealed on " +
          "this Portal, only for you. Dashed items are still being learned."
    })
    if (rows.isEmpty()) {
      KnowsEmpty("Nothing here yet", "Jarvis learns as you talk. There's nothing to set up.")
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(rows, key = { it.id }) { row -> KnowsFactRow(row, actions) }
      }
    }
  }
}

@Composable
private fun KnowsFactRow(row: KnowsRows.Row, actions: KnowsActions) {
  val p = LocalApprovedPalette.current
  val shape = RoundedCornerShape(24.dp)
  val base = Modifier.fillMaxWidth().heightIn(min = 104.dp).clip(shape)
  val styled = if (row.tentative) base.dashedBorder(p.outline, 24.dp) else base.background(p.surface)
  Row(styled.padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(p.surface2), contentAlignment = Alignment.Center) {
      if (row.badge != null) {
        Text(row.badge, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
      } else {
        ApprovedHomeIcon(glyph(row.category), Modifier.size(30.dp), tint = p.secondary)
      }
    }
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(row.title, color = p.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (row.fresh) KnowsTag("new")
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ConfidenceBars(row.bars)
        Text(row.detail, color = p.secondary, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    if (row.tentative) RoundButton("check", "Yes, keep ${row.title}") { actions.keep(row) }
    RoundButton("trash", "Forget ${row.title}") { actions.forget(row) }
  }
}

private fun glyph(category: KnowsRows.Category) = when (category) {
  KnowsRows.Category.MUSIC -> "music"
  KnowsRows.Category.MARKETS -> "stocks"
  else -> "info"
}

@Composable
private fun RoundButton(icon: String, label: String, onClick: () -> Unit) {
  val p = LocalApprovedPalette.current
  Box(Modifier.size(68.dp).clip(CircleShape).background(p.surface2)
      .tvFocusable(CircleShape, ringColor = p.accent, onClick = onClick)
      .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
    ApprovedHomeIcon(icon, Modifier.size(28.dp), tint = p.text)
  }
}

@Composable
private fun KnowsBanner(text: String) {
  val p = LocalApprovedPalette.current
  Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(24.dp)).background(p.surface2)
      .padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    ApprovedHomeIcon("info", Modifier.size(30.dp), tint = p.secondary)
    Text(text, color = p.secondary, fontSize = 20.sp, lineHeight = 28.sp, modifier = Modifier.weight(1f))
  }
}

@Composable
private fun KnowsLocked(reason: MemoryAccess.Reason, onClose: () -> Unit) {
  val (title, body) = when (reason) {
    MemoryAccess.Reason.DISABLED -> "Learning is off" to
        "Turn on \"Jarvis learns from conversations\" in Settings, Jarvis. Nothing is learned until then."
    MemoryAccess.Reason.GUEST -> "Nothing is kept for guests" to "Guest mode is on, so chats aren't remembered."
    MemoryAccess.Reason.CHILD -> "Nothing is kept for children" to "Children are never profiled."
    MemoryAccess.Reason.TEEN -> "Nothing is kept for teens" to "Teens aren't profiled yet."
  }
  Column(Modifier.fillMaxSize().padding(32.dp)) {
    RoundButton("chevronleft", "Back", onClose)
    KnowsEmpty(title, body)
  }
}

@Composable
private fun KnowsEmpty(title: String, body: String) {
  val p = LocalApprovedPalette.current
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
      ApprovedHomeIcon("lock", Modifier.size(56.dp), tint = p.secondary)
      Text(title, color = p.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
      Text(body, color = p.secondary, fontSize = 22.sp, lineHeight = 30.sp, textAlign = TextAlign.Center)
    }
  }
}

@Composable
private fun KnowsTag(text: String) {
  val p = LocalApprovedPalette.current
  Text(text, color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
      modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(p.selected).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
private fun KnowsToast(toast: Undoable, modifier: Modifier, onDone: () -> Unit) {
  val p = LocalApprovedPalette.current
  Row(modifier.clip(RoundedCornerShape(24.dp)).background(p.text).padding(horizontal = 28.dp, vertical = 10.dp)
      .heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    Text(toast.text, color = p.background, fontSize = 22.sp)
    toast.undo?.let { undo -> ApprovedHomeTextButton("Undo") { undo(); onDone() } }
  }
}
