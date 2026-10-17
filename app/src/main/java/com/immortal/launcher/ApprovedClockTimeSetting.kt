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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.IntSpec

/** A 24-hour dial and a strictly validated keyboard draft share the existing apply boundary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <S> ApprovedClockTimeSetting(
    spec: IntSpec<S>, value: Int, help: String?, enabled: Boolean, onApply: (Int) -> Unit,
) = ApprovedClockTimeSetting(spec.title, value, spec.format(value), help, enabled, onApply)

/** Composite hour/minute settings use the same editor without inventing a persisted scalar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovedClockTimeSetting(
    title: String, value: Int, valueLabel: String, help: String?, enabled: Boolean,
    onApply: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val pageActive = LocalApprovedLauncherPageActive.current
    val canEdit = enabled && pageActive
    val canEditNow by rememberUpdatedState(canEdit)
    val applyNow by rememberUpdatedState(onApply)
    SideEffect { if (!canEdit) open = false }
    NavRow(title, valueLabel, enabled = canEdit) {
        if (canEditNow) open = true
    }
    if (open && canEdit) {
        ApprovedSettingsDialog(title, onDismiss = { open = false }, help = help) {
            // Allocate the selector and draft only while the dialog is visible.
            val clock = rememberTimePickerState(value / 60, value % 60, is24Hour = true)
            var keyboard by remember { mutableStateOf(false) }
            var draft by remember { mutableStateOf(ClockTimeDraft.fromMinutes(value)) }
            var error by remember { mutableStateOf<String?>(null) }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (keyboard) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        OutlinedTextField(
                            value = draft.hour,
                            onValueChange = { draft = draft.copy(hour = it); error = null },
                            label = { Text("Hour") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = iosTextFieldColors(), textStyle = TextStyle(color = IosLabel, fontSize = 26.sp),
                            modifier = Modifier.weight(1f), enabled = canEdit,
                        )
                        OutlinedTextField(
                            value = draft.minute,
                            onValueChange = { draft = draft.copy(minute = it); error = null },
                            label = { Text("Minute") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = iosTextFieldColors(), textStyle = TextStyle(color = IosLabel, fontSize = 26.sp),
                            modifier = Modifier.weight(1f), enabled = canEdit,
                        )
                    }
                } else {
                    TimePicker(state = clock, colors = TimePickerDefaults.colors(
                        clockDialColor = IosCard, selectorColor = IosBlue, containerColor = IosCard,
                        clockDialSelectedContentColor = IosCard, clockDialUnselectedContentColor = IosLabel,
                        periodSelectorBorderColor = IosBlue,
                        periodSelectorSelectedContainerColor = IosFocusTint,
                        periodSelectorUnselectedContainerColor = IosCard,
                        periodSelectorSelectedContentColor = IosLabel,
                        periodSelectorUnselectedContentColor = IosLabel,
                        timeSelectorSelectedContainerColor = IosFocusTint,
                        timeSelectorUnselectedContainerColor = IosCard,
                        timeSelectorSelectedContentColor = IosBlue,
                        timeSelectorUnselectedContentColor = IosLabel,
                    ))
                }
                error?.let { Text(it, color = IosRed, fontSize = Sizes.rowSub) }
                Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    ApprovedSettingsTextButton(if (keyboard) "Clock" else "Keyboard") {
                        if (canEditNow) {
                            if (keyboard) {
                                val minutes = draft.totalMinutesOrNull()
                                if (minutes == null) error = draft.errorOrNull()
                                else {
                                    clock.hour = minutes / 60
                                    clock.minute = minutes % 60
                                    error = null
                                    keyboard = false
                                }
                            } else {
                                draft = ClockTimeDraft.fromMinutes(clock.hour * 60 + clock.minute)
                                error = null
                                keyboard = true
                            }
                        }
                    }
                    ActionPill("Save", enabled = canEdit) {
                        if (canEditNow) {
                            val chosen = if (keyboard) draft.totalMinutesOrNull() else clock.hour * 60 + clock.minute
                            if (chosen == null) error = draft.errorOrNull()
                            else {
                                applyNow(chosen)
                                open = false
                            }
                        }
                    }
                    ApprovedSettingsTextButton("Cancel") { open = false }
                }
            }
        }
    }
}
