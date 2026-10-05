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


import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

class StocksSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = intent.getStringExtra(APPROVED_SETTINGS_PAGE) == "alpaca"
        setContent {
            PortalPrimeTheme(darkTheme = false) {
                SettingsScaffold(title = if (account) "Alpaca account" else "Stocks", onBack = { finish() }) {
                    if (account) {
                        if (rememberApprovedAdvanced()) ApprovedEditorsAlpacaForm(onCancel = { finish() })
                        else ApprovedSettingsAdvancedGate(LocalApprovedEnableAdvanced.current)
                    } else StocksSettingsContent()
                }
            }
        }
    }
}

private val TICKER_SUGGESTIONS = listOf("AAPL", "MSFT", "NVDA", "GOOGL", "AMZN", "TSLA", "SPY", "QQQ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StocksSettingsContent() {
    val context = LocalContext.current
    var tickers by remember { mutableStateOf(StocksConfig.load(context)) }
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<String?>(null) }
    val selected = remember(tickers) { tickers.toSet() }
    fun refresh() { tickers = StocksConfig.load(context) }
    Column {
        ApprovedEditorsCollection("Your stocks", tickers, key = { it }) { ticker ->
            ApprovedEditorsCollectionRow(ticker, onRemove = { removal = ticker })
        }
        ApprovedEditorsForm(actions = {
            ApprovedEditorsActions(error = error, onCancel = {
                manual = ""; error = null; (context as? Activity)?.finish()
            }) {
                if (manual.isBlank()) error = "Enter a symbol."
                else { StocksConfig.add(context, manual); manual = ""; error = null; refresh() }
            }
        }) {
            ApprovedEditorsField("Symbol", manual, { manual = it; error = null })
        }
        if (rememberApprovedAdvanced()) {
            SectionLabel("Advanced options")
            AlpacaCredentialsCard()
        }
        SectionLabel("Popular choices")
        Card {
            FlowRow(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TICKER_SUGGESTIONS.forEach { symbol ->
                    val added = symbol in selected
                    ApprovedEditorsButton(if (added) "$symbol ✓" else symbol, enabled = !added) {
                        StocksConfig.add(context, symbol); refresh()
                    }
                }
            }
        }
    }
    removal?.let { ticker ->
        ApprovedEditorsRemoveDialog(ticker, onCancel = { removal = null }) {
            StocksConfig.remove(context, ticker); removal = null; refresh()
        }
    }
}

/** Credentials are a rich Activity destination; opening it never mutates the saved pair. */
@Composable
internal fun AlpacaCredentialsCard() {
    val context = LocalContext.current
    if (rememberApprovedAdvanced()) {
        Card {
            NavRow("Alpaca account", value = if (AlpacaConfig.isConfigured(context)) "Credentials saved" else "Set up") {
                context.startActivity(approvedSettingsIntent(context, "alpaca"))
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
