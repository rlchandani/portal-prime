/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Generated local UI shared by the native Dream and continuation curtain. */
internal fun pinnedSessionLostPanel(context: Context, goHome: () -> Unit): LinearLayout {
    val d = context.resources.displayMetrics.density
    return LinearLayout(context).apply {
        tag = "pin-session-lost"
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding((48*d).toInt(), (40*d).toInt(), (48*d).toInt(), (40*d).toInt())
        setBackgroundColor(Color.rgb(34, 34, 34))
        addView(TextView(context).apply {
            text = "The pinned app session is no longer available.\nGo Home to open it again."
            textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        })
        addView(Button(context).apply {
            text = "Go Home"
            setOnClickListener { goHome() }
        })
    }
}
