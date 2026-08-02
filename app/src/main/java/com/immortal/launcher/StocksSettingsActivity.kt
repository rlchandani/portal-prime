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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * Editor for the stocks quote widget: shared Alpaca credentials ([AlpacaCredentialsCard]) plus the
 * tracked-ticker list ([StocksConfig]). Light iOS layout matching the other widget editors.
 */
class StocksSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { StocksSettingsScreen() } }
  }
}

/** Popular tickers for the one-tap add palette. */
private val TICKER_SUGGESTIONS = listOf("AAPL", "MSFT", "NVDA", "GOOGL", "AMZN", "TSLA", "SPY", "QQQ")

@Composable
private fun StocksSettingsScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "Stocks",
      subtitle = "Track stock prices via Alpaca (free key). Data is real-time IEX.",
      onBack = { activity?.finish() },
  ) { StocksSettingsContent() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StocksSettingsContent() {
  val context = LocalContext.current
  var tickers by remember { mutableStateOf(StocksConfig.load(context)) }
  var manual by remember { mutableStateOf("") }

  fun refresh() {
    tickers = StocksConfig.load(context)
  }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    AlpacaCredentialsCard()

    // Current tickers.
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
                    StocksConfig.remove(context, t)
                    refresh()
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

    // Popular tickers palette.
    Card {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Popular", color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          TICKER_SUGGESTIONS.forEach { sym ->
            val added = tickers.contains(sym)
            Surface(
                color = if (added) IosDivider else IosFocusTint,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable(enabled = !added) {
                  StocksConfig.add(context, sym)
                  refresh()
                },
            ) {
              Text(
                  if (added) "$sym ✓" else "+ $sym",
                  color = if (added) IosSecondary else IosBlue,
                  fontSize = 15.sp,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
              )
            }
          }
        }
      }
    }

    // Manual add.
    Card {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add ticker", color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            colors = iosTextFieldColors(),
            value = manual,
            onValueChange = { manual = it },
            label = { Text("Symbol (e.g. AAPL)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Add") {
          StocksConfig.add(context, manual)
          manual = ""
          refresh()
        }
      }
    }
    Spacer(Modifier.size(8.dp))
  }
}

/**
 * Shared Alpaca credentials editor, reused by the stocks and options widgets. Shows key-id + secret
 * fields (secret masked) and a save action; reflects the connected state. Stored via [AlpacaConfig]
 * (plain prefs — market-data keys are read-only). This is a distinct composable so both editors
 * present identical credential UX without duplicating it.
 */
@Composable
internal fun AlpacaCredentialsCard() {
  val context = LocalContext.current
  var keyId by remember { mutableStateOf(AlpacaConfig.keyId(context)) }
  var secret by remember { mutableStateOf(AlpacaConfig.keySecret(context)) }
  var savedTick by remember { mutableStateOf(AlpacaConfig.isConfigured(context)) }

  Card {
    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Alpaca account", color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (savedTick) Text("Connected ✓", color = IosGreen, fontSize = Sizes.rowSub, fontWeight = FontWeight.SemiBold)
      }
      Text(
          "Get free API keys at alpaca.markets → Paper/Live → API Keys. Market-data keys are read-only.",
          color = IosSecondary,
          fontSize = Sizes.rowSub,
      )
      OutlinedTextField(
          colors = iosTextFieldColors(),
          value = keyId,
          onValueChange = { keyId = it },
          label = { Text("API Key ID") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
          colors = iosTextFieldColors(),
          value = secret,
          onValueChange = { secret = it },
          label = { Text("API Secret Key") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth(),
      )
      PrimaryButton(if (savedTick) "Update credentials" else "Save credentials") {
        AlpacaConfig.setCredentials(context, keyId, secret)
        savedTick = AlpacaConfig.isConfigured(context)
      }
    }
  }
}

/** A full-width iOS blue primary action button, shared by the widget editors. */
@Composable
internal fun PrimaryButton(label: String, onClick: () -> Unit) {
  Surface(
      color = IosBlue,
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) { onClick() },
  ) {
    Text(
        label,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
    )
  }
}
