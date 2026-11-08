/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.immortal.launcher.HomeWidgetStore.Slot

/** Renders a widget kind's picker/draft preview at a given stored span. */
internal typealias HomeWidgetPreviewContent = @Composable (kind: String, span: Pair<Int, Int>, modifier: Modifier) -> Unit

/** A widget being placed on the real page; nothing is saved until Add. */
internal data class HomeAddDraft(
    val kind: String,
    /** Flattened provider component for Android app widgets; null for Immortal widgets. */
    val providerKey: String?,
    val label: String,
    val image: ImageBitmap?,
    val tapped: Slot,
    val span: Pair<Int, Int>,
    val origin: Slot,
)

/**
 * Customize's add flow (approved 2026-11-07): empty slot → picker → draft on the page → Add.
 * Held by the launcher for the Customize session; reset when Customize closes.
 */
@Stable
internal class HomeAddFlowState {
  /** The tapped empty slot while the picker sheet is open. */
  var pickerSlot by mutableStateOf<Slot?>(null)
  var draft by mutableStateOf<HomeAddDraft?>(null)
  /** Key of the widget just added; draws a brief accent ring. */
  var ring by mutableStateOf<String?>(null)
  /** "+ new page" was tapped: Customize shows one empty page after the last. */
  var newPage by mutableStateOf(false)
  /** Measured size of one slot on the page, so picker previews render at slot size. */
  var slotSize by mutableStateOf(DpSize.Unspecified)

  /** The page the flow is working on, if any. */
  val targetPage: Int? get() = draft?.origin?.page ?: pickerSlot?.page

  fun openPicker(slot: Slot) { draft = null; pickerSlot = slot }

  /** Starts a draft in the picker's slot at its natural shape (or the first that fits). */
  fun choose(widgets: List<HomeWidgetStore.HomeWidget>, kind: String, providerKey: String?, label: String,
      image: ImageBitmap?, natural: Pair<Int, Int>? = null): Boolean {
    val slot = pickerSlot ?: return false
    val fitting = HomeSlots.fittingShapes(widgets, slot)
    val span = natural?.takeIf { it in fitting } ?: HomeWidgetPicker.initialShape(kind, fitting) ?: return false
    val origin = HomeSlots.placementFor(widgets, slot, span) ?: return false
    draft = HomeAddDraft(kind, providerKey, label, image, slot, span, origin)
    pickerSlot = null
    return true
  }

  fun reshape(widgets: List<HomeWidgetStore.HomeWidget>, span: Pair<Int, Int>) {
    val current = draft ?: return
    val origin = HomeSlots.placementFor(widgets, current.tapped, span) ?: return
    draft = current.copy(span = span, origin = origin)
  }

  fun backToPicker() { pickerSlot = draft?.tapped; draft = null }

  fun added(key: String) { draft = null; pickerSlot = null; newPage = false; ring = key }

  fun reset() { pickerSlot = null; draft = null; ring = null; newPage = false }
}

internal val LocalHomeAddFlow = staticCompositionLocalOf<HomeAddFlowState?> { null }

private val CardShape = RoundedCornerShape(24.dp)

/**
 * The flow's overlay on one page, in the grid's own geometry: dashed "+" outlines on [emptySlots],
 * the dimmed page with the outlined draft, and the brief ring after Add.
 */
@Composable
internal fun BoxScope.HomeAddDecor(
    geometry: ApprovedSlotGeometry,
    flow: HomeAddFlowState,
    pageIndex: Int,
    emptySlots: List<Slot>,
    preview: HomeWidgetPreviewContent,
) {
  val size = geometry.slotSize
  SideEffect { if (flow.slotSize != size) flow.slotSize = size }
  val draft = flow.draft
  if (draft == null) emptySlots.forEach { slot ->
    key(slot) { HomeEmptySlot(geometry.slot(slot, 1 to 2)) { flow.openPicker(slot) } }
  }
  if (draft != null && draft.origin.page == pageIndex) {
    // The rest of the page dims; taps on it do nothing while placing.
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .55f))
        .pointerInput(Unit) { detectTapGestures { } })
    val accent = ApprovedHomePalette.Accent
    ApprovedHomeSurface(geometry.slot(draft.origin, draft.span).border(3.dp, accent, CardShape)
        .semantics { contentDescription = "${draft.label} draft" }) {
      if (draft.providerKey != null) HomeAppWidgetPlaceholder(draft.label, draft.image)
      else HomePreviewFrame { preview(draft.kind, draft.span, Modifier.fillMaxSize()) }
    }
  }
  flow.ring?.let { key -> geometry.widget(key)?.let { Box(it.border(4.dp, ApprovedHomePalette.Accent, CardShape)) } }
}

/** A quiet dashed outline with a "+" — every empty slot in Customize. */
@Composable
private fun HomeEmptySlot(modifier: Modifier, onClick: () -> Unit) {
  val line = ApprovedHomePalette.Secondary.copy(alpha = .5f)
  Box(modifier.semantics { contentDescription = "Add a widget here"; role = Role.Button }
      .tvFocusable(CardShape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick),
      contentAlignment = Alignment.Center) {
    Canvas(Modifier.matchParentSize()) {
      val stroke = 2.dp.toPx()
      drawRoundRect(line, topLeft = Offset(stroke / 2, stroke / 2),
          size = Size(size.width - stroke, size.height - stroke),
          cornerRadius = CornerRadius(24.dp.toPx()),
          style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))))
    }
    Box(Modifier.size(72.dp).clip(CircleShape).background(ApprovedHomePalette.Chrome),
        contentAlignment = Alignment.Center) {
      ApprovedHomeIcon("plus", Modifier.size(30.dp), ApprovedHomePalette.Secondary)
    }
  }
}

/** Preview content is display-only: no header actions, no semantics, no touches. */
@Composable
private fun HomePreviewFrame(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
    CompositionLocalProvider(LocalWidgetEditing provides true) { content() }
    Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
  }
}

@Composable
private fun HomeAppWidgetPlaceholder(label: String, image: ImageBitmap?) {
  Column(Modifier.fillMaxSize().padding(30.dp), verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally) {
    if (image != null) Image(image, contentDescription = null, modifier = Modifier.size(64.dp))
    else ApprovedHomeIcon("apps", Modifier.size(64.dp), ApprovedHomePalette.Accent)
    Text(label, color = ApprovedHomePalette.Text, fontSize = 24.sp, lineHeight = 32.sp,
        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 16.dp))
  }
}

/**
 * Static representative preview for kinds whose real widget would fetch or poll just to be seen:
 * the real heading over a quiet skeleton of the card's layout. No sample data is invented.
 */
@Composable
internal fun HomeStaticWidgetPreview(kind: String, modifier: Modifier = Modifier) {
  val bar = ApprovedHomePalette.Secondary.copy(alpha = .22f)
  @Composable fun line(width: Float, height: Dp = 20.dp, top: Dp = 14.dp) =
      Box(Modifier.padding(top = top).fillMaxWidth(width).height(height).clip(RoundedCornerShape(6.dp)).background(bar))
  if (kind == HomeWidgetStore.KIND_PHOTO_MEMORY) {
    Box(modifier.background(ApprovedHomePalette.Chrome), contentAlignment = Alignment.Center) {
      ApprovedHomeIcon("photo", Modifier.size(84.dp), ApprovedHomePalette.Secondary)
    }
    return
  }
  Column(modifier.padding(30.dp)) {
    ApprovedHomeWidgetHeading(HomeWidgetPicker.label(kind), HomeWidgetPicker.icon(kind), action = null) { }
    Spacer(Modifier.height(18.dp))
    when (kind) {
      HomeWidgetStore.KIND_WEATHER -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { line(.55f, 76.dp, 0.dp); line(.7f); line(.45f) }
        ApprovedHomeIcon("weather", Modifier.size(72.dp),
            if (com.immortal.launcher.ui.theme.LocalApprovedPalette.current.dark) Color(0xFFF4DB93) else Color(0xFF99610F))
      }
      HomeWidgetStore.KIND_CALENDAR, HomeWidgetStore.KIND_NEWS -> repeat(3) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
          Box(Modifier.weight(.35f)) { line(1f, 18.dp, 0.dp) }
          Box(Modifier.weight(.65f)) { line(1f, 18.dp, 0.dp) }
        }
      }
      HomeWidgetStore.KIND_HA -> {
        line(.75f, 26.dp, 0.dp); line(.9f)
        Box(Modifier.padding(top = 22.dp).width(190.dp).height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(ApprovedHomePalette.Chrome))
      }
      HomeWidgetStore.KIND_CRYPTO, HomeWidgetStore.KIND_STOCKS, HomeWidgetStore.KIND_OPTIONS -> repeat(3) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
          Box(Modifier.width(80.dp)) { line(1f, 18.dp, 0.dp) }
          Box(Modifier.width(120.dp)) { line(1f, 18.dp, 0.dp) }
        }
      }
      // A QR-shaped placeholder: the real widget would decrypt the password just to be previewed.
      HomeWidgetStore.KIND_WIFI -> Box(Modifier.size(132.dp).clip(RoundedCornerShape(16.dp))
          .background(ApprovedHomePalette.Chrome), contentAlignment = Alignment.Center) {
        ApprovedHomeIcon("qr", Modifier.size(72.dp), ApprovedHomePalette.Secondary)
      }
      else -> { line(.8f, 26.dp, 0.dp); line(.6f) }
    }
  }
}

/** One choice in the picker: an Immortal kind or an installed provider's widget. */
internal data class HomePickerChoice(val key: String, val label: String, val image: ImageBitmap? = null,
    val sublabel: String = "")

/**
 * "Add a widget": the everyday widgets as previews at slot size (no descriptions), a 4×2 map of the
 * page highlighting the tapped slot, and an eighth "More" tile revealing the rest plus app widgets.
 */
@Composable
internal fun HomeWidgetPickerSheet(
    slot: Slot,
    occupied: Set<Pair<Int, Int>>,
    slotSize: DpSize,
    everyday: List<String>,
    more: List<String>,
    providers: List<HomePickerChoice>?,
    preview: HomeWidgetPreviewContent,
    onKind: (String) -> Unit,
    onProvider: (HomePickerChoice) -> Unit,
    onDismiss: () -> Unit,
) {
  var showMore by rememberSaveable(slot) { mutableStateOf(false) }
  Dialog(onDismissRequest = onDismiss,
      properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    val view = LocalView.current
    DisposableEffect(view) {
      (view.parent as? DialogWindowProvider)?.window?.let { window ->
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setDimAmount(0f)
        WindowInsetsControllerCompat(window, window.decorView).apply {
          hide(WindowInsetsCompat.Type.systemBars())
          systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
      }
      onDispose { }
    }
    CompositionLocalProvider(LocalApprovedHomePopup provides true) {
      Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f))
          .pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
        ApprovedHomeSurface(Modifier.fillMaxSize().padding(start = LauncherEdge, end = LauncherEdge, top = 104.dp,
            bottom = LauncherEdge).pointerInput(Unit) { detectTapGestures { } }, radius = 32.dp) {
          Column(Modifier.fillMaxSize().padding(start = 36.dp, end = 36.dp, top = 32.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              HomeSlotMiniMap(slot, occupied)
              Text("Add a widget", color = ApprovedHomePalette.Text, fontSize = 44.sp, lineHeight = 54.sp,
                  fontWeight = FontWeight.Medium, letterSpacing = (-.5).sp,
                  modifier = Modifier.padding(start = 24.dp).weight(1f))
              Box(Modifier.size(68.dp).clip(CircleShape).background(ApprovedHomePalette.Chrome)
                  .semantics { contentDescription = "Close" }
                  .tvFocusable(CircleShape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onDismiss),
                  contentAlignment = Alignment.Center) {
                ApprovedHomeIcon("close", Modifier.size(30.dp), ApprovedHomePalette.Text)
              }
            }
            HomePickerGrid(slotSize, everyday, more, providers, showMore, preview,
                onToggleMore = { showMore = !showMore }, onKind = onKind, onProvider = onProvider)
          }
        }
      }
    }
  }
}

@Composable
private fun ColumnScope.HomePickerGrid(
    slotSize: DpSize,
    everyday: List<String>,
    more: List<String>,
    providers: List<HomePickerChoice>?,
    showMore: Boolean,
    preview: HomeWidgetPreviewContent,
    onToggleMore: () -> Unit,
    onKind: (String) -> Unit,
    onProvider: (HomePickerChoice) -> Unit,
) {
  val tileHeight = if (slotSize.height == Dp.Unspecified) 360.dp else slotSize.height.coerceIn(220.dp, 440.dp)
  val extra = more.size + (providers?.size ?: 0)
  LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f).fillMaxWidth(),
      contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp),
      horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
    items(everyday, key = { it }, contentType = { "kind" }) { kind ->
      HomePickerTile(HomeWidgetPicker.label(kind), tileHeight, { onKind(kind) }) {
        preview(kind, 1 to 2, Modifier.fillMaxSize())
      }
    }
    item(key = "more", contentType = "more") {
      HomeMoreTile(if (showMore) "Fewer" else "More", if (showMore) null else "$extra more", tileHeight, onToggleMore)
    }
    if (showMore) {
      items(more, key = { it }, contentType = { "kind" }) { kind ->
        HomePickerTile(HomeWidgetPicker.label(kind), tileHeight, { onKind(kind) }) {
          preview(kind, 1 to 2, Modifier.fillMaxSize())
        }
      }
      item(key = "providers-heading", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
        Text(if (providers == null) "Android app widgets · loading…" else "Android app widgets",
            color = ApprovedHomePalette.Secondary, fontSize = 24.sp, lineHeight = 32.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
      }
      if (providers?.isEmpty() == true) item(key = "providers-empty", span = { GridItemSpan(maxLineSpan) }) {
        Text("No app widgets installed.", color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
      }
      items(providers.orEmpty(), key = { "provider:${it.key}" }, contentType = { "provider" }) { choice ->
        HomePickerTile(choice.label, tileHeight * .6f, { onProvider(choice) }) {
          Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (choice.image != null) Image(choice.image, contentDescription = null, modifier = Modifier.size(48.dp))
            else ApprovedHomeIcon("apps", Modifier.size(48.dp), ApprovedHomePalette.Accent)
            Text(choice.label, color = ApprovedHomePalette.Text, fontSize = 24.sp, lineHeight = 32.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(choice.sublabel, color = ApprovedHomePalette.Secondary, fontSize = 20.sp, lineHeight = 26.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
  }
}

@Composable
private fun HomePickerTile(label: String, height: Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
  ApprovedHomeSurface(Modifier.fillMaxWidth().height(height)) {
    HomePreviewFrame(content)
    Box(Modifier.matchParentSize().semantics { contentDescription = label; role = Role.Button }
        .tvFocusable(CardShape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick))
  }
}

@Composable
private fun HomeMoreTile(title: String, subtitle: String?, height: Dp, onClick: () -> Unit) {
  val line = ApprovedHomePalette.Secondary.copy(alpha = .5f)
  Box(Modifier.fillMaxWidth().height(height).semantics { contentDescription = subtitle?.let { "$title, $it" } ?: title }
      .tvFocusable(CardShape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick),
      contentAlignment = Alignment.Center) {
    Canvas(Modifier.matchParentSize()) {
      val stroke = 2.dp.toPx()
      drawRoundRect(line, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
          cornerRadius = CornerRadius(24.dp.toPx()),
          style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))))
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(title, color = ApprovedHomePalette.Text, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium)
      subtitle?.let { Text(it, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp) }
    }
  }
}

/** A tiny 4×2 map of the page: the tapped slot in the accent, covered slots filled, empty ones hollow. */
@Composable
private fun HomeSlotMiniMap(slot: Slot, occupied: Set<Pair<Int, Int>>) {
  val accent = ApprovedHomePalette.Accent
  val filled = ApprovedHomePalette.Secondary.copy(alpha = .45f)
  val hollow = ApprovedHomePalette.Secondary.copy(alpha = .6f)
  Canvas(Modifier.size(width = 104.dp, height = 42.dp)
      .semantics { contentDescription = "Row ${slot.row + 1}, column ${slot.column + 1}" }) {
    val gap = 4.dp.toPx()
    val w = (size.width - gap * 3) / 4
    val h = (size.height - gap) / 2
    for (row in 0..1) for (column in 0..3) {
      val topLeft = Offset(column * (w + gap), row * (h + gap))
      val radius = CornerRadius(4.dp.toPx())
      when {
        column == slot.column && row == slot.row -> drawRoundRect(accent, topLeft, Size(w, h), radius)
        column to row in occupied -> drawRoundRect(filled, topLeft, Size(w, h), radius)
        else -> drawRoundRect(hollow, topLeft + Offset(1f, 1f), Size(w - 2f, h - 2f), radius, Stroke(1.5.dp.toPx()))
      }
    }
  }
}

/**
 * The floating place-and-size bar: "‹ name" back to the picker, the four shapes (greyed and
 * disabled when they don't fit around the tapped slot), and Add.
 */
@Composable
internal fun HomeAddShapeBar(
    draft: HomeAddDraft,
    fitting: List<Pair<Int, Int>>,
    onBack: () -> Unit,
    onShape: (Pair<Int, Int>) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val palette = com.immortal.launcher.ui.theme.LocalApprovedPalette.current
  ApprovedHomeSurface(modifier.height(128.dp), radius = 34.dp) {
    Row(Modifier.fillMaxHeight().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
      Row(Modifier.heightIn(min = 80.dp).widthIn(max = 300.dp)
          .semantics { contentDescription = "Back to widgets, ${draft.label}" }
          .tvFocusable(RoundedCornerShape(18.dp), focusScale = 1f, ringColor = palette.accent, onClick = onBack)
          .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ApprovedHomeIcon("back", Modifier.size(28.dp), ApprovedHomePalette.Secondary)
        Text(draft.label, color = ApprovedHomePalette.Text, fontSize = 28.sp, lineHeight = 36.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      HomeBarDivider()
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
        HomeSlots.SHAPES.forEach { shape ->
          HomeShapeButton(shape, selected = shape == draft.span, enabled = shape in fitting) { onShape(shape) }
        }
      }
      HomeBarDivider()
      Box(Modifier.padding(start = 20.dp).height(88.dp).widthIn(min = 150.dp).clip(RoundedCornerShape(44.dp))
          .background(palette.text)
          .semantics { contentDescription = "Add ${draft.label}" }
          .tvFocusable(RoundedCornerShape(44.dp), focusScale = 1f, ringColor = palette.accent, onClick = onAdd)
          .padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        Text("Add", color = palette.background, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

@Composable
private fun HomeBarDivider() {
  Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(84.dp).background(ApprovedHomePalette.Border))
}

private fun shapeName(shape: Pair<Int, Int>): String = when (shape) {
  1 to 2 -> "One slot"
  2 to 2 -> "Two wide"
  1 to 4 -> "Two tall"
  else -> "Two by two"
}

@Composable
private fun HomeShapeButton(shape: Pair<Int, Int>, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val accent = ApprovedHomePalette.Accent
  val color = when {
    selected -> accent
    enabled -> ApprovedHomePalette.Text.copy(alpha = .85f)
    else -> ApprovedHomePalette.Secondary.copy(alpha = .25f)
  }
  val shapeOutline = RoundedCornerShape(18.dp)
  Box(Modifier.size(width = 112.dp, height = 92.dp).clip(shapeOutline)
      .background(if (selected) ApprovedHomePalette.Chrome else Color.Transparent)
      .then(if (selected) Modifier.border(2.dp, accent, shapeOutline) else Modifier)
      .semantics { contentDescription = shapeName(shape); this.selected = selected }
      .tvFocusable(shapeOutline, focusScale = 1f, ringColor = accent, enabled = enabled, onClick = onClick),
      contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(60.dp)) {
      val gap = 5.dp.toPx()
      val unit = (size.width - gap) / 2
      val columns = shape.first
      val rows = shape.second / 2
      val left = (size.width - (columns * unit + (columns - 1) * gap)) / 2
      val top = (size.height - (rows * unit + (rows - 1) * gap)) / 2
      for (c in 0 until columns) for (r in 0 until rows) {
        drawRoundRect(color, Offset(left + c * (unit + gap), top + r * (unit + gap)), Size(unit, unit),
            CornerRadius(6.dp.toPx()))
      }
    }
  }
}
