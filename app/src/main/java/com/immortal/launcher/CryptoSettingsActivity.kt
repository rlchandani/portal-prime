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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

class CryptoSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme(darkTheme = false) { CryptoSettingsScreen() } }
    }
}

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
    SettingsScaffold(title = "Crypto", onBack = { activity?.finish() }) { CryptoSettingsContent() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CryptoSettingsContent() {
    val context = LocalContext.current
    var coins by remember { mutableStateOf(CryptoConfig.load(context)) }
    var coinId by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<CryptoConfig.Coin?>(null) }
    val selectedIds = remember(coins) { coins.map { it.id }.toSet() }
    fun refresh() { coins = CryptoConfig.load(context) }
    Column {
        ApprovedEditorsCollection("Your crypto", coins, key = { it.id }) { coin ->
            ApprovedEditorsCollectionRow(coin.symbol, coin.id, onRemove = { removal = coin })
        }
        ApprovedEditorsForm(actions = {
            ApprovedEditorsActions(error = error, onCancel = {
                coinId = ""; symbol = ""; error = null; (context as? Activity)?.finish()
            }) {
                if (coinId.isBlank()) error = "Enter a CoinGecko id."
                else {
                    CryptoConfig.add(context, coinId, approvedEditorsCryptoSymbol(coinId, symbol))
                    coinId = ""; symbol = ""; error = null; refresh()
                }
            }
        }) {
            ApprovedEditorsField("CoinGecko id", coinId, { coinId = it; error = null })
            Divider()
            ApprovedEditorsField("Display symbol (optional)", symbol, { symbol = it })
        }
        SectionLabel("Popular choices")
        Card {
            FlowRow(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                COIN_SUGGESTIONS.forEach { (id, sym) ->
                    val added = id in selectedIds
                    ApprovedEditorsButton(if (added) "$sym ✓" else sym, enabled = !added) {
                        CryptoConfig.add(context, id, sym); refresh()
                    }
                }
            }
        }
    }
    removal?.let { coin ->
        ApprovedEditorsRemoveDialog(coin.symbol, onCancel = { removal = null }) {
            CryptoConfig.remove(context, coin.id); removal = null; refresh()
        }
    }
}
