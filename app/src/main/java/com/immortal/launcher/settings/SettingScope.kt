/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

/**
 * Where a setting lives. Every registered [SettingSpec] declares one; `SettingScopeTest` fails the
 * build for a spec that doesn't. Household settings sync across the Portals that share the
 * household key ([com.immortal.launcher.sync.SettingsSync]); the other two never leave this Portal.
 * A spec with no scope is treated as [DEVICE] (fail closed).
 */
enum class SettingScope {
  /** The same on every Portal in the home: synced. */
  HOUSEHOLD,

  /** This Portal only (room name, rotation, Shared room, drop-ins, kids mode, hardware). */
  DEVICE,

  /** This Portal only, and a credential (tokens, passwords, keys). Never synced or advertised. */
  DEVICE_SECRET;

  val synced: Boolean
    get() = this == HOUSEHOLD
}

/** The effective scope: an undeclared spec never syncs. */
val SettingSpec<*>.effectiveScope: SettingScope
  get() = scope ?: SettingScope.DEVICE
