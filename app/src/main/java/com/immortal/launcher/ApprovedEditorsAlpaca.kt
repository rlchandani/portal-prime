/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

@Composable
internal fun ApprovedEditorsAlpacaForm(onCancel: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    Column {
        ApprovedEditorsForm(actions = {
            ApprovedEditorsActions("Save credentials", error, onCancel = {
                key = ""; secret = ""; error = null; onCancel()
            }) {
                val effectiveKey = approvedEditorsCredential(key, AlpacaConfig.keyId(context))
                val effectiveSecret = approvedEditorsCredential(secret, AlpacaConfig.keySecret(context))
                if (effectiveKey.isBlank() || effectiveSecret.isBlank()) {
                    error = "Enter both an API Key ID and an API Secret Key."
                } else {
                    // The Config writes the complete pair in one edit, only after validation.
                    AlpacaConfig.setCredentials(context, effectiveKey, effectiveSecret)
                    key = ""; secret = ""; error = null; saved = true
                }
            }
            if (saved) FooterNote("Credentials saved. Market data will use these keys.")
        }) {
            ApprovedEditorsField("API Key ID", key, { key = it; error = null; saved = false },
                placeholder = if (AlpacaConfig.keyId(context).isBlank()) "Enter a new value"
                    else "Leave blank to keep your saved key", secret = true)
            Divider()
            ApprovedEditorsField("API Secret Key", secret, { secret = it; error = null; saved = false },
                placeholder = if (AlpacaConfig.keySecret(context).isBlank()) "Enter a new value"
                    else "Leave blank to keep your saved secret", secret = true)
        }
        FooterNote("Get API keys at alpaca.markets → Paper/Live → API Keys. " +
            "These credentials are shared by Stocks and Options scanner. Blank fields keep saved values.")
    }
}
