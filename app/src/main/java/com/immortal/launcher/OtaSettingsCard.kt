/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val OTA_CARD_GAP = 20.dp

/**
 * "Hearthpane 1.228 · Up to date · Checked 3:12 PM · [Check for updates]" at the top of About &
 * device health. "Update now" installs at once (idle rules skipped, integrity checks kept).
 */
@Composable
internal fun OtaSettingsCard() {
  val context = LocalContext.current.applicationContext
  val state by OtaUpdater.state.collectAsState()
  LaunchedEffect(context) { withContext(Dispatchers.IO) { OtaUpdater.refresh(context) } }
  val checked = state.lastCheckAtMs?.let {
    DateUtils.formatSameDayTime(it, System.currentTimeMillis(), DateFormat.MEDIUM, DateFormat.SHORT).toString()
  }
  val copy = OtaCopy.row(state, checked)
  Card {
    ActionSettingRow(copy.title, copy.subtitle, copy.action, enabled = copy.enabled) {
      val trigger = if (copy.action == OtaCopy.UPDATE_NOW) OtaTrigger.INSTALL else OtaTrigger.CHECK
      OtaUpdater.submit(context, trigger)
    }
  }
  Spacer(Modifier.size(OTA_CARD_GAP))
}
