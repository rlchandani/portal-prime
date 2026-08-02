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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * Editor for the crypto price widget's tracked-coin list. Mirrors [CountdownSettingsActivity]'s
 * light iOS layout: a card of current coins (tap ✕ to remove) plus an add form with a palette of
 * popular coins (so the user doesn't have to know CoinGecko ids) and a manual id/symbol entry.
 */
class CryptoSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { CryptoSettingsScreen() } }
  }
}

/** Popular coins as (CoinGecko id, display symbol) pairs for the one-tap add palette. */
private val COIN_SUGGESTIONS =
    listOf(
        "bitcoin" to "BTC",
        "ethereum" to "ETH",
        "solana" to "SOL",
        "cardano" to "ADA",
        "ripple" to "XRP",
        "dogecoin" to "DOGE",
        "polkadot" to "DOT",
        "chainlink" to "LINK",
        "litecoin" to "LTC",
        "avalanche-2" to "AVAX",
    )

@Composable
private fun CryptoSettingsScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "Crypto",
      subtitle = "Track coin prices on the home screen. Prices are from CoinGecko (no account needed).",
      onBack = { activity?.finish() },
  ) { CryptoSettingsContent() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CryptoSettingsContent() {
  val context = LocalContext.current
  var coins by remember { mutableStateOf(CryptoConfig.load(context)) }
  var manualId by remember { mutableStateOf("") }
  var manualSymbol by remember { mutableStateOf("") }

  fun refresh() {
    coins = CryptoConfig.load(context)
  }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    // Current coins.
    if (coins.isNotEmpty()) {
      Card {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
          coins.forEach { c ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(c.symbol, color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
                Text(c.id, color = IosSecondary, fontSize = Sizes.rowSub)
              }
              Box(
                  modifier =
                      Modifier.size(40.dp).clickable {
                        CryptoConfig.remove(context, c.id)
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

    // Add-from-palette.
    Card {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Popular coins", color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          COIN_SUGGESTIONS.forEach { (id, sym) ->
            val added = coins.any { it.id == id }
            Surface(
                color = if (added) IosDivider else IosFocusTint,
                shape = RoundedCornerShape(12.dp),
                modifier =
                    Modifier.clickable(enabled = !added) {
                      CryptoConfig.add(context, id, sym)
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

    // Manual add (any CoinGecko id).
    Card {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add by CoinGecko id", color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            colors = iosTextFieldColors(),
            value = manualId,
            onValueChange = { manualId = it },
            label = { Text("CoinGecko id (e.g. bitcoin)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            colors = iosTextFieldColors(),
            value = manualSymbol,
            onValueChange = { manualSymbol = it },
            label = { Text("Display symbol (e.g. BTC)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Surface(
            color = IosBlue,
            shape = RoundedCornerShape(14.dp),
            modifier =
                Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) {
                  CryptoConfig.add(context, manualId, manualSymbol)
                  manualId = ""
                  manualSymbol = ""
                  refresh()
                },
        ) {
          Text(
              "Add coin",
              color = Color.White,
              fontSize = 18.sp,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
          )
        }
      }
    }
    Spacer(Modifier.size(8.dp))
  }
}
