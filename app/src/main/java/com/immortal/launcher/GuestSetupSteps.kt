/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Step 2: the password, masked, with the card's hide-until-tapped choice. */
@Composable
internal fun GuestPasswordStep(draft: GuestSetupDraft, onDraft: (GuestSetupDraft) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    GuestHint("For ${draft.settings.ssid} (${draft.settings.wifiSecurity.label}).")
    ApprovedEditorsField("Wi-Fi password", draft.password, { onDraft(draft.withPassword(it)) },
        placeholder = "Type the password", secret = true)
    GuestHint("Hearthpane stores it encrypted and never shares it.")
    GuestToggleRow("Hide password on the card until tapped", draft.settings.hidePassword) { onDraft(draft.withHidePassword(it)) }
  }
}

/** Step 3: check-out, Good to know, door code and contact — all choices. */
@Composable
internal fun GuestBasicsStep(draft: GuestSetupDraft, use24: Boolean, onDraft: (GuestSetupDraft) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(36.dp)) {
    GuestCheckoutChoice(draft.settings.checkout, use24) { onDraft(draft.withCheckout(it)) }
    GuestGoodToKnowChoice(draft, onDraft)
    GuestDoorCodeChoice(draft, onDraft)
    GuestContactChoice(draft, onDraft)
  }
}

@Composable
private fun GuestCheckoutChoice(value: String, use24: Boolean, onChange: (String) -> Unit) {
  var picking by remember { mutableStateOf(false) }
  val custom = value !in GuestBasics.CHECKOUT_CHOICES
  GuestSetupGroup("Check-out") {
    GuestChipRow {
      GuestBasics.CHECKOUT_CHOICES.forEach { choice ->
        GuestChoiceChip(GuestBasics.checkoutLabel(choice, use24), value == choice) { onChange(choice) }
      }
      GuestChoiceChip(if (custom) GuestBasics.checkoutLabel(value, use24) else "Custom…", custom) { picking = true }
    }
  }
  if (picking) GuestTimeDialog(value.ifEmpty { "11:00" }, use24, onDismiss = { picking = false }) { onChange(it); picking = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuestTimeDialog(initial: String, use24: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
  val (h, m) = initial.split(':').map { it.toInt() }
  ApprovedSettingsDialog("Check-out time", onDismiss = onDismiss) {
    val clock = rememberTimePickerState(h, m, is24Hour = use24)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
      TimePicker(state = clock, colors = TimePickerDefaults.colors(
          clockDialColor = ApprovedHomePalette.Card, selectorColor = ApprovedHomePalette.Accent,
          containerColor = ApprovedHomePalette.Card))
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ToolsButton("Use this time", primary = true) {
          onPick(String.format(Locale.ROOT, "%02d:%02d", clock.hour, clock.minute))
        }
        ToolsButton("Cancel", onClick = onDismiss)
      }
    }
  }
}

@Composable
private fun GuestGoodToKnowChoice(draft: GuestSetupDraft, onDraft: (GuestSetupDraft) -> Unit) {
  val chosen = GuestBasics.decodePresets(draft.settings.goodToKnow).toSet()
  var adding by remember { mutableStateOf<String?>(null) }
  GuestSetupGroup("Good to know", "Tap everything that applies.") {
    GuestChipRow {
      GuestBasics.PRESETS.forEach { p -> GuestChoiceChip(p.label, p.id in chosen, p.icon) { onDraft(draft.togglePreset(p.id)) } }
      draft.settings.notes.forEach { note ->
        GuestChoiceChip("$note  ×", selected = true, icon = "notes") { onDraft(draft.removeNote(note)) }
      }
      if (adding == null && draft.settings.notes.size < GuestBasics.MAX_NOTES) {
        GuestChoiceChip("Add your own…", selected = false, icon = "plus") { adding = "" }
      }
    }
    adding?.let { text ->
      ApprovedEditorsField("Your note", text, { adding = GuestBasics.cleanNote(it) }, placeholder = "For example: Bins go out Tuesday")
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ToolsButton("Add", primary = true, enabled = text.isNotBlank()) { onDraft(draft.addNote(text)); adding = null }
        ToolsButton("Cancel") { adding = null }
      }
      GuestHint("${text.length} of ${GuestBasics.MAX_NOTE_TYPED} characters")
    }
  }
}

@Composable
private fun GuestDoorCodeChoice(draft: GuestSetupDraft, onDraft: (GuestSetupDraft) -> Unit) {
  GuestSetupGroup("Door code") {
    GuestToggleRow("Show a door code", draft.doorCodeOn) { onDraft(draft.withDoorCode(it, draft.doorCode)) }
    if (draft.doorCodeOn) {
      GuestDigitPad(draft.doorCode) { onDraft(draft.withDoorCode(true, it)) }
      GuestHint("4 to 8 digits. Hidden on the card until a guest taps Show door code. Stored encrypted.")
    }
  }
}

@Composable
private fun GuestContactChoice(draft: GuestSetupDraft, onDraft: (GuestSetupDraft) -> Unit) {
  GuestSetupGroup("Contact", "How guests can reach you.") {
    GuestChipRow {
      GuestBasics.CONTACTS.forEach { c -> GuestChoiceChip(c.label, draft.settings.contact == c.id) { onDraft(draft.withContact(c.id)) } }
    }
    val name = draft.settings.contactName
    if (name.isNotEmpty()) {
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GuestHint("Questions? Ask $name. (Chosen in Portal Toolkit.)", modifier = Modifier.weight(1f))
        ToolsButton("Remove") { onDraft(draft.clearContactName()) }
      }
    }
  }
}

/** Step 4: the only free text, prefilled, with samples one tap away. */
@Composable
internal fun GuestWelcomeStep(draft: GuestSetupDraft, onDraft: (GuestSetupDraft) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
    ApprovedEditorsField("Welcome message", draft.settings.welcome, { onDraft(draft.withWelcome(it)) },
        placeholder = GuestBasics.DEFAULT_WELCOME)
    GuestHint("${draft.settings.welcome.length} of ${GuestBasics.MAX_WELCOME} characters")
    GuestSetupGroup("Or tap a sample") {
      GuestChipRow {
        GuestBasics.WELCOME_SAMPLES.forEach { sample ->
          GuestChoiceChip(sample, draft.settings.welcome == sample) { onDraft(draft.withWelcome(sample)) }
        }
      }
    }
  }
}
