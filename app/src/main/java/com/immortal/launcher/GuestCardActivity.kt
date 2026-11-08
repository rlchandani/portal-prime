/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The calm, full-screen Guest card: Wi-Fi QR, network and password, the house basics, and the
 * Guest mode controls. Keeps the screen awake while open and closes itself after
 * [GuestCardScreen]'s idle timeout so the screensaver (neutral while guests are here) can return.
 */
class GuestCardActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.Theme_SampleApp)
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContent { PortalPrimeTheme(darkTheme = false) { GuestCardHost(::finish) } }
  }
}

/** Snapshot the card once per open; the secrets are decrypted off the main thread, never logged. */
@androidx.compose.runtime.Composable
private fun GuestCardHost(onClose: () -> Unit) {
  val context = LocalContext.current.applicationContext
  val card by produceState<GuestCardContent?>(null) {
    value = withContext(Dispatchers.IO) { loadGuestCard(context) }
  }
  val mode by GuestMode.state.collectAsState()
  GuestCardScreen(
      content = card,
      mode = mode,
      onClose = onClose,
      onStart = { schedule -> GuestFeature.start(context, schedule) },
      onEnd = { GuestFeature.end(context); onClose() },
      onEdit = { GuestEntry.openSettings(context); onClose() },
  )
}

/** The card's start choices; schedules resolve in this Portal's time zone ([GuestSchedule]). */
internal object GuestStartChoices {
  val choices: List<Pair<String, GuestSchedule>> = listOf(
      "Until I end it" to GuestSchedule.UntilEnded,
      "Until tomorrow 11:00" to GuestSchedule.UntilTomorrow(11, 0),
      "For 2 days" to GuestSchedule.ForDays(2),
  )
}

/** The saved card with its secrets; call off the main thread. */
internal fun loadGuestCard(context: android.content.Context): GuestCardContent {
  GuestFeature.migrateSecrets(context)
  return GuestCardContent(com.immortal.launcher.settings.GuestSettings.domain.load(context),
      GuestPasswordStore.get(context).read(), GuestDoorCodeStore.get(context).read(), ImmortalSettings.use24HourClock(context))
}
