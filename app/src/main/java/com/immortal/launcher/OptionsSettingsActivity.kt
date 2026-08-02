/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlin.math.roundToInt

/**
 * Editor for the options scanner widget: shared Alpaca credentials, plus the scan parameters —
 * call/put, delta band (default 0.20–0.30), expiry window, and the underlyings to scan.
 */
class OptionsSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { OptionsSettingsScreen() } }
  }
}

@Composable
private fun OptionsSettingsScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "Options scanner",
      subtitle = "Show option contracts in a delta band across your tickers. Alpaca free data is 15-min delayed.",
      onBack = { activity?.finish() },
  ) { OptionsSettingsContent() }
}

@Composable
internal fun OptionsSettingsContent() {
  val context = LocalContext.current
  var tickers by remember { mutableStateOf(OptionsScanConfig.tickers(context)) }
  var manual by remember { mutableStateOf("") }
  var type by remember { mutableStateOf(OptionsScanConfig.type(context)) }
  // Delta band stored as ints of hundredths (20 = 0.20) for easy stepping.
  var deltaMinPct by remember { mutableStateOf((OptionsScanConfig.deltaMin(context) * 100).roundToInt()) }
  var deltaMaxPct by remember { mutableStateOf((OptionsScanConfig.deltaMax(context) * 100).roundToInt()) }
  var expiryDays by remember { mutableStateOf(OptionsScanConfig.expiryDays(context)) }

  fun persistBand() = OptionsScanConfig.setDeltaBand(context, deltaMinPct / 100.0, deltaMaxPct / 100.0)

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    AlpacaCredentialsCard()

    // Scan parameters.
    Card {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Scan settings", color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)

        // Call / Put segmented toggle.
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Contract type", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
          TypePill("Calls", type == OptionsScanConfig.TYPE_CALL) {
            type = OptionsScanConfig.TYPE_CALL
            OptionsScanConfig.setType(context, type)
          }
          Spacer(Modifier.size(8.dp))
          TypePill("Puts", type == OptionsScanConfig.TYPE_PUT) {
            type = OptionsScanConfig.TYPE_PUT
            OptionsScanConfig.setType(context, type)
          }
        }

        OptionStepper("Delta min", "%.2f".format(deltaMinPct / 100.0)) {
          deltaMinPct = (deltaMinPct + it).coerceIn(1, 99)
          persistBand()
        }
        OptionStepper("Delta max", "%.2f".format(deltaMaxPct / 100.0)) {
          deltaMaxPct = (deltaMaxPct + it).coerceIn(1, 99)
          persistBand()
        }
        OptionStepper("Expiry within (days)", "$expiryDays") {
          expiryDays = (expiryDays + it * 5).coerceIn(1, 365)
          OptionsScanConfig.setExpiryDays(context, expiryDays)
        }
      }
    }

    // Tickers to scan.
    if (tickers.isNotEmpty()) {
      Card {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
          tickers.forEach { t ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(t, color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
              Box(
                  modifier = Modifier.size(40.dp).clickable {
                    OptionsScanConfig.removeTicker(context, t)
                    tickers = OptionsScanConfig.tickers(context)
                  },
                  contentAlignment = Alignment.Center,
              ) {
                Text("✕", color = IosRed, fontSize = 20.sp)
              }
            }
          }
        }
      }
    }

    Card {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add ticker to scan", color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            colors = iosTextFieldColors(),
            value = manual,
            onValueChange = { manual = it },
            label = { Text("Symbol (e.g. AAPL)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Add") {
          OptionsScanConfig.addTicker(context, manual)
          manual = ""
          tickers = OptionsScanConfig.tickers(context)
        }
      }
    }
    Spacer(Modifier.size(8.dp))
  }
}

/** A small call/put selector pill. */
@Composable
private fun TypePill(label: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
      color = if (selected) IosBlue else IosDivider,
      shape = RoundedCornerShape(10.dp),
      modifier = Modifier.clickable { onClick() },
  ) {
    Text(
        label,
        color = if (selected) Color.White else IosLabel,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
  }
}

/** A ±stepper row for a scalar scan parameter (reads its display value pre-formatted). */
@Composable
private fun OptionStepper(label: String, value: String, onChange: (Int) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
    Box(modifier = Modifier.size(48.dp).clickable { onChange(-1) }, contentAlignment = Alignment.Center) {
      Text("◀", color = IosBlue, fontSize = 20.sp)
    }
    Text(
        value,
        color = IosLabel,
        fontSize = Sizes.rowValue,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 56.dp),
    )
    Box(modifier = Modifier.size(48.dp).clickable { onChange(1) }, contentAlignment = Alignment.Center) {
      Text("▶", color = IosBlue, fontSize = 20.sp)
    }
  }
}
