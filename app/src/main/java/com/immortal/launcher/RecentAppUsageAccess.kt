/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** Read the caller's usage-access grant; never change permission or app-op state. */
internal object RecentAppUsageAccess {
    @Suppress("DEPRECATION") // checkOpNoThrow supports every Portal and minSdk 24.
    fun allowed(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return@runCatching false
        when (manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                context.applicationInfo.uid, context.packageName)) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }.getOrDefault(false)

    /** Prefer this app's settings page, with one generic fallback for firmware differences. */
    fun open(context: Context): Boolean {
        if (runCatching {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess) return true
        return runCatching {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}
