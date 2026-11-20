/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Pure wording for the Paired phones list. */
internal object PairedPhoneCopy {
  private const val HOUR_MS = 60L * 60_000L
  private const val DAY_MS = 24 * HOUR_MS

  /**
   * "Used in the last hour" / "Used 3 h ago" / "Used 12 days ago". Last use is stored at most
   * hourly ([RemoteSessionPolicy.SEEN_WRITE_INTERVAL_MS]), so nothing finer is claimed.
   */
  fun lastSeen(lastSeenMs: Long, now: Long): String {
    val age = (now - lastSeenMs).coerceAtLeast(0L)
    return when {
      age < HOUR_MS -> "Used in the last hour"
      age < 2 * HOUR_MS -> "Used 1 h ago"
      age < DAY_MS -> "Used ${age / HOUR_MS} h ago"
      age / DAY_MS == 1L -> "Used 1 day ago"
      else -> "Used ${age / DAY_MS} days ago"
    }
  }

  /** Whole days left before an unused phone has to pair again. */
  fun expiresIn(lastSeenMs: Long, now: Long): String {
    val left = ((lastSeenMs + RemoteSessionPolicy.IDLE_EXPIRY_MS - now) / DAY_MS).coerceAtLeast(0L)
    return if (left == 1L) "pairing ends in 1 day unless used" else "pairing ends in $left days unless used"
  }

  fun subtitle(record: RemoteSessionRecord, now: Long): String =
      "${lastSeen(record.lastSeenMs, now)} · ${expiresIn(record.lastSeenMs, now)}"
}

/**
 * Settings › Phone Remote › Paired phones: every phone session with its last use and a Remove
 * action (tap twice to confirm). A removed phone gets 401 and shows "Pair again".
 */
@Composable
internal fun PairedPhonesSection() {
  val context = LocalContext.current
  var refresh by remember { mutableStateOf(0) }
  val pinChanges by RemotePairing.pinChanges.collectAsState()
  var confirming by remember { mutableStateOf<String?>(null) }
  val now = System.currentTimeMillis()
  val phones = remember(refresh, pinChanges) { RemotePairing.sessions(context, now) }
  LaunchedEffect(refresh) {
    delay(REFRESH_MS) // last-seen times move while the page is open
    refresh++
  }
  SectionLabel("Paired phones")
  Card {
    if (phones.isEmpty()) {
      NavRow("No phones paired", subLabel = "Show a pairing code to connect one.", enabled = false) {}
    }
    phones.forEachIndexed { index, phone ->
      if (index > 0) Divider()
      val confirm = confirming == phone.id
      ActionSettingRow(
          title = phone.label,
          subtitle = PairedPhoneCopy.subtitle(phone, now),
          actionLabel = if (confirm) "Confirm" else "Remove",
      ) {
        if (confirm) {
          RemotePairing.revokeSession(context, phone.id)
          confirming = null
          refresh++
        } else confirming = phone.id
      }
    }
  }
  FooterNote("A phone that isn't used for 180 days has to pair again.")
  Spacer(Modifier.size(8.dp))
}

private const val REFRESH_MS = 60_000L
