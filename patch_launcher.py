#!/usr/bin/env python3
"""
Replaces the body of LauncherScreen (lines 372-1243) with a premium dark
dashboard, and inserts five private helper composables immediately after it.
"""

import sys

SRC = "/Users/rohit/portal-prime/app/src/main/java/com/immortal/launcher/HomeActivity.kt"

NEW_BODY = """\
) {
  val context = androidx.compose.ui.platform.LocalContext.current

  // --- live time/date (1-second tick) ---
  val now by produceState(initialValue = Date()) {
    while (true) {
      delay(1000)
      value = Date()
    }
  }

  // --- live weather (30-min refresh, 1-min retry on failure) ---
  val weatherCurrent by produceState<Weather.Current?>(initialValue = null) {
    while (true) {
      val w = withContext(Dispatchers.IO) { Weather.fetchCurrent(context) }
      if (w != null) {
        value = w
        delay(30L * 60 * 1000)
      } else {
        delay(60L * 1000)
      }
    }
  }

  // --- battery (live via sticky broadcast) ---
  val battery = batteryState()

  // --- time-of-day derived from ticking now ---
  val hour = SimpleDateFormat("H", Locale.getDefault()).format(now).toIntOrNull() ?: 12
  val greetingWord = when {
    hour in 5..11  -> "Good morning"
    hour in 12..16 -> "Good afternoon"
    hour in 17..21 -> "Good evening"
    else           -> "Good night"
  }
  val timeOfDayLabel = when {
    hour in 5..11  -> "Morning"
    hour in 12..16 -> "Afternoon"
    hour in 17..21 -> "Evening"
    else           -> "Night"
  }

  // --- formatted strings ---
  val dayDateString = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(now)
      .uppercase(Locale.getDefault())
  val timeString = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
  val fullDate   = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)

  // --- local dashboard palette ---
  val textPrimary   = Color(0xFFF0F0F5)
  val textSecondary = Color(0xFF8888A0)
  val textMuted     = Color(0xFF55556A)
  val accentGreen   = Color(0xFF30D158)
  val accentAmber   = Color(0xFFFF9F0A)
  val accentBlue    = Color(0xFF0A84FF)
  val batteryColor  = when {
    battery.charging    -> accentGreen
    battery.percent <= 15 -> Color(0xFFFF453A)
    else                -> textPrimary
  }

  Box(
      modifier = Modifier
          .fillMaxSize()
          .background(Brush.verticalGradient(listOf(Color(0xFF0F0F12), Color(0xFF111116))))
  ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 18.dp)
    ) {
      // ── TOP BAR ──────────────────────────────────────────────────────────────
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(
              text = dayDateString,
              color = textMuted,
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold,
              letterSpacing = 1.5.sp,
          )
          Spacer(Modifier.size(4.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$greetingWord, ",
                color = textPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Rohit",
                color = textSecondary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
          }
        }
        Column(horizontalAlignment = Alignment.End) {
          Text(
              text = timeString,
              color = textPrimary,
              fontSize = 30.sp,
              fontWeight = FontWeight.Light,
          )
          val wc = weatherCurrent
          if (wc != null) {
            Text(
                text = "${wc.city} · ${wc.temp}° · ${Weather.emoji(wc.code)}",
                color = textSecondary,
                fontSize = 13.sp,
            )
          }
        }
      }

      Spacer(Modifier.size(12.dp))

      // ── STATUS PILLS ─────────────────────────────────────────────────────────
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        weatherCurrent?.let { wc ->
          DashStatusPill(text = "${Weather.emoji(wc.code)} ${wc.temp}°", accentColor = accentAmber)
        }
        if (battery.present) {
          DashStatusPill(text = "${battery.percent}%", accentColor = batteryColor)
        }
        DashStatusPill(text = timeOfDayLabel, accentColor = accentBlue)
      }

      Spacer(Modifier.size(20.dp))
      androidx.compose.material3.HorizontalDivider(
          color = Color(0xFF2A2A35),
          thickness = 1.dp,
          modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.size(20.dp))

      // ── HERO ROW — 3 large cards, asymmetric weights ──────────────────────────
      Row(
          modifier = Modifier.fillMaxWidth().height(200.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        DashHeroCard(
            modifier    = Modifier.weight(1.4f).fillMaxHeight(),
            gradient    = listOf(Color(0xFF1F2937), Color(0xFF111827)),
            accentColor = accentAmber,
            icon        = "📅",
            title       = "PortalHub",
            subtitle    = "Calendar & Family",
            onClick     = {
              runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage("com.immortal.hub")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
            },
        )
        DashHeroCard(
            modifier    = Modifier.weight(1.2f).fillMaxHeight(),
            gradient    = listOf(Color(0xFF1A1030), Color(0xFF0D0820)),
            accentColor = Color(0xFFBF5AF2),
            icon        = "🤖",
            title       = "Jarvis",
            subtitle    = "AI Assistant",
            onClick     = {
              runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage("com.immortal.jarvis")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
            },
        )
        DashHeroCard(
            modifier    = Modifier.weight(1.0f).fillMaxHeight(),
            gradient    = listOf(Color(0xFF0C1A2E), Color(0xFF060D18)),
            accentColor = accentBlue,
            icon        = "📞",
            title       = "Calls",
            subtitle    = "WhatsApp & Messenger",
            onClick     = { onExitHome() },
        )
      }

      Spacer(Modifier.size(16.dp))

      // ── QUICK ACTIONS ─────────────────────────────────────────────────────────
      DashSectionLabel("QUICK ACTIONS")
      Spacer(Modifier.size(8.dp))
      Row(
          modifier = Modifier.fillMaxWidth().height(90.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        DashQuickTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon     = "📦",
            label    = "App Store",
            onClick  = { onOpenStore() },
        )
        DashQuickTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon     = "🌐",
            label    = "Browser",
            onClick  = {
              runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage("org.chromium.chrome")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
            },
        )
        DashQuickTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon     = "🔧",
            label    = "Tools",
            onClick  = {
              runCatching {
                context.startActivity(Intent(context, ToolsActivity::class.java))
              }
            },
        )
        DashQuickTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon     = "⚙️",
            label    = "Settings",
            onClick  = {
              runCatching {
                context.startActivity(Intent(context, ImmortalSettingsActivity::class.java))
              }
            },
        )
      }

      Spacer(Modifier.size(16.dp))

      // ── BOTTOM ROW — fills remaining space ────────────────────────────────────
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        DashCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
          Column(modifier = Modifier.padding(16.dp)) {
            DashSectionLabel("TODAY")
            Spacer(Modifier.size(6.dp))
            Text(
                text = fullDate,
                color = textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = SimpleDateFormat("EEEE", Locale.getDefault()).format(now),
                color = textSecondary,
                fontSize = 13.sp,
            )
          }
        }
        DashCard(modifier = Modifier.weight(1.5f).fillMaxHeight()) {
          Column(modifier = Modifier.padding(16.dp)) {
            DashSectionLabel("NOW PLAYING")
            Spacer(Modifier.size(6.dp))
            Text(
                text = "Nothing playing",
                color = textMuted,
                fontSize = 14.sp,
            )
          }
        }
        DashCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
          Column(modifier = Modifier.padding(16.dp)) {
            DashSectionLabel("SYSTEM")
            Spacer(Modifier.size(6.dp))
            Text(
                text = "Up to date",
                color = accentGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "All apps current",
                color = textMuted,
                fontSize = 11.sp,
            )
          }
        }
      }
    }
  }
}
"""

HELPERS = """
// ─────────────────────────────────────────────────────────────────────────────
// Dashboard helper composables — used by LauncherScreen's premium dark layout
// ─────────────────────────────────────────────────────────────────────────────

/** Dark rounded card surface for the bottom-row tiles. */
@Composable
private fun DashCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Box(
      modifier = modifier
          .clip(RoundedCornerShape(18.dp))
          .background(Color(0xFF1C1C22))
          .border(1.dp, Color(0xFF2A2A35).copy(alpha = 0.7f), RoundedCornerShape(18.dp)),
  ) {
    content()
  }
}

/** Small caps section label (11sp, muted, letter-spaced). */
@Composable
private fun DashSectionLabel(text: String) {
  Text(
      text = text.uppercase(Locale.getDefault()),
      color = Color(0xFF55556A),
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 1.5.sp,
  )
}

/** Coloured pill badge for status indicators (weather, battery, time-of-day). */
@Composable
private fun DashStatusPill(text: String, accentColor: Color) {
  Box(
      modifier = Modifier
          .clip(RoundedCornerShape(50))
          .background(Color(0xFF252530))
          .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(50))
          .padding(horizontal = 12.dp, vertical = 5.dp),
  ) {
    Text(
        text = text,
        color = accentColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
  }
}

/** Gradient hero card for primary app shortcuts (PortalHub, Jarvis, Calls). */
@Composable
private fun DashHeroCard(
    modifier: Modifier = Modifier,
    gradient: List<Color>,
    accentColor: Color,
    icon: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
  Box(
      modifier = modifier
          .clip(RoundedCornerShape(18.dp))
          .background(Brush.verticalGradient(gradient))
          .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
          .clickable(onClick = onClick),
  ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(text = icon, fontSize = 36.sp)
      Column {
        Text(
            text = title,
            color = Color(0xFFF0F0F5),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = subtitle,
            color = Color(0xFF8888A0),
            fontSize = 12.sp,
        )
        Spacer(Modifier.size(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(accentColor.copy(alpha = 0.6f)),
        )
      }
    }
  }
}

/** Square quick-action tile (App Store, Browser, Tools, Settings). */
@Composable
private fun DashQuickTile(
    modifier: Modifier = Modifier,
    icon: String,
    label: String,
    onClick: () -> Unit,
) {
  Box(
      modifier = modifier
          .clip(RoundedCornerShape(14.dp))
          .background(Color(0xFF1C1C22))
          .border(1.dp, Color(0xFF2A2A35).copy(alpha = 0.7f), RoundedCornerShape(14.dp))
          .clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(text = icon, fontSize = 26.sp)
      Text(
          text = label,
          color = Color(0xFF8888A0),
          fontSize = 11.sp,
          fontWeight = FontWeight.Medium,
      )
    }
  }
}
"""

def main():
    with open(SRC, "r", encoding="utf-8") as f:
        lines = f.readlines()

    total = len(lines)
    print(f"File has {total} lines.")

    # Verify the expected boundaries (1-indexed).
    # Line 371 must be ") {"  (function open brace)
    # Line 1243 must be "}"   (function close brace)
    open_line = lines[370].rstrip()   # 0-indexed 370 == 1-indexed 371
    close_line = lines[1242].rstrip() # 0-indexed 1242 == 1-indexed 1243
    print(f"Line 371: {repr(open_line)}")
    print(f"Line 1243: {repr(close_line)}")

    if open_line != ") {":
        print("ERROR: line 371 is not ') {' — aborting.", file=sys.stderr)
        sys.exit(1)
    if close_line != "}":
        print("ERROR: line 1243 is not '}' — aborting.", file=sys.stderr)
        sys.exit(1)

    # Build the new file:
    #   lines[0..369]   = unchanged (up to and including ") {" signature close)
    #   NEW_BODY        = new function body (replaces lines[370..1242])
    #   HELPERS         = new private composables (inserted after closing })
    #   lines[1243..]   = unchanged (TimerAlarmOverlay onwards)
    before   = lines[:370]           # lines 1-370 (keeps ") {" from sig as part of new body)
    after    = lines[1243:]          # lines 1244+ (TimerAlarmOverlay etc.)

    # NEW_BODY already starts with ") {" and ends with "}\n"
    new_file_content = (
        "".join(before)
        + NEW_BODY
        + "\n"
        + HELPERS
        + "\n"
        + "".join(after)
    )

    with open(SRC, "w", encoding="utf-8") as f:
        f.write(new_file_content)

    with open(SRC, "r", encoding="utf-8") as f:
        new_lines = f.readlines()
    print(f"Done. File now has {len(new_lines)} lines.")

if __name__ == "__main__":
    main()
