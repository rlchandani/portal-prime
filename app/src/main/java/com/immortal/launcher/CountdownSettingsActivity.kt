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

import java.util.Calendar

class CountdownSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme(darkTheme = false) { CountdownSettingsScreen() } }
    }
}

private val EMOJI_PALETTE = listOf("📅", "🎂", "🎉", "✈️", "❤️", "🎄", "🏖️", "🎓", "💍", "🍼", "⭐", "🏠")

@Composable
private fun CountdownSettingsScreen() {
    val onBack = rememberApprovedSettingsBack()
    SettingsScaffold(title = "Countdowns", onBack = onBack) { CountdownSettingsContent() }
}

@Composable
internal fun CountdownSettingsContent() {
    val context = LocalContext.current
    val onBack = rememberApprovedSettingsBack()
    var events by remember { mutableStateOf(CountdownConfig.loadSorted(context)) }
    var label by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🎂") }
    val initialDate = remember { Calendar.getInstance() }
    var month by remember { mutableIntStateOf(initialDate.get(Calendar.MONTH) + 1) }
    var day by remember { mutableIntStateOf(initialDate.get(Calendar.DAY_OF_MONTH)) }
    var year by remember { mutableIntStateOf(initialDate.get(Calendar.YEAR)) }
    var yearly by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<CountdownConfig.Event?>(null) }
    var iconPicker by remember { mutableStateOf<String?>(null) }
    fun reset() {
        label = ""; emoji = "🎂"; yearly = true; error = null
        month = initialDate.get(Calendar.MONTH) + 1
        day = initialDate.get(Calendar.DAY_OF_MONTH)
        year = initialDate.get(Calendar.YEAR)
    }
    Column {
        ApprovedEditorsCollection("Your countdowns", events, key = { it.id }) { event ->
            ApprovedEditorsCollectionRow("${event.emoji}  ${event.label}",
                "%02d.%02d".format(event.day, event.month) +
                    (if (event.year > 0) ".${event.year}" else " · yearly") + " · ${event.phrase()}",
                onRemove = { removal = event })
        }
        ApprovedEditorsForm(actions = {
            ApprovedEditorsActions(error = error, onCancel = { reset(); onBack() }) {
                if (label.isBlank()) error = "Enter a name."
                else {
                    CountdownConfig.add(context, label, emoji, month, day, if (yearly) 0 else year)
                    label = ""; error = null; events = CountdownConfig.loadSorted(context)
                }
            }
        }) {
            ApprovedEditorsField("Name", label, { label = it; error = null })
            Divider()
            if (!yearly) {
                ApprovedEditorsNumber("Year", year, 2000, 2100, { year = it })
                Divider()
            }
            ApprovedEditorsNumber("Month", month, 1, 12, { month = it }, wrap = true)
            Divider()
            ApprovedEditorsNumber("Day", day, 1, 31, { day = it }, wrap = true)
            Divider()
            NavRow("Icon", value = emoji) { iconPicker = emoji }
            Divider()
            ToggleRow("Repeats every year", yearly, onChange = { yearly = it })
        }
    }
    removal?.let { event ->
        ApprovedEditorsRemoveDialog(event.label, onCancel = { removal = null }) {
            CountdownConfig.remove(context, event.id); removal = null
            events = CountdownConfig.loadSorted(context)
        }
    }
    iconPicker?.let { selected ->
        ApprovedSettingsDialog("Icon", onDismiss = { iconPicker = null }) {
            EMOJI_PALETTE.chunked(6).forEach { icons ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    icons.forEach { icon ->
                        ApprovedEditorsButton(if (icon == selected) "$icon ✓" else icon) {
                            emoji = icon; iconPicker = null
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            ApprovedEditorsButton("Cancel") { iconPicker = null }
        }
    }
}
