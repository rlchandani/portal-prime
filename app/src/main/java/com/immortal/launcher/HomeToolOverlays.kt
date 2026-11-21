/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.activity.compose.BackHandler
import com.immortal.launcher.ui.theme.ProvideHearthpaneTouchTargets
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared utility content for Home modals and Tools detail workspaces. Home retains its bounded
 * card, title and Close control; [ApprovedToolWorkspace] supplies the page chrome in Tools.
 * Computation and persistence stay in the matching feature objects.
 */

/* ------------------------------------------------------------------ ISS passes */

/* ------------------------------------------------------------------ Aurora */

/* ------------------------------------------------------------------ Speed test */

/* ------------------------------------------------------------------ Converter */

@Composable
internal fun ConverterOverlay(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val categories = remember { Converter.UNIT_CATEGORIES.map { it.first } + "Currency" }
  var category by remember { mutableStateOf(categories.first()) }
  val units =
      remember(category) {
        if (category == "Currency") Converter.CURRENCIES
        else Converter.UNIT_CATEGORIES.first { it.first == category }.second.keys.toList()
      }
  var from by remember(category) { mutableStateOf(units.first()) }
  var to by remember(category) { mutableStateOf(units.getOrElse(1) { units.first() }) }
  var input by remember { mutableStateOf("1") }
  var result by remember { mutableStateOf("") }

  LaunchedEffect(category, from, to, input) {
    val v = input.toDoubleOrNull()
    result =
        when {
          v == null -> ""
          category == "Currency" ->
              withContext(Dispatchers.IO) { Converter.convertCurrency(context, from, to, v) }
                  ?.let { trimNum(it) } ?: "no rate (offline?)"
          else -> trimNum(Converter.convert(category, from, to, v))
        }
  }

  var picker by remember { mutableStateOf<String?>(null) }
  BackHandler { if (picker != null) picker = null else onDismiss() }
  Scrim(onDismiss) {
    ToolCard(onClose = onDismiss) {
      ToolTitle("Converter")
      ToolGroup("Convert") {
        ToolValueRow("Category", category) { picker = "Category" }
        ToolValueRow("From", from) { picker = "From" }
        ToolValueRow("To", to) { picker = "To" }
        Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Value", color = IosLabel, fontSize = 26.sp, modifier = Modifier.weight(1f))
          androidx.compose.material3.OutlinedTextField(value = input, onValueChange = { input = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, color = IosLabel), shape = RoundedCornerShape(16.dp), colors = iosTextFieldColors(), modifier = Modifier.width(320.dp))
        }
      }
      ToolGroup("Result") {
        Text(if (input.toDoubleOrNull() == null) "Enter a valid number." else if (result.isBlank()) "Calculating…" else "$result $to", color = IosLabel, fontSize = 40.sp, lineHeight = 54.sp, modifier = Modifier.padding(28.dp))
      }
    }
  }
  picker?.let { which ->
    ToolDialog(which, { picker = null }) {
      val options = if (which == "Category") categories else units
      options.forEach { option -> ToolValueRow(option, if (option == when (which) { "Category" -> category; "From" -> from; else -> to }) "✓" else "") {
        when (which) { "Category" -> category = option; "From" -> from = option; else -> to = option }; picker = null
      } }
    }
  }
}


/* ------------------------------------------------------------------ Timers */


/* ------------------------------------------------------------------ Leave a note */

/* ------------------------------------------------------------------ small shared bits */

/** Trim a converted number to at most 4 decimals, dropping trailing zeros. */
private fun trimNum(v: Double): String = String.format("%.4f", v).trimEnd('0').trimEnd('.')

/* ------------------------------------------------------------------ shared scaffolding */

/**
 * Full-screen scrim over the home page. Tapping outside the card does NOT dismiss (the tap is
 * swallowed) — closing is only via the card's explicit Close button or Back, so an accidental
 * outside-tap while using a tool can't lose your place. Uses the SAME dim as the Tools folder grid
 * (0x4D / 30% black) so opening a tool from the folder keeps the identical background darkness.
 * Keep this alpha in sync with ToolsScreen's scrim.
 *
 * [onDismiss] is still wired to Back (via each overlay's BackHandler); the scrim itself just
 * consumes taps so nothing behind it (or a fall-through dismiss) fires.
 */
/** Page hosts keep their header/dock; Home callers retain an explicit, bounded modal. */
internal val LocalApprovedToolPage = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
internal fun Scrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
  if (LocalApprovedToolWorkspace.current) {
    content()
    return
  }
  val page = LocalApprovedToolPage.current
  if (!page) KeepInteractiveScreenAwake()
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()
      .background(if (page) IosBackground else Color(0x66000000))) { content() }
}

@Composable
internal fun ToolCard(maxWidth: Int = 860, onClose: (() -> Unit)? = null, content: @Composable () -> Unit) {
  if (LocalApprovedToolWorkspace.current) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) { content() }
    return
  }
  val page = LocalApprovedToolPage.current
  val cfg = androidx.compose.ui.platform.LocalConfiguration.current
  val compact = currentLayoutClass().compact
  if (page) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (compact) 24.dp else 56.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
      if (onClose != null) ApprovedAppsButton("‹  Tools", onClick = onClose)
      content()
    }
  } else {
    Surface(color = IosCard, shape = RoundedCornerShape(28.dp), border = androidx.compose.foundation.BorderStroke(1.dp, IosDivider),
        modifier = if (compact) compactDialogFrame(maxWidthDp = maxWidth, maxHeightDp = cfg.screenHeightDp)
            else Modifier.width((cfg.screenWidthDp * .72f).dp.coerceAtMost(maxWidth.dp)).heightIn(max = (cfg.screenHeightDp * .82f).dp)) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(36.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (onClose != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { CloseCircle(onClose) }
        content()
      }
    }
  }
}

/** Home modals keep their title; Tools detail has one heading supplied by its workspace. */
@Composable
private fun ToolTitle(title: String) {
  if (!LocalApprovedToolWorkspace.current) {
    Text(title, color = IosLabel, fontSize = 44.sp, lineHeight = 56.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun ToolGroup(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
  Text(title, color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(start = 22.dp, bottom = 12.dp))
  Surface(color = IosCard, shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, IosDivider), modifier = Modifier.fillMaxWidth()) { Column(content = content) }
}

@Composable
private fun ToolValueRow(label: String, value: String, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f, onClick = onClick).padding(horizontal = 28.dp, vertical = 21.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = IosLabel, fontSize = 26.sp, lineHeight = 34.sp, modifier = Modifier.weight(1f))
    Text(value, color = IosSecondary, fontSize = 24.sp, lineHeight = 32.sp)
    Text("›", color = IosSecondary, fontSize = 28.sp, modifier = Modifier.padding(start = 22.dp))
  }
}

@Composable
private fun ToolDialog(title: String, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
  if (!LocalApprovedLauncherPageActive.current) return
  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) { ProvideHearthpaneTouchTargets {
    KeepInteractiveScreenAwake()
    val compact = currentLayoutClass().compact
    val frame = if (compact) compactDialogFrame(maxWidthDp = 760, maxHeightDp = 980) else Modifier.width(760.dp).heightIn(max = 980.dp)
    Surface(color = IosCard, shape = RoundedCornerShape(28.dp), border = androidx.compose.foundation.BorderStroke(1.dp, IosDivider), modifier = frame) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(if (compact) 28.dp else 36.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(title, color = IosLabel, fontSize = 34.sp, lineHeight = 46.sp, fontWeight = FontWeight.Medium)
        content()
      }
    }
  }}
}

/**
 * Compact window frame for a centred dialog card: full width less a 24 dp margin, no wider than
 * [maxWidthDp], and no taller than the window less [CompactDialogLayout.VERTICAL_MARGIN_DP] (the
 * Mini turned is only 740 dp tall).
 */
@Composable
internal fun compactDialogFrame(maxWidthDp: Int, maxHeightDp: Int): Modifier {
  val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
  return Modifier.padding(horizontal = CompactDialogLayout.SIDE_MARGIN_DP.dp).fillMaxWidth().widthIn(max = maxWidthDp.dp)
      .heightIn(max = CompactDialogLayout.maxHeightDp(screenHeight, maxHeightDp).dp)
}

/** Pure sizing for compact dialog cards (unit tested); the Portal+ keeps each dialog's fixed size. */
internal object CompactDialogLayout {
  const val SIDE_MARGIN_DP = 24
  /** Kept free above and below a dialog card (40 dp each side). */
  const val VERTICAL_MARGIN_DP = 80
  /** Never shorter than this, even in a tiny split-screen window. */
  const val MIN_HEIGHT_DP = 240

  fun maxHeightDp(screenHeightDp: Int, capDp: Int): Int =
      minOf(capDp, screenHeightDp - VERTICAL_MARGIN_DP).coerceAtLeast(MIN_HEIGHT_DP)
}
