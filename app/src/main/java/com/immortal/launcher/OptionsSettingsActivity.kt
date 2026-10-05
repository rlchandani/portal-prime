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


import kotlin.math.roundToInt

class OptionsSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme(darkTheme = false) { OptionsSettingsScreen() } }
    }
}

@Composable
private fun OptionsSettingsScreen() {
    val activity = LocalContext.current as? Activity
    SettingsScaffold(title = "Options scanner", onBack = { activity?.finish() }) { OptionsSettingsContent() }
}

@Composable
internal fun OptionsSettingsContent() {
    if (!rememberApprovedAdvanced()) {
        ApprovedSettingsAdvancedGate(LocalApprovedEnableAdvanced.current)
        return
    }
    val context = LocalContext.current
    var tickers by remember { mutableStateOf(OptionsScanConfig.tickers(context)) }
    var manual by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(OptionsScanConfig.type(context)) }
    var deltaMin by remember { mutableIntStateOf((OptionsScanConfig.deltaMin(context) * 100).roundToInt()) }
    var deltaMax by remember { mutableIntStateOf((OptionsScanConfig.deltaMax(context) * 100).roundToInt()) }
    var expiry by remember { mutableIntStateOf(OptionsScanConfig.expiryDays(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var removal by remember { mutableStateOf<String?>(null) }
    var typePicker by remember { mutableStateOf<String?>(null) }
    fun restoreDraft() {
        manual = ""; type = OptionsScanConfig.type(context)
        deltaMin = (OptionsScanConfig.deltaMin(context) * 100).roundToInt()
        deltaMax = (OptionsScanConfig.deltaMax(context) * 100).roundToInt()
        expiry = OptionsScanConfig.expiryDays(context); error = null; status = ""
    }
    fun saveScan(): Boolean {
        if (deltaMin !in 1..99 || deltaMax !in 1..99 || expiry !in 1..365) {
            error = "Delta must be 0.01–0.99; expiry must be 1–365 days."
            return false
        }
        // Retain the Config's established sorting of an inverted delta band.
        OptionsScanConfig.setDeltaBand(context, deltaMin / 100.0, deltaMax / 100.0)
        OptionsScanConfig.setType(context, type)
        OptionsScanConfig.setExpiryDays(context, expiry)
        deltaMin = (OptionsScanConfig.deltaMin(context) * 100).roundToInt()
        deltaMax = (OptionsScanConfig.deltaMax(context) * 100).roundToInt()
        error = null; status = "Scan settings saved."
        return true
    }
    Column {
        ApprovedEditorsCollection("Your options scanner", tickers, key = { it }) { ticker ->
            ApprovedEditorsCollectionRow(ticker, onRemove = { removal = ticker })
        }
        ApprovedEditorsForm(actions = {
            ApprovedEditorsActions(error = error, onCancel = {
                restoreDraft(); (context as? Activity)?.finish()
            }) {
                if (manual.isBlank()) error = "Enter a symbol."
                else if (saveScan()) {
                    OptionsScanConfig.addTicker(context, manual); manual = ""
                    tickers = OptionsScanConfig.tickers(context)
                }
            }
            // Scan parameters can also be saved when the existing ticker list needs no changes.
            Row(Modifier.padding(vertical = 12.dp)) {
                ApprovedSettingsTextButton("Save scan settings") { saveScan() }
            }
            if (status.isNotEmpty()) FooterNote(status)
        }) {
            NavRow("Contract type", value = if (type == OptionsScanConfig.TYPE_PUT) "Puts" else "Calls") {
                typePicker = type
            }
            Divider()
            ApprovedEditorsNumber("Delta min", deltaMin, 1, 99, { deltaMin = it; status = "" },
                format = { "%.2f".format(it / 100.0) }, inputHelp = "Use hundredths: 20 means 0.20.")
            Divider()
            ApprovedEditorsNumber("Delta max", deltaMax, 1, 99, { deltaMax = it; status = "" },
                format = { "%.2f".format(it / 100.0) }, inputHelp = "Use hundredths: 30 means 0.30.")
            Divider()
            ApprovedEditorsNumber("Expiry within (days)", expiry, 1, 365, { expiry = it; status = "" }, step = 5)
            Divider()
            ApprovedEditorsField("Symbol", manual, { manual = it; error = null })
        }
        SectionLabel("More options")
        AlpacaCredentialsCard()
        FooterNote("Alpaca free options data is delayed by 15 minutes. Changes apply when you save or add.")
    }
    removal?.let { ticker ->
        ApprovedEditorsRemoveDialog(ticker, onCancel = { removal = null }) {
            OptionsScanConfig.removeTicker(context, ticker); removal = null
            tickers = OptionsScanConfig.tickers(context)
        }
    }
    typePicker?.let {
        ApprovedSettingsDialog("Contract type", onDismiss = { typePicker = null }) {
            listOf(OptionsScanConfig.TYPE_CALL to "Calls", OptionsScanConfig.TYPE_PUT to "Puts")
                .forEach { (value, label) ->
                    SelectableRow(label, "", type == value) { type = value; status = ""; typePicker = null }
                }
            ApprovedEditorsButton("Cancel") { typePicker = null }
        }
    }
}
