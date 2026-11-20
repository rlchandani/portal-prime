/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.TextView

/** View helpers shared by the photo frame's panels: density and the frame's shadowed text style. */
internal class PhotoFrameViews(private val context: Context) {
  fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

  fun text(sizeSp: Float, color: Int, light: Boolean): TextView {
    val t = TextView(context)
    t.textSize = sizeSp
    t.setTextColor(color)
    if (light) t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    t.setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
    return t
  }

  companion object {
    const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
  }
}
