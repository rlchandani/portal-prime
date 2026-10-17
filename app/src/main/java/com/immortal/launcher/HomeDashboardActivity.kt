/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Browser
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Stable launcher identity for the user's dashboard; the browser owns its login and renderer. */
class HomeDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDashboard(this)
        finish()
    }

    companion object {
        fun openDashboard(context: Context): Boolean {
            val url = HomeDashboardConfig.load(context).url
            if (url.isBlank()) {
                context.openApprovedHomeSettings("ha")
                return false
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url).normalizeScheme())
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // The Portal browser already holds the user's session. A separate WebView would
            // require another login and duplicate its frontend, sockets and renderer memory.
            return runCatching {
                try {
                    context.startActivity(intent.setPackage("org.chromium.chrome"))
                } catch (_: ActivityNotFoundException) {
                    // URL handling, not a launcher activity, determines browser eligibility.
                    // A rejected dispatch is safe to fall back; other failures are not retried.
                    context.startActivity(intent.setPackage(null))
                }
                true
            }.getOrElse {
                Toast.makeText(context, "Your dashboard couldn’t be opened.", Toast.LENGTH_LONG).show()
                false
            }
        }
    }
}
