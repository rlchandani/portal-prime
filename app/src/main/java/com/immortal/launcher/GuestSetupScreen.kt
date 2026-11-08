/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.GuestSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Home → Guest card: a short step-by-step setup with a live preview of the card beside
 * it. Nothing is written until "Looks good"; non-secret values go through [GuestSettings.domain],
 * the password and door code only to their [GuestSecret] stores.
 */
@Composable
internal fun GuestSettingsContent() {
  val context = LocalContext.current.applicationContext
  var loaded by remember { mutableStateOf<GuestCardContent?>(null) }
  LaunchedEffect(Unit) { loaded = withContext(Dispatchers.IO) { loadGuestCard(context) } }
  val card = loaded ?: return GuestHint("Loading the guest card…")
  var draft by remember(card) { mutableStateOf(GuestSetupDraft.from(card.settings, card.password, card.doorCode)) }
  var saved by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  val save: () -> Unit = {
    val plan = draft.save()
    scope.launch {
      val ok = withContext(Dispatchers.IO) { writeGuestSetup(context, plan) }
      saved = ok
      message = if (ok) null else "Couldn't save the password or door code. Try again."
    }
  }
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
      if (saved) GuestSetupDone(onEdit = { saved = false })
      else GuestSetupFlow(draft, card.use24, message, onDraft = { draft = it; message = null }, onSave = save)
    }
    GuestSetupPreview(GuestCardContent(draft.settings, draft.password, if (draft.doorCodeOn) draft.doorCode else "", card.use24))
  }
}

@Composable
private fun GuestSetupFlow(
    draft: GuestSetupDraft, use24: Boolean, message: String?,
    onDraft: (GuestSetupDraft) -> Unit, onSave: () -> Unit,
) {
  GuestStepPills(draft) { onDraft(draft.goTo(it)) }
  Text(draft.step.title, color = ApprovedHomePalette.Text, fontSize = 34.sp, lineHeight = 42.sp,
      fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
  when (draft.step) {
    GuestSetupStep.NETWORK -> GuestNetworkStep(draft, onDraft)
    GuestSetupStep.PASSWORD -> GuestPasswordStep(draft, onDraft)
    GuestSetupStep.BASICS -> GuestBasicsStep(draft, use24, onDraft)
    GuestSetupStep.WELCOME -> GuestWelcomeStep(draft, onDraft)
    GuestSetupStep.FINISH -> GuestFinishStep(draft)
  }
  val problem = message ?: draft.problem(draft.step)
  problem?.let { Text(it, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
  Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    if (draft.step != draft.steps.first()) ToolsButton("Back") { onDraft(draft.back()) }
    if (draft.step == GuestSetupStep.FINISH) ToolsButton("Looks good", primary = true, enabled = draft.canFinish, onClick = onSave)
    else ToolsButton("Next", primary = true, enabled = draft.problem(draft.step) == null) { onDraft(draft.next()) }
  }
}

@Composable
private fun GuestFinishStep(draft: GuestSetupDraft) {
  GuestHint(if (draft.canFinish) "This is how the card looks to guests. Tap Looks good to save it."
      else "A step still needs something. Tap it above to finish it.")
}

@Composable
private fun GuestSetupDone(onEdit: () -> Unit) {
  val context = LocalContext.current
  val mode by GuestMode.state.collectAsState()
  val on = mode.isOn(System.currentTimeMillis())
  Text("The guest card is ready", color = ApprovedHomePalette.Text, fontSize = 34.sp, lineHeight = 42.sp,
      fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
  GuestHint(if (on) "Guest mode is on. ${guestUntilLabel(mode)}."
      else "To start Guest mode, open the card from Home or Tools → Guests and choose how long guests are staying. " +
          "Guest mode hides photos, calendar, notes and your music library.")
  Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    ToolsButton("Open the guest card", primary = true) { GuestEntry.openCard(context) }
    ToolsButton("Edit again", onClick = onEdit)
    if (on) ToolsButton("End Guest mode") { GuestFeature.end(context) }
  }
}

/** The live card preview: the same parts the guest card uses, at preview size. */
@Composable
private fun GuestSetupPreview(content: GuestCardContent) {
  val shape = RoundedCornerShape(28.dp)
  Column(Modifier.width(PREVIEW_WIDTH).clip(shape).background(ApprovedHomePalette.Background).approvedOutline(shape)
      .padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.Start) {
    GuestLabel("Preview")
    GuestQr(content.network, size = PREVIEW_QR)
    GuestCardDetails(content, compact = true)
  }
}

private val PREVIEW_WIDTH = 420.dp
private val PREVIEW_QR = 260.dp

/** Writes a finished setup; false when a secret couldn't be stored (non-secret values still apply). */
internal fun writeGuestSetup(context: Context, plan: GuestSetupSave): Boolean {
  GuestSettings.domain.apply(context, GuestSettings.domain.flatJson(plan.settings))
  val password = plan.password?.let { GuestPasswordStore.get(context).write(it) } ?: true
  val door = plan.doorCode?.let { GuestDoorCodeStore.get(context).write(it) } ?: true
  return password && door
}
