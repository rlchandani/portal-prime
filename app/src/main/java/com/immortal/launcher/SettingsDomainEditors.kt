/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.settings.SettingsRegistry
import com.immortal.launcher.ui.components.OnResume
import org.json.JSONObject

/**
 * Current snapshot of one registry domain on a page, plus its apply. Every write goes through
 * the domain's `apply`, so validation and `onApplied` side effects match the phone remote.
 */
internal data class SettingsDomainState(
    val domain: SettingsDomain<Any>,
    val snapshot: Any,
    val editsEnabled: Boolean,
    val apply: (key: String, value: Any) -> Unit,
)

/** Domains that publish remote/background writes, so an open page stays current. */
private fun observeDomain(context: Context, domainId: String, changed: () -> Unit): (() -> Unit)? = when (domainId) {
    "immortal" -> ImmortalSettings.observeSnapshot(context, changed)
    else -> null
}

/** One state per domain the page draws; the screensaver and calendar share the write queue editor. */
@Composable
internal fun rememberSettingsDomainStates(page: String): Map<String, SettingsDomainState> {
    val domains = remember(page) { settingsPageDomains(page) }
    return domains.associateWith { id -> rememberSettingsDomainState(id) }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun rememberSettingsDomainState(domainId: String): SettingsDomainState {
    val domain = requireNotNull(SettingsRegistry.domain(domainId)) { "Unknown settings domain $domainId" }
        as SettingsDomain<Any>
    return if (domainId == SettingsDomains.screensaver.id || domainId == SettingsDomains.calendar.id) {
        rememberScreensaverDomainState(domain)
    } else {
        rememberPlainDomainState(domain)
    }
}

/** Screensaver writes run off the main thread through the existing queue; rows pause while saving. */
@Composable
private fun rememberScreensaverDomainState(domain: SettingsDomain<Any>): SettingsDomainState {
    val editor = rememberScreensaverEditor()
    @Suppress("UNCHECKED_CAST")
    val typed = domain as SettingsDomain<ScreensaverConfig.Settings>
    return SettingsDomainState(domain, editor.settings.value, !editor.applying.value) { key, value ->
        editor.apply(typed, key, value)
    }
}

@Composable
private fun rememberPlainDomainState(domain: SettingsDomain<Any>): SettingsDomainState {
    val application = LocalContext.current.applicationContext
    var snapshot by remember(domain) { mutableStateOf(domain.load(application)) }
    OnResume { snapshot = domain.load(application) }
    DisposableEffect(domain, application) {
        val stop = observeDomain(application, domain.id) { snapshot = domain.load(application) }
        onDispose { stop?.invoke() }
    }
    return SettingsDomainState(domain, snapshot, editsEnabled = true) { key, value ->
        domain.apply(application, JSONObject().put(key, value))
        snapshot = domain.load(application)
    }
}

/** Whether the photo frame screensaver is on, for pages that hide its rows while it is off. */
internal fun Map<String, SettingsDomainState>.screensaverOn(): Boolean =
    (this[SettingsDomains.screensaver.id]?.snapshot as? ScreensaverConfig.Settings)?.enabled ?: true
