/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.util.Locale

// Generated from frozen Portal+ revision 04 plus separately maintained header glyphs.
// Use the generator to update the catalog; native header drawables are maintained separately.
// Source paths, stroke widths/caps/joins, fill rules and alpha live in the drawables.
// Aliases follow the approved dock, widget, tool and installed-app glyph assignments.
@DrawableRes
internal fun approvedIconResource(name: String): Int = when (name.lowercase(Locale.ROOT)) {
    "aurora store", "aurorastore", "bag",
    "builtin:store", "com.aurora.store", "curated",
    "f-droid", "f-droid store", "fdroid",
    "org.fdroid.fdroid", "store" -> R.drawable.approved_icon_bag
    "battery" -> R.drawable.approved_icon_battery
    "alarm", "bell" -> R.drawable.approved_icon_bell
    "bluetooth", "bt" -> R.drawable.approved_icon_bt
    "bulb", "lamp" -> R.drawable.approved_icon_bulb
    "cal", "calendar", "com.portal.calendar",
    "portalhub", "today" -> R.drawable.approved_icon_cal
    "calc", "calculator", "converter",
    "crypto", "options", "stocks" -> R.drawable.approved_icon_calc
    "back", "chev_l", "chevl",
    "chevronleft" -> R.drawable.approved_icon_chev_l
    "clock", "world_clock", "worldclock" -> R.drawable.approved_icon_clock
    "brightness", "display" -> R.drawable.approved_icon_display
    "camera", "film", "newpipe",
    "org.schabi.newpipe" -> R.drawable.approved_icon_film
    "files", "folder", "material files",
    "me.zhanghai.android.files" -> R.drawable.approved_icon_folder
    "game" -> R.drawable.approved_icon_game
    "builtin:settings", "com.facebook.alohaapps.settings", "gear",
    "portal settings", "settings" -> R.drawable.approved_icon_gear
    "browser", "globe", "iss",
    "news", "org.chromium.chrome", "org.mozilla.firefox" -> R.drawable.approved_icon_globe
    "apps", "grid", "gridapps" -> R.drawable.approved_icon_gridapps
    "hand" -> R.drawable.approved_icon_hand
    "builtin:dashboard", "ha", "home",
    "home assistant", "home dashboard", "homeassistant",
    "io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal" -> R.drawable.approved_icon_home
    "ha_widget", "home_s", "homes" -> R.drawable.approved_icon_home_s
    "countdowns", "hourglass" -> R.drawable.approved_icon_hourglass
    "info" -> R.drawable.approved_icon_info
    "link" -> R.drawable.approved_icon_link
    "lock" -> R.drawable.approved_icon_lock
    "intercom", "megaphone" -> R.drawable.approved_icon_megaphone
    "mic" -> R.drawable.approved_icon_mic
    "moon" -> R.drawable.approved_icon_moonz
    "builtin:music", "music" -> R.drawable.approved_icon_music
    "com.portal.assistant", "jarvis", "notes",
    "stories", "todo" -> R.drawable.approved_icon_notes
    "builtin:calls", "calls", "phone" -> R.drawable.approved_icon_phone
    "photo", "photo_memory", "photos" -> R.drawable.approved_icon_photo
    "play" -> R.drawable.approved_icon_play
    "plug" -> R.drawable.approved_icon_plug
    "plus" -> R.drawable.approved_icon_plus
    "qr" -> R.drawable.approved_icon_qr
    "org.y20k.transistor", "radio", "transistor" -> R.drawable.approved_icon_radio
    "robot" -> R.drawable.approved_icon_robot
    "sat" -> R.drawable.approved_icon_sat
    "search" -> R.drawable.approved_icon_search
    "timer", "timers" -> R.drawable.approved_icon_timer
    "builtin:tools", "quick_controls", "tools",
    "工具" -> R.drawable.approved_icon_tools
    "tv" -> R.drawable.approved_icon_tv
    "org.videolan.vlc", "vlc" -> R.drawable.approved_icon_vlc
    "aurora", "breezy weather", "org.breezyweather",
    "weather" -> R.drawable.approved_icon_weather
    "speed", "wifi" -> R.drawable.approved_icon_wifi
    "close", "x" -> R.drawable.approved_icon_x
    "remote", "smartphone" -> R.drawable.approved_icon_remote
    "moonz", "sleep" -> R.drawable.approved_icon_sleep
    "sun", "theme_day" -> R.drawable.approved_icon_theme_day
    "theme_night" -> R.drawable.approved_icon_theme_night
    "screensaver" -> R.drawable.portal_icon_screensaver
    else -> R.drawable.approved_icon_info
}

/** Decorative glyph; the enclosing action supplies its accessible label. */
@Composable
internal fun ApprovedIcon(
    name: String,
    modifier: Modifier = Modifier.size(30.dp),
    tint: Color = LocalContentColor.current,
): Unit {
    Icon(
        painter = painterResource(approvedIconResource(name)),
        contentDescription = null,
        modifier = modifier,
        tint = tint,
    )
}
