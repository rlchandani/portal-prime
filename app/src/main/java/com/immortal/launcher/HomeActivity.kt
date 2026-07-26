/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.immortal.launcher.ui.components.GlassTile
import com.immortal.launcher.ui.theme.ContentPrimary
import com.immortal.launcher.ui.theme.ContentSecondary
import com.immortal.launcher.ui.theme.PrimeBlue
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private data class AppEntry(
    val label: String,
    val component: ComponentName,
    val icon: ImageBitmap,
    val folder: String? = null,
    val uninstallable: Boolean = false,
)

private data class WidgetProviderEntry(
    val label: String,
    val packageLabel: String,
    val icon: ImageBitmap?,
    val info: AppWidgetProviderInfo?,
    val spanX: Int,
    val spanY: Int,
    val customKind: String? = null,
)

private data class PendingWidgetAdd(
    val appWidgetId: Int,
    val provider: WidgetProviderEntry,
)

/** Portal-TV (ripleyhome) fallback for [launchStockHome]: how many system Back presses to reach
 *  Meta's launcher, and the gap between them. Best-effort — on some Portal TVs the burst lands on
 *  the previously-used app instead (issue #91), and it's untestable from here. */
private const val STOCK_HOME_BACK_PRESSES = 5
private const val STOCK_HOME_BACK_INTERVAL_MS = 300L
private const val HOME_APP_WIDGET_HOST_ID = 0x4611

/**
 * The custom Portal home launcher. Replaces the stock Aloha home (selected via
 * `cmd package set-home-activity`). Shows a clock/date/weather header, an App
 * Store tile, and a grid of every installed launchable app. Built for Portal's
 * form factor: dark theme, top 64dp reserved for the system overlay, large
 * touch targets, landscape.
 */
class HomeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enterImmersive()
    // First launch: show the friendly tour once so new users aren't dropped in cold.
    if (!HelpActivity.hasSeen(this)) {
      window.decorView.post {
        runCatching { startActivity(Intent(this, HelpActivity::class.java)) }
      }
    }
    setContent {
      PortalPrimeTheme(darkTheme = true) {
        LauncherScreen(
            onLaunch = { cn ->
              runCatching {
                if (cn.packageName == packageName &&
                    cn.className == ImmortalSettingsActivity::class.java.name) {
                  // Our own iOS-style settings (exported=false, no LAUNCHER filter) — launch it
                  // directly with an explicit intent rather than the generic MAIN/LAUNCHER one.
                  startActivity(
                      Intent(this@HomeActivity, ImmortalSettingsActivity::class.java)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else if (cn.packageName == packageName &&
                    cn.className == StoreActivity::class.java.name) {
                  // Our own App Store — explicit launch (same reasoning as settings).
                  startActivity(
                      Intent(this@HomeActivity, StoreActivity::class.java)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                  startActivity(
                      Intent(Intent.ACTION_MAIN)
                          .addCategory(Intent.CATEGORY_LAUNCHER)
                          .setComponent(cn)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
              }
            },
            onOpenStore = {
              runCatching { startActivity(Intent(this, StoreActivity::class.java)) }
            },
            onOpenHelp = {
              runCatching { startActivity(Intent(this, HelpActivity::class.java)) }
            },
            onStartScreensaver = {
              runCatching {
                startActivity(Intent(this, PhotoFramePreviewActivity::class.java))
              }
            },
            onExitHome = { launchStockHome() },
            onUninstall = { pkg ->
              // System uninstall dialog; no special permission needed.
              runCatching {
                startActivity(
                    Intent(Intent.ACTION_DELETE)
                        .setData(android.net.Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
            },
        )
      }
    }
  }

  // Re-assert immersive fullscreen whenever the launcher regains focus — e.g.
  // after the screensaver or another app closes — since the system restores the
  // bars when another window takes over.
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) enterImmersive()
  }

  // Self-heal: if anything (e.g. the stock launcher) reset our screensaver
  // settings, put them back every time Immortal comes to the foreground.
  override fun onResume() {
    super.onResume()
    // The user is back on Immortal, so any stock-launcher call handoff is over:
    // allow the photo frame to resume its normal screensaver behaviour.
    DreamPolicy.clearBridge(this)
    SettingsGuard.reaffirmScreensaver(this)
    // The user is back on the launcher: end the idle session and, inside the overnight window,
    // arm the touch-renewed "you have the device" session. SleepScheduler owns that policy.
    SleepScheduler.onReturnedToLauncher(this)
  }

  override fun onPause() {
    super.onPause()
    // Don't carry a pending overnight re-sleep into another activity or a real sleep.
    SleepScheduler.onLeftLauncher()
  }

  // dispatchTouchEvent is the top of the input chain, so it sees every touch before Compose
  // consumes it. Renews the overnight session; a no-op outside the window.
  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    SleepScheduler.onInteraction(this)
    return super.dispatchTouchEvent(ev)
  }

  private fun enterImmersive() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }

  /**
   * The "Calls" tile bridges to the STOCK Portal home — the only caller Meta trusts
   * to launch Contacts/calling/camera.
   *
   * Why a deep link and not a plain HOME launch: the Contacts app
   * (com.facebook.alohaapps.contacts) enforces a signature-based trusted-caller
   * check (Meta's com.facebook.secure framework) that Immortal can never satisfy,
   * so we must route through the trusted stock launcher. A plain MAIN/HOME launch
   * cold-starts the stock launcher into its idle "dream" face, whose
   * DREAMING_STOPPED then makes our own screensaver relaunch over the top and trap
   * the user (the reported "Calls kicks me back into Immortal"). The stock
   * launcher's `portal://launcher/home` VIEW deep link instead resumes its
   * interactive Home tab directly — the Contacts/Favorites calling surface — and we
   * mark a bridge in flight so [DreamPolicy] doesn't claw the frame back during the
   * transition. (Launching Contacts directly is rejected as an untrusted caller, so the
   * hand-off must go through the launcher.)
   *
   * Path order: the deep link is primary on touchscreen Portals. The accessibility
   * Back-press burst is only a Portal-TV (ripleyhome) fallback for when the deep link
   * doesn't resolve — on a touchscreen Portal a system BACK from Immortal (itself the
   * home activity) never surfaces the stock launcher, so the burst can't be primary there.
   */
  private fun launchStockHome() {
    // Suppress the screensaver-relaunch race while the stock home comes forward, and
    // keep suppressing the holding-frame relaunch until the user returns to Immortal
    // (cleared in onResume) so the frame can't slam over an in-progress call.
    // Persisted to SharedPreferences so new Immortal processes spawned after Android
    // kills our process also see the bridge and return SUPPRESSED (not REDREAM).
    DreamPolicy.markBridge(this)

    fun fire(intent: Intent): Boolean =
        runCatching {
              startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              true
            }
            .getOrDefault(false)

    // 1) Touchscreen Portals (Portal / Portal+ / Go / Mini): deep-link into the stock launcher's
    //    Home tab. Needs the stock launcher enabled — provisioning leaves it so. Gate on
    //    resolveActivity: launching an explicit-but-DISABLED component does NOT throw, so an
    //    unguarded fire() would return true and swallow the tap (Calls silently no-ops) instead of
    //    falling through. Resolving first means a disabled launcher drops to the fallbacks below.
    val deepLink =
        Intent(Intent.ACTION_VIEW, Uri.parse("portal://launcher/home"))
            .setPackage("com.facebook.alohaapps.launcher")
    if (packageManager.resolveActivity(deepLink, 0) != null && fire(deepLink)) return

    // 2) Portal TV (ripleyhome): no portal:// deep link, so the VIEW above didn't resolve. Surface
    //    its stock launcher with a Back-press burst through the accessibility service.
    if (RemoteInput.available()) {
      RemoteInput.backRepeat(STOCK_HOME_BACK_PRESSES, STOCK_HOME_BACK_INTERVAL_MS)
      return
    }

    // 3) Last resort: this device's real stock HOME, excluding ourselves and the fallback homes.
    val stock =
        packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo }
            .firstOrNull {
              it.packageName != packageName &&
                  it.packageName != "com.android.settings" &&
                  it.packageName != "com.android.tv.settings" &&
                  !it.name.contains("FallbackHome", ignoreCase = true)
            }
    if (stock != null) {
      fire(
          Intent(Intent.ACTION_MAIN)
              .addCategory(Intent.CATEGORY_LAUNCHER)
              .setComponent(ComponentName(stock.packageName, stock.name)))
    }
  }
}

/** Left column with greeting/now-playing chrome + resizable, reorderable multi-widget area. */
@Composable
private fun LeftWidgetColumn(
    editMode: Boolean,
    greetingWord: String,
    dateString: String,
    homeWidgets: List<HomeWidgetStore.HomeWidget>,
    weatherCurrent: Weather.Current?,
    onWidgetsChanged: (List<HomeWidgetStore.HomeWidget>) -> Unit,
    onEnterEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
  var showAddMenu by remember { mutableStateOf(false) }

  Column(
      modifier = modifier,
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Greeting card (always present). Wraps its content so the date line isn't clipped.
    LiquidGlassWidget(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
      Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
            text = "$greetingWord,",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            text = "Rohit",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
        )
        Text(
            text = dateString,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
        )
      }
    }

    // Today strip (always present): next event · feels-like + hi/lo · moon phase + sun times.
    TodayStripWidget(modifier = Modifier.fillMaxWidth())

    // Now Playing card (always present)
    LiquidGlassWidget(modifier = Modifier.fillMaxWidth().height(80.dp)) {
      Row(
          modifier = Modifier.fillMaxSize().padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        LiquidGlass(modifier = Modifier.size(40.dp), cornerRadius = 8.dp) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("♫", color = Color.White, fontSize = 18.sp)
          }
        }
        Column {
          Text(
              "Now Playing",
              color = Color.White.copy(alpha = 0.55f),
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium,
          )
          Text(
              "Tap to play",
              color = Color.White.copy(alpha = 0.28f),
              fontSize = 11.sp,
              fontWeight = FontWeight.Light,
          )
        }
      }
    }

    // Premium dashboard cards (self-hiding when their data/target isn't available):
    // smart-home quick controls, family quick-call, and a photo memory.
    QuickControlsWidget(modifier = Modifier.fillMaxWidth())
    FamilyCallRow(modifier = Modifier.fillMaxWidth())
    PhotoMemoryStrip(modifier = Modifier.fillMaxWidth())

    // Store-driven resizable + reorderable widgets.
    // Mirrors the app-icon grid EXACTLY: a LazyVerticalGrid(2) whose reorderable state edits the
    // same list the grid renders (1:1 index mapping) and persists the new order. A Small widget
    // (spanX==1) fills one column; Medium/Large (spanX>=2) span both columns. The greeting and
    // now-playing chrome stay fixed above; the "+ Add Widget" tile is a non-draggable full-span
    // item at the end. The grid is weight(1f) inside this fillMaxHeight column so it is bounded
    // (no scrolling parent → no infinite-height measure crash).
    val widgetGridState = rememberLazyGridState()
    val widgetReorderState = rememberReorderableLazyGridState(widgetGridState) { from, to ->
      // Called continuously as items cross during drag. Reorder the displayed list (indexed
      // identically to what the grid renders) so from/to line up 1:1, then persist the order.
      val list = homeWidgets.toMutableList()
      if (from.index in list.indices && to.index in list.indices) {
        list.add(to.index, list.removeAt(from.index))
        onWidgetsChanged(list)
      }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = widgetGridState,
        // In edit mode the grid's own vertical scroll competes with the drag; disable user
        // scroll while editing so the reorder owns the pointer (same as the icon grid).
        userScrollEnabled = !editMode,
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(
          homeWidgets,
          key = { it.key },
          span = { GridItemSpan(if (it.spanX >= 2) 2 else 1) },
      ) { widget ->
        ReorderableItem(widgetReorderState, key = widget.key) { isDragging ->
          val widgetHeight = 100.dp * widget.spanY.toFloat()
          // --- Hold-and-drag resize state (per widget) ---------------------------------------
          // One grid row-unit is 100.dp; convert to px so a vertical drag maps to spanY steps.
          val rowUnitPx = with(androidx.compose.ui.platform.LocalDensity.current) { 100.dp.toPx() }
          // Gap between grid columns (matches horizontalArrangement.spacedBy(8.dp)).
          val colGapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
          // The laid-out width of this widget cell (spans widget.spanX columns). Captured below.
          var widgetWidthPx by remember(widget.key) { mutableStateOf(0) }
          // Accumulated drag delta since the current resize gesture began.
          var resizeAccum by remember(widget.key) { mutableStateOf(Offset.Zero) }
          // Span at the moment the resize gesture started (the anchor for target-span math).
          var startSpanX by remember(widget.key) { mutableStateOf(widget.spanX) }
          var startSpanY by remember(widget.key) { mutableStateOf(widget.spanY) }
          // Column width frozen at drag start (stable divisor — avoids the resize feedback loop).
          var startColWidthPx by remember(widget.key) { mutableStateOf(0f) }
          // animateItem() glides the OTHER widgets into their new slots while one is dragged.
          // The dragged item is driven by the reorderable library, so exclude it while dragging.
          Box(modifier = if (isDragging) Modifier else Modifier.animateItem()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(widgetHeight)
                    .onGloballyPositioned { widgetWidthPx = it.size.width }
                    .jiggle(editMode && !isDragging, widget.key.hashCode())
                    // Drag handle mirrors the icon grid:
                    // - In edit mode: press+move starts a drag immediately (no hold).
                    // - Not in edit mode: a long-press BOTH enters edit mode AND begins the drag
                    //   in one continuous gesture (onDragStarted calls onEnterEdit).
                    // The handle is on the widget BODY, not the badges, so the corner badges
                    // still receive their own taps.
                    .then(
                        if (editMode) {
                          Modifier.draggableHandle(
                              dragGestureDetector = sh.calvin.reorderable.DragGestureDetector.Press,
                              onDragStarted = {},
                              onDragStopped = {},
                          )
                        } else {
                          Modifier.longPressDraggableHandle(
                              onDragStarted = { onEnterEdit() },
                              onDragStopped = {},
                          )
                        }
                    )
                    // Inset by the badge overhang so the corner badges sit ON the edge and render
                    // FULLY (not clipped) — same treatment as the app-icon minus badge.
                    .padding(11.dp)
            ) {
              LiquidGlassWidget(modifier = Modifier.fillMaxSize()) {
                ImmortalWidgetContent(widget = widget, modifier = Modifier.fillMaxSize())
              }

              if (editMode) {
                // Minus at top-left edge, resize at bottom-right edge; offset out into the
                // padding room so each badge straddles the corner and stays fully visible.
                MinusBadge(
                    modifier = Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp),
                ) {
                  onWidgetsChanged(HomeWidgetStore.without(homeWidgets, widget.key))
                }
                ResizeBadge(
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp),
                    onResizeStart = {
                      // Anchor: capture the span AND the cell pixel size ONCE at gesture start.
                      // Using a frozen column width (not the live widget width, which changes as
                      // the widget resizes) prevents the divisor from shifting mid-drag — the
                      // feedback loop that made the size flip back and forth.
                      startSpanX = widget.spanX
                      startSpanY = widget.spanY
                      resizeAccum = Offset.Zero
                      startColWidthPx =
                          if (widgetWidthPx > 0 && widget.spanX > 0)
                              ((widgetWidthPx - colGapPx) / widget.spanX).coerceAtLeast(1f)
                          else rowUnitPx
                    },
                    onResizeDrag = { delta ->
                      resizeAccum += delta
                      // Hysteresis: only flip to the next span once the drag passes 65% of a cell
                      // beyond the start (not the 50% midpoint), so jitter near a boundary can't
                      // oscillate. stepsX/Y = how many whole cells past the 0.65 threshold.
                      fun steps(accum: Float, unit: Float): Int {
                        val cells = accum / unit
                        // round toward zero until 0.65 into the next cell.
                        return (kotlin.math.sign(cells) *
                            kotlin.math.floor(kotlin.math.abs(cells) + 0.35f)).toInt()
                      }
                      val targetX = (startSpanX + steps(resizeAccum.x, startColWidthPx)).coerceIn(1, 2)
                      val targetY = (startSpanY + steps(resizeAccum.y, rowUnitPx)).coerceIn(1, 4)
                      if (targetX != widget.spanX || targetY != widget.spanY) {
                        val updated = widget.copy(spanX = targetX, spanY = targetY)
                        onWidgetsChanged(
                            homeWidgets.map { if (it.key == widget.key) updated else it })
                      }
                    },
                    onResizeEnd = { resizeAccum = Offset.Zero },
                )
              }
            }
          }
        }
      }

      // Add widget tile (edit mode only) — full-span, non-draggable item at the end.
      if (editMode) {
        item(key = "add-widget", span = { GridItemSpan(2) }) {
          LiquidGlassWidget(
              modifier = Modifier.fillMaxWidth().height(100.dp).clickable { showAddMenu = true },
          ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(32.dp)) {
                  val cx = size.width / 2f
                  val cy = size.height / 2f
                  val armLength = size.minDimension * 0.3f
                  val stroke = size.minDimension * 0.08f
                  drawLine(Color.White.copy(alpha = 0.5f), Offset(cx - armLength, cy), Offset(cx + armLength, cy), strokeWidth = stroke, cap = StrokeCap.Round)
                  drawLine(Color.White.copy(alpha = 0.5f), Offset(cx, cy - armLength), Offset(cx, cy + armLength), strokeWidth = stroke, cap = StrokeCap.Round)
                }
                Text(
                    "Add Widget",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Light,
                )
              }
            }
          }
        }
      }
    }
  }

  // Add widget menu
  if (showAddMenu) {
    Box(
        modifier = Modifier.fillMaxSize().clickable { showAddMenu = false },
        contentAlignment = Alignment.Center,
    ) {
      Surface(
          color = Color(0xE6000000),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.padding(32.dp),
      ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Add Widget", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
          listOf(
              HomeWidgetStore.KIND_CALENDAR to "Calendar",
              HomeWidgetStore.KIND_WEATHER to "Weather",
              HomeWidgetStore.KIND_WORLD_CLOCK to "World Clock",
              HomeWidgetStore.KIND_TIMERS to "Timers",
          ).forEach { (kind, label) ->
            Text(
                label,
                color = Color(0xFF8AB4F8),
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clickable {
                  val newWidget = HomeWidgetStore.custom(kind)
                  onWidgetsChanged(HomeWidgetStore.withAdded(homeWidgets, newWidget))
                  showAddMenu = false
                }.padding(12.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LauncherScreen(
    onLaunch: (ComponentName) -> Unit,
    onOpenStore: () -> Unit,
    onOpenHelp: () -> Unit,
    onStartScreensaver: () -> Unit,
    onExitHome: () -> Unit,
    onUninstall: (String) -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current

  // Live clock tick
  val now by produceState(initialValue = Date()) {
    while (true) { delay(1000); value = Date() }
  }

  // Installed apps. Reloaded whenever a package is added/removed/changed (so an uninstalled
  // app's tile disappears immediately instead of lingering until the Activity is recreated)
  // and whenever the launcher returns to the foreground (catches changes made elsewhere).
  var appsRefreshKey by remember { mutableStateOf(0) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val onResume = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) appsRefreshKey++
    }
    lifecycleOwner.lifecycle.addObserver(onResume)
    val pkgReceiver = object : android.content.BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) { appsRefreshKey++ }
    }
    val filter = android.content.IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addAction(Intent.ACTION_PACKAGE_CHANGED)
      addAction(Intent.ACTION_PACKAGE_REPLACED)
      addDataScheme("package")
    }
    runCatching { context.registerReceiver(pkgReceiver, filter) }
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(onResume)
      runCatching { context.unregisterReceiver(pkgReceiver) }
    }
  }
  val installedApps by produceState<List<AppEntry>>(initialValue = emptyList(), appsRefreshKey) {
    value = withContext(Dispatchers.IO) { loadApps(context) }
  }

  // Weather (30-min refresh)
  val weatherCurrent by produceState<Weather.Current?>(initialValue = null) {
    while (true) {
      val w = withContext(Dispatchers.IO) { Weather.fetchCurrent(context) }
      if (w != null) { value = w; delay(30L * 60 * 1000) } else { delay(60L * 1000) }
    }
  }

  // Battery
  val battery = batteryState()

  // Edit mode state
  var editMode by remember { mutableStateOf(false) }
  BackHandler(enabled = editMode) { editMode = false }

  // ── Single reorderable app grid (no dock) ──────────────────────────────────
  // The user's saved icon order. All installed apps live in one grid; the
  // sh.calvin.reorderable library owns the drag-reorder (smooth, flicker-free).
  // NOTE: HomeLayoutStore.kt / DragHitTest.kt (+ their tests) remain in the tree
  // but are intentionally unused by the UI now that the dock is gone.
  val orderState = remember { mutableStateOf(UserLayout.loadOrder(context)) }
  val orderedApps = remember(installedApps, orderState.value) {
    // Identity is the flattened component ("pkg/class"), not the package name: our two
    // synthetic tiles (Settings, App Store) both live under this launcher's package, so a
    // package-name key would collide and crash the LazyGrid ("key already used").
    UserLayout.applyOrder(installedApps, orderState.value) { it.component.flattenToString() }
  }

  // App tile size (Settings > Appearance): drives the grid column count and the tile size that
  // every AppTile reads via LocalTileDp. Re-read on the same resume/package trigger as the app
  // list, so changing it in Settings applies the moment the user returns home.
  val tileSize = remember(appsRefreshKey) { ImmortalSettings.load(context).tileSize }
  val gridColumns = gridColumnsFor(tileSize)
  val tileDp = tileDpFor(tileSize)

  // Home widgets state
  var homeWidgets by remember { mutableStateOf(HomeWidgetStore.load(context)) }
  LaunchedEffect(Unit) {
    if (homeWidgets.isEmpty()) {
      // Seed default: one Large weather widget
      val defaultWidget = HomeWidgetStore.custom(HomeWidgetStore.KIND_WEATHER, spanX = 2, spanY = 2)
      homeWidgets = listOf(defaultWidget)
      HomeWidgetStore.save(context, homeWidgets)
    }
  }

  // Derived strings
  val hour = SimpleDateFormat("H", Locale.getDefault()).format(now).toIntOrNull() ?: 12
  val greetingWord = when {
    hour in 5..11  -> "Good morning"
    hour in 12..16 -> "Good afternoon"
    hour in 17..21 -> "Good evening"
    else           -> "Good night"
  }
  val timeString = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
  val dateString = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)

  // ── iOS 26 Liquid Glass Layout ────────────────────────────────────────────

  BoxWithConstraints(
      modifier = Modifier.fillMaxSize().then(
          if (editMode) {
            // Exit edit mode on an empty-space tap. Attached to the OUTERMOST box so it always
            // receives the gesture; the Final pass + unconsumed check means it fires only when
            // no child (icon/widget/badge/drag-handle) claimed the tap — i.e. empty space.
            Modifier.pointerInput(Unit) {
              awaitEachGesture {
                val down = awaitFirstDown(pass = androidx.compose.ui.input.pointer.PointerEventPass.Final)
                val up = waitForUpOrCancellation(pass = androidx.compose.ui.input.pointer.PointerEventPass.Final)
                if (up != null && !up.isConsumed) editMode = false
              }
            }
          } else Modifier
      )
  ) {
    val w = constraints.maxWidth
    val h = constraints.maxHeight

    // Background: the user's chosen wallpaper (WallpaperConfig via HomeBackground) — dark fill,
    // a gradient preset, a photo, sky/starfield, or "sync with screensaver". Re-reads on resume,
    // so a change in Settings > Wallpaper takes effect the moment the user returns home.
    // (Previously this was a hardcoded radial gradient, which made the Wallpaper setting a no-op.)
    HomeBackground(modifier = Modifier.fillMaxSize())

    Column(modifier = Modifier.fillMaxSize()) {

      // ── STATUS BAR ──────────────────────────────────────────────────────
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .background(
                  Brush.verticalGradient(
                      listOf(Color(0x40000000), Color.Transparent)
                  )
              )
              .padding(horizontal = 28.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
            text = timeString,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            text = dateString,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          weatherCurrent?.let {
            Text(
                "${Weather.emoji(it.code)} ${it.temp}°",
                color = Color.White,
                fontSize = 14.sp,
            )
          }
          WifiBars(wifiLevel())
          if (battery.present) {
            Text("${battery.percent}%", color = Color.White, fontSize = 14.sp)
          }
        }
      }

      // ── MAIN CONTENT ────────────────────────────────────────────────────
      Row(
          modifier = Modifier
              .weight(1f)
              .fillMaxWidth()
              .padding(horizontal = 28.dp)
              .padding(top = 12.dp, bottom = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(20.dp),
      ) {

        // LEFT COLUMN (38%) — Widget area with greeting/now-playing chrome + resizable widgets
        LeftWidgetColumn(
            editMode = editMode,
            greetingWord = greetingWord,
            dateString = dateString,
            homeWidgets = homeWidgets,
            weatherCurrent = weatherCurrent,
            onWidgetsChanged = { newWidgets ->
              homeWidgets = newWidgets
              HomeWidgetStore.save(context, newWidgets)
            },
            onEnterEdit = { editMode = true },
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.38f),
        )

        FadingDivider()

        // RIGHT COLUMN (62%) — app icon grid. Tile size + column count come from the
        // "App tile size" setting (Standard 6×88dp / Large 5×110dp / XL 4×140dp) via LocalTileDp.
        CompositionLocalProvider(LocalTileDp provides tileDp) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.62f),
        ) {
          // (No "Done" button — tap any empty space to exit edit mode.)
          val gridState = rememberLazyGridState()
          val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
            // Called continuously as items cross during drag. Reorder the displayed list
            // (indexed identically to what the grid renders) so from/to line up 1:1 and the
            // other items animate into their new slots. Persist the full order.
            val displayed = orderedApps.map { it.component.flattenToString() }.toMutableList()
            if (from.index in displayed.indices && to.index in displayed.indices) {
              displayed.add(to.index, displayed.removeAt(from.index))
              UserLayout.saveOrder(context, displayed)
              orderState.value = displayed
            }
          }
          LazyVerticalGrid(
              columns = GridCells.Fixed(gridColumns),
              state = gridState,
              // In edit mode the grid's own vertical scroll competes with the drag; disable
              // user scroll while editing so the reorder owns the pointer.
              userScrollEnabled = !editMode,
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(4.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalArrangement = Arrangement.spacedBy(20.dp),
          ) {
            items(orderedApps, key = { it.component.flattenToString() }) { app ->
              val pkg = app.component.packageName
              val itemKey = app.component.flattenToString()
              ReorderableItem(reorderableState, key = itemKey) { isDragging ->
                // animateItem() glides the OTHER icons into their new slots while one is
                // dragged. The dragged item is driven by the reorderable library, so exclude
                // it from placement animation while dragging.
                Box(modifier = if (isDragging) Modifier else Modifier.animateItem()) {
                  LiquidGlassAppIconContent(
                      app = app,
                      editMode = editMode,
                      isDragging = isDragging,
                      // Drag handle is ALWAYS active:
                      // - In edit mode: press+move starts a drag immediately (no hold).
                      // - Not in edit mode: a long-press BOTH enters edit mode AND begins the
                      //   drag in one continuous gesture (onDragStarted flips editMode), so the
                      //   user never has to release and grab again.
                      dragHandleModifier = if (editMode) {
                        Modifier.draggableHandle(
                            dragGestureDetector = sh.calvin.reorderable.DragGestureDetector.Press,
                            onDragStarted = {},
                            onDragStopped = {},
                        )
                      } else {
                        Modifier.longPressDraggableHandle(
                            onDragStarted = { editMode = true },
                            onDragStopped = {},
                        )
                      },
                      onClick = { if (!editMode) onLaunch(app.component) },
                      onLongPress = {},
                      onRemove = { onUninstall(pkg) },
                  )
                }
              }
            }
          }
        }
        } // end CompositionLocalProvider(LocalTileDp)
      }
    }
  }
}

// Minimal weather condition text without network deps
private fun weatherConditionText(code: Int): String = when (code) {
  0 -> "Clear Sky"
  in 1..3 -> "Partly Cloudy"
  in 45..48 -> "Foggy"
  in 51..67 -> "Rainy"
  in 71..77 -> "Snowy"
  in 80..82 -> "Showers"
  in 95..99 -> "Thunderstorm"
  else -> "Cloudy"
}

// ─────────────────────────────────────────────────────────────────────────────
// WiFi signal indicator
// ─────────────────────────────────────────────────────────────────────────────

private const val RSSI_UNAVAILABLE = -127

@Composable
private fun wifiLevel(): Int {
  val context = androidx.compose.ui.platform.LocalContext.current
  return produceState(initialValue = -1) {
    while (true) {
      val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      value = if (wifiManager != null) {
        val rssi = wifiManager.connectionInfo?.rssi ?: RSSI_UNAVAILABLE
        if (rssi == RSSI_UNAVAILABLE) {
          -1
        } else {
          @Suppress("DEPRECATION")
          WifiManager.calculateSignalLevel(rssi, 5)
        }
      } else {
        -1
      }
      delay(10_000L)
    }
  }.value
}

@Composable
private fun WifiBars(level: Int) {
  Row(
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalAlignment = Alignment.Bottom,
  ) {
    for (i in 0..3) {
      val height = (4 + i * 3).dp
      val color = if (level != -1 && i < level) Color.White else Color.White.copy(alpha = 0.25f)
      Box(
          modifier = Modifier
              .width(3.dp)
              .height(height)
              .background(color, RoundedCornerShape(1.dp))
      )
    }
  }
}

@Composable
private fun FadingDivider() {
  Box(
      modifier = Modifier
          .fillMaxHeight()
          .padding(horizontal = 10.dp),
      contentAlignment = Alignment.Center
  ) {
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.30f),
                        Color.Transparent
                    )
                )
            )
    )
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Liquid Glass composables
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Bare Liquid Glass surface — transparent glass, FLAT (no gradient): a single even translucent
 * white fill so the wallpaper shows through uniformly, plus a thin light border for the glass edge.
 * No drop shadow (it renders a dark rect that bleeds through the translucent fill and darkens the
 * centre) and no gradient. Just a clean, even, see-through card with a crisp rim.
 */
@Composable
private fun LiquidGlass(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
  val shape = RoundedCornerShape(cornerRadius)
  Box(
      modifier = modifier
          .clip(shape)
          // Flat, even translucent fill — no gradient. ~5% white so the wallpaper shows through
          // strongly; the border does most of the work defining the card.
          .background(Color(0x0DFFFFFF))
          // Thin light rim for the glass edge.
          .border(1.dp, Color(0x59FFFFFF), shape),
  ) {
    content()
  }
}

/** Liquid Glass widget tile (rounded 22dp). */
@Composable
private fun LiquidGlassWidget(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
  LiquidGlass(modifier = modifier, cornerRadius = 22.dp, content = content)
}

/** App icon in the grid with scale animation on tap and reorderable drag handle. */
@Composable
private fun LiquidGlassAppIconContent(
    app: AppEntry,
    editMode: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
) {
  val scale = remember { Animatable(1f) }

  Box(
      modifier = Modifier
          .fillMaxWidth()
          .jiggle(editMode && !isDragging, app.component.packageName.hashCode()),
      contentAlignment = Alignment.Center
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
              scaleX = if (isDragging) 1.05f else scale.value
              scaleY = if (isDragging) 1.05f else scale.value
              shadowElevation = if (isDragging) 16.dp.toPx() else 0f
              shape = RoundedCornerShape(26.dp)
              clip = false
              // Disable Offscreen compositing while dragging for perf
              compositingStrategy = if (isDragging) CompositingStrategy.Auto else CompositingStrategy.Offscreen
            }
            .then(dragHandleModifier)
            .then(
                if (!editMode) {
                  // Non-edit mode: standard tap gestures with press-scale
                  Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                          scale.animateTo(0.96f, tween(90))
                          tryAwaitRelease()
                          scale.animateTo(1f, spring())
                        },
                        onTap = { onClick() },
                        onLongPress = { onLongPress() },
                    )
                  }
                } else {
                  // In edit mode the drag handle owns the gesture; a plain tap does nothing.
                  Modifier
                }
            ),
    ) {
      // Pad the wrapper so the minus badge sits at the tile's top-left corner and is FULLY
      // visible (not clipped by the grid cell). The tile is inset by the overhang amount.
      Box(modifier = Modifier.padding(top = 11.dp, start = 11.dp)) {
        // iOS-style icon: rounded-squircle corners, soft drop shadow (3D depth), and a glossy
        // top highlight sheen — applied uniformly so even square drawables read as premium
        // rounded tiles.
        val iconShape = RoundedCornerShape(26.dp)
        Box(
            modifier = Modifier
                .size(110.dp)
                .shadow(elevation = 8.dp, shape = iconShape, clip = false)
                .clip(iconShape),
            contentAlignment = Alignment.Center,
        ) {
          Image(
              bitmap = app.icon,
              contentDescription = app.label,
              modifier = Modifier.size(110.dp),
              filterQuality = FilterQuality.High,
          )
          // Glossy top-down sheen for the "candy"/3D finish.
          Box(
              modifier = Modifier.matchParentSize().background(
                  Brush.verticalGradient(
                      colors = listOf(Color(0x33FFFFFF), Color(0x0FFFFFFF), Color.Transparent),
                      endY = 130f,
                  )
              )
          )
        }
        if (editMode && app.uninstallable) {
          // Badge sits at the tile's top-left corner, fully inside the padded wrapper (so it
          // isn't clipped) — straddling the edge visually. Offset into the padding room.
          MinusBadge(
              modifier = Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp),
          ) { onRemove() }
        }
      }
      Spacer(Modifier.height(6.dp))
      Text(
          text = app.label,
          color = Color.White.copy(alpha = 0.82f),
          fontSize = 22.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
          modifier = Modifier.widthIn(max = 110.dp),
      )
    }
  }
}

/**
 * Full-screen "time's up" alarm overlay with an iOS-style slide-to-stop. Blocks touches from
 * reaching the grid beneath, so the only way out is the slider (or the 60s auto-silence).
 */
@Composable
private fun TimerAlarmOverlay(onStop: () -> Unit) {
  val noRipple = remember { MutableInteractionSource() }
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xE6000000))
              // Swallow taps so nothing behind the alarm reacts.
              .clickable(interactionSource = noRipple, indication = null) {},
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text("⏰", fontSize = 68.sp)
      Spacer(Modifier.size(18.dp))
      Text("Timer", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
      Text(
          "Time's up",
          color = Color(0xFFBFBFBF),
          fontSize = 18.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(44.dp))
      SlideToStop(onStop = onStop)
    }
  }
}

/** A draggable thumb on a track; sliding it to the far end fires [onStop] (else it snaps back). */
@Composable
private fun SlideToStop(onStop: () -> Unit) {
  val density = androidx.compose.ui.platform.LocalDensity.current
  val trackWidth = 320.dp
  val thumbSize = 56.dp
  val inset = 4.dp
  val maxOffset =
      with(density) { (trackWidth - thumbSize - inset * 2).toPx() }.coerceAtLeast(0f)
  val insetPx = with(density) { inset.roundToPx() }
  var offsetX by remember { mutableStateOf(0f) }
  Box(
      modifier =
          Modifier.width(trackWidth)
              .height(64.dp)
              .clip(RoundedCornerShape(32.dp))
              .background(Color(0x33FFFFFF)),
      contentAlignment = Alignment.CenterStart,
  ) {
    Text(
        "Slide to stop",
        color = Color(0xFFE8E8E8),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.align(Alignment.Center).alpha(1f - (offsetX / maxOffset).coerceIn(0f, 1f)),
    )
    Box(
        modifier =
            Modifier.offset { IntOffset(offsetX.roundToInt() + insetPx, 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color(0xFFE53935))
                .pointerInput(Unit) {
                  detectDragGestures(
                      onDragEnd = { if (offsetX >= maxOffset * 0.9f) onStop() else offsetX = 0f },
                      onDragCancel = { offsetX = 0f },
                      onDrag = { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxOffset)
                      },
                  )
                },
        contentAlignment = Alignment.Center,
    ) {
      Text("›", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
  }
}

private const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6 hours

// --- tile sizing ----------------------------------------------------------------
// The grid's tile edge, provided once at the top of the tree so every tile
// (apps, folders, built-ins, the drag ghost) follows the user's size setting.
// Standard is the original 6-column/88dp look; Large is 5 columns of 110dp tiles,
// closer to the stock Portal launcher.
private val LocalTileDp = compositionLocalOf { 88.dp }

private fun tileDpFor(size: String): Dp =
    when (size) {
      ImmortalSettings.SIZE_XL -> 140.dp
      ImmortalSettings.SIZE_LARGE -> 110.dp
      else -> 88.dp
    }

private fun gridColumnsFor(size: String): Int =
    when (size) {
      ImmortalSettings.SIZE_XL -> 4
      ImmortalSettings.SIZE_LARGE -> 5
      else -> 6
    }

private fun estimateWidgetSpan(minDp: Int, tileDp: Dp): Int {
  if (minDp <= 0) return HomeWidgetStore.DEFAULT_SPAN_X
  val tile = tileDp.value.coerceAtLeast(1f)
  return HomeWidgetStore.normalizeSpan(((minDp + tile - 1f) / tile).toInt().coerceAtLeast(1))
}

private fun customWidgetLabel(kind: String): String =
    when (kind) {
      HomeWidgetStore.KIND_WEATHER -> "Weather"
      HomeWidgetStore.KIND_WORLD_CLOCK -> "World Clock"
      HomeWidgetStore.KIND_TIMERS -> "Timers"
      else -> "Widget"
    }

@Composable
private fun HeaderBar(onScreensaver: () -> Unit) {
  var now by remember { mutableStateOf(Date()) }
  androidx.compose.runtime.LaunchedEffect(Unit) {
    while (true) {
      now = Date()
      delay(1000)
    }
  }
  val context = androidx.compose.ui.platform.LocalContext.current
  // The unit preference is re-read on resume and keys the fetch loop, so flipping
  // °F/°C in Immortal Settings updates the header the moment the user returns.
  var weatherUnit by remember { mutableStateOf(ImmortalSettings.load(context).weatherUnit) }
  // The clock format is likewise re-read on resume so flipping Auto/12h/24h in
  // Immortal Settings updates the header the moment the user returns home.
  var use24Hour by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  // The "hey" button only appears when Millennium is installed; re-checked on
  // resume so it shows up the moment the user finishes provisioning without
  // needing to relaunch the launcher.
  var heyPkg by remember { mutableStateOf(heyPackage(context)) }
  // The header mini-player toggle is re-read on resume so flipping it in Immortal
  // Settings shows/hides the player the moment the user returns home.
  var showMiniPlayer by remember { mutableStateOf(ImmortalSettings.load(context).showMiniPlayer) }
  // Live now-playing from the device's media session. The hub notifies off-main, so
  // hop back to the main thread before touching Compose state.
  var nowPlaying by remember { mutableStateOf(NowPlayingHub.current) }
  val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
  DisposableEffect(Unit) {
    val l = NowPlayingHub.Listener { s -> mainHandler.post { nowPlaying = s } }
    NowPlayingHub.addListener(l) // replays current immediately
    onDispose { NowPlayingHub.removeListener(l) }
  }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) {
        weatherUnit = ImmortalSettings.load(context).weatherUnit
        use24Hour = ImmortalSettings.use24HourClock(context)
        heyPkg = heyPackage(context)
        showMiniPlayer = ImmortalSettings.load(context).showMiniPlayer
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  val weather by produceState(initialValue = "", weatherUnit) {
    // Retry soon on failure (e.g. a transient geolocation rate-limit), then
    // refresh periodically. Location is cached after the first success.
    while (true) {
      val w = withContext(Dispatchers.IO) { Weather.fetch(context) }
      if (w.isNotBlank()) {
        value = w
        delay(30L * 60 * 1000) // refresh every 30 min
      } else {
        delay(60L * 1000) // retry in 1 min
      }
    }
  }
  val battery = batteryState()

  // Quick-connect: the remote header button enables the remote and pops a QR/PIN modal.
  var showPair by remember { mutableStateOf(false) }
  var pairUrl by remember { mutableStateOf<String?>(null) }
  var pairPin by remember { mutableStateOf<String?>(null) }

  // Layout: the big clock anchors the left; the weather and date stack on the
  // right, right-aligned, so the header reads as a balanced pair of blocks. The
  // clock and the right-hand stack are centred against each other.
  Row(
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // Clock anchors the top-left corner; the action buttons sit just to its right
    // (now that there's a group of them, leading with the buttons looked off).
    Text(
        SimpleDateFormat(if (use24Hour) "H:mm" else "h:mm", Locale.getDefault()).format(now),
        color = Color.White,
        fontSize = 72.sp,
        fontWeight = FontWeight.Light,
        lineHeight = 72.sp,
    )
    Spacer(Modifier.size(28.dp))
    // Screensaver entry — the stock launcher's stacked-photo icon so the affordance
    // reads the same as the Portal users already know.
    Surface(
        color = Color(0x33FFFFFF),
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier =
            Modifier.size(56.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
              onScreensaver()
            },
    ) {
      Box(contentAlignment = Alignment.Center) { StackedPhotoIcon() }
    }
    // Remote — one-tap "control from your phone": turn the remote on (if off) and show a
    // QR/PIN modal, so a phone pairs without digging into Settings.
    Spacer(Modifier.size(14.dp))
    Surface(
        color = Color(0x33FFFFFF),
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier =
            Modifier.size(56.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
              val (u, p) = enableRemoteAndMintPin(context)
              pairUrl = u
              pairPin = p
              showPair = true
            },
    ) {
      Box(contentAlignment = Alignment.Center) { RemoteGlyph() }
    }
    // "Hey" button — push-to-talk for the active assistant. The launcher stays
    // dumb: it just broadcasts the trigger; Millennium owns assistant selection,
    // the premium gate and the falcon mic handoff. Only shown when Millennium is
    // installed (so a bare launcher has no dead button).
    heyPkg?.let { pkg ->
      Spacer(Modifier.size(14.dp))
      Surface(
          color = Color(0x33FFFFFF),
          shape = androidx.compose.foundation.shape.CircleShape,
          modifier =
              Modifier.size(56.dp).tvFocusable(
                  shape = androidx.compose.foundation.shape.CircleShape,
                  onLongClick = { openHeyPicker(context, pkg) },
              ) {
                fireHey(context, pkg)
              },
      ) {
        Box(contentAlignment = Alignment.Center) { MicGlyph() }
      }
    }
    // Mini-player — sits in the left action cluster, only while something's playing.
    // When shown it takes the flexible space (so its text uses the header width before
    // it scrolls); otherwise a weight spacer pushes the weather/date to the right.
    val np = nowPlaying
    if (showMiniPlayer && np != null && np.active) {
      Spacer(Modifier.size(16.dp))
      MiniPlayer(np, modifier = Modifier.weight(1f).padding(end = 16.dp))
    } else {
      Spacer(Modifier.weight(1f))
    }
    Column(horizontalAlignment = Alignment.End) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        // Charge level is OPTIONAL — only Portal Go has a battery; mains-powered
        // Portals report no battery present, so we render nothing for them.
        if (battery.present) {
          BatteryIndicator(percent = battery.percent, charging = battery.charging)
        }
        if (weather.isNotBlank()) {
          Text(weather, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Light)
        }
      }
      Text(
          DateFormatter.format(now, "EEEEMMMMd"),
          color = Color(0xFFAAAAAA),
          fontSize = 20.sp,
          fontWeight = FontWeight.Normal,
          letterSpacing = 0.5.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
    }
  }

  if (showPair) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { showPair = false }) {
      Surface(color = Color(0xFF101012), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
          Text("Control from your phone", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
          Text(
              "Scan with a phone on the same Wi-Fi, or open the address and enter the code.",
              color = Color(0xFF9A9A9A),
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
          )
          RemotePairCard(pairUrl, pairPin)
          Spacer(Modifier.size(16.dp))
          PairDoneButton { showPair = false }
        }
      }
    }
  }
}

/**
 * Home-header mini-player: premium card with rounded album art, glass-style card background,
 * a subtle left PrimeBlue border indicating active playback, and refined typography.
 * The art area is the play/pause touch target. Driven by [NowPlayingHub].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniPlayer(state: NowPlayingState, modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Some media apps hand cover art as a URI rather than an embedded bitmap. Resolve it off the
  // main thread (content:// or http) so the header shows the cover too — the screensaver and the
  // phone remote already do this via [MediaArt]; the mini-player was the one place that didn't.
  var urlArt by remember(state.artUrl, state.artBitmap) { mutableStateOf<android.graphics.Bitmap?>(null) }
  androidx.compose.runtime.LaunchedEffect(state.artUrl, state.artBitmap) {
    urlArt =
        if (state.artBitmap == null && state.artUrl.isNotBlank())
            withContext(Dispatchers.IO) { MediaArt.resolveUri(context, state.artUrl) }
        else null
  }
  val cover = state.artBitmap ?: urlArt
  // Card background: surfaceContainer-style dark translucent glass with 16dp rounded corners.
  // A 2dp left border in PrimeBlue signals active playback.
  Row(
      modifier =
          modifier
              .clip(RoundedCornerShape(16.dp))
              .background(Color(0x22FFFFFF)) // ~13% white — glass surface
              .border(
                  width = 2.dp,
                  brush = Brush.verticalGradient(
                      listOf(PrimeBlue, PrimeBlue.copy(alpha = 0.5f)),
                  ),
                  shape = RoundedCornerShape(16.dp),
              )
              .padding(horizontal = 10.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // Album art with 8dp rounded corners; tapping toggles playback.
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .tvFocusable(RoundedCornerShape(8.dp)) { NowPlayingHub.playPause() },
        contentAlignment = Alignment.Center,
    ) {
      val bmp = cover
      if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp),
        )
      } else {
        Box(Modifier.size(48.dp).background(Color(0x33FFFFFF)))
      }
      // Subtle dark scrim + play/pause glyph overlay on the art.
      Box(
          Modifier.size(48.dp).background(Color(0x3D000000)),
          contentAlignment = Alignment.Center,
      ) {
        PlayPauseGlyph(playing = state.state == PlaybackState.PLAYING)
      }
    }
    Spacer(Modifier.size(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      // titleSmall equivalent: 14sp Bold, ContentPrimary
      Text(
          state.title,
          color = ContentPrimary,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          modifier = Modifier.basicMarquee(),
      )
      if (state.artist.isNotBlank()) {
        // labelSmall equivalent: 12sp, ContentSecondary
        Text(
            state.artist,
            color = ContentSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp).basicMarquee(),
        )
      }
    }
  }
}

@Composable
private fun PlayPauseGlyph(playing: Boolean) {
  Canvas(modifier = Modifier.size(30.dp)) {
    val w = size.minDimension
    if (playing) {
      // Two slim, fully-rounded bars with a clear gap between them — close-set square
      // bars blur into a single block from across the room.
      val barW = w * 0.13f
      val top = w * 0.24f
      val barH = w * 0.52f
      val r = CornerRadius(barW * 0.5f, barW * 0.5f)
      drawRoundRect(Color.White, topLeft = Offset(w * 0.30f, top), size = Size(barW, barH), cornerRadius = r)
      drawRoundRect(Color.White, topLeft = Offset(w * 0.57f, top), size = Size(barW, barH), cornerRadius = r)
    } else {
      drawPath(
          Path().apply {
            moveTo(w * 0.32f, w * 0.22f)
            lineTo(w * 0.32f, w * 0.78f)
            lineTo(w * 0.80f, w * 0.50f)
            close()
          },
          Color.White,
      )
    }
  }
}

/** What the forecast widget currently has to show. */
private sealed interface ForecastState {
  /** First fetch still in flight — render nothing so the bar doesn't flash. */
  object Loading : ForecastState
  /** Tried and failed with nothing cached — show a friendly note instead. */
  object Unavailable : ForecastState
  data class Ready(val forecast: Weather.Forecast) : ForecastState
}

// The two swipeable forecast pages, in left-to-right order.
private const val PAGE_HOURLY = 0
private const val PAGE_DAILY = 1

/**
 * Optional home-screen forecast, shown full-width below the app grid when enabled in
 * Immortal Settings ▸ Weather. One network call fetches both views; the user swipes
 * left/right between the hourly and 7-day pages. [mode] picks which page shows first
 * (and jumps to it if the setting changes). [fahrenheit] only keys a re-fetch when the
 * unit changes — the unit itself is resolved inside [Weather.fetchForecast].
 *
 * Failure handling: the fetch retries every minute until it succeeds. While the very
 * first attempt is in flight nothing is drawn (no flash). If it can't be reached and
 * we have no forecast yet, a quiet "unavailable" note replaces the data; once a
 * forecast has loaded, a later failed refresh keeps the last good one on screen rather
 * than blanking it.
 */
@Composable
private fun WeatherWidget(mode: String, fahrenheit: Boolean) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Keyed on the unit only: switching pages is a local swipe, not a re-fetch.
  val state by
      produceState<ForecastState>(initialValue = ForecastState.Loading, fahrenheit) {
        while (true) {
          val f = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
          if (f != null) {
            value = ForecastState.Ready(f)
            delay(30L * 60 * 1000) // refresh every 30 min
          } else {
            // Keep showing the last good forecast if we have one; only surface the
            // note when there's nothing to display.
            if (value !is ForecastState.Ready) value = ForecastState.Unavailable
            delay(60L * 1000) // retry in 1 min
          }
        }
      }
  if (state is ForecastState.Loading) return

  val startPage = if (mode == ImmortalSettings.WIDGET_DAILY) PAGE_DAILY else PAGE_HOURLY
  val pagerState = rememberPagerState(initialPage = startPage) { 2 }
  // Follow the setting: if the user changes the default in Immortal Settings, jump to
  // that page when they come back (no-op on first composition, where it already matches).
  LaunchedEffect(mode) {
    if (pagerState.currentPage != startPage) pagerState.scrollToPage(startPage)
  }

  val ready = state as? ForecastState.Ready
  Surface(
      color = Color(0x14FFFFFF),
      shape = RoundedCornerShape(20.dp),
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
  ) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
      // Header: the current page's title, plus a two-dot indicator hinting the swipe.
      Row(
          modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            when {
              ready == null -> "Forecast"
              pagerState.currentPage == PAGE_DAILY -> "7-day forecast"
              else -> "Hourly forecast"
            },
            color = Color(0xFFBFBFBF),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (ready != null) PageDots(selected = pagerState.currentPage, count = 2)
      }
      if (ready != null) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
          // Each page's cells share the width evenly, spanning the whole card.
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (page == PAGE_HOURLY) {
              ready.forecast.hours.forEach { HourCell(it, Modifier.weight(1f)) }
            } else {
              ready.forecast.days.forEach { DayCell(it, Modifier.weight(1f)) }
            }
          }
        }
      } else {
        // Unavailable: no connection / location yet. Retries quietly in the
        // background, so no action is needed from the user.
        Text(
            "Forecast unavailable. It'll appear once your Portal is back online.",
            color = Color(0xFF9A9A9A),
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
      }
    }
  }
}

/** Small page-position dots, hinting the forecast can be swiped between its pages. */
@Composable
private fun PageDots(selected: Int, count: Int) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    repeat(count) { i ->
      val isActive = i == selected
      Box(
          modifier =
              Modifier.size(if (isActive) 6.dp else 4.dp)
                  .clip(androidx.compose.foundation.shape.CircleShape)
                  .background(if (isActive) PrimeBlue else Color(0x55FFFFFF)))
    }
  }
}

@Composable
private fun HourCell(h: Weather.HourForecast, modifier: Modifier = Modifier) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.padding(vertical = 4.dp),
  ) {
    Text(h.label, color = Color(0xFFCFCFCF), fontSize = 13.sp, maxLines = 1)
    Text(Weather.emoji(h.code), fontSize = 26.sp, modifier = Modifier.padding(vertical = 6.dp))
    Text("${h.temp}°", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun DayCell(d: Weather.DayForecast, modifier: Modifier = Modifier) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.padding(vertical = 4.dp),
  ) {
    Text(d.label, color = Color(0xFFCFCFCF), fontSize = 13.sp, maxLines = 1)
    Text(Weather.emoji(d.code), fontSize = 26.sp, modifier = Modifier.padding(vertical = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("${d.hi}°", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
      Text("${d.lo}°", color = Color(0xFF9A9A9A), fontSize = 16.sp)
    }
  }
}

// --- "Hey" assistant trigger -------------------------------------------------
// Millennium publishes an exported receiver for this action; the launcher only
// fires it. Release build preferred, debug fallback for sideloaded test devices.
private const val HEY_TRIGGER_ACTION = "com.millennium.TRIGGER_ASSISTANT"
private val HEY_PACKAGES = listOf("com.millennium", "com.millennium.debug")

/** The installed Millennium ("hey") package, release preferred, or null if absent. */
private fun heyPackage(context: android.content.Context): String? =
    HEY_PACKAGES.firstOrNull { pkg ->
      try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
      } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
      }
    }

/** Ask Millennium to activate the user's active assistant (same path as a wake word). */
private fun fireHey(context: android.content.Context, pkg: String) {
  context.sendBroadcast(Intent(HEY_TRIGGER_ACTION).setPackage(pkg))
}

/** Millennium's assistant picker (premium: choose which assistant to talk to). */
private const val HEY_PICKER_ACTIVITY = "com.millennium.ui.HeyPickerActivity"

/** Long-press: open Millennium's picker. Falls back to a normal trigger if the
 *  installed Millennium predates the picker (so the gesture is never a dead end). */
private fun openHeyPicker(context: android.content.Context, pkg: String) {
  val ok =
      runCatching {
            context.startActivity(
                Intent()
                    .setClassName(pkg, HEY_PICKER_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          }
          .isSuccess
  if (!ok) fireHey(context, pkg)
}

/** White line-art microphone glyph for the header "hey" button. */
@Composable
private fun MicGlyph() {
  Canvas(modifier = Modifier.size(28.dp)) {
    val w = size.minDimension
    val s = w * 0.08f
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Capsule mic body.
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.38f, w * 0.16f),
        size = Size(w * 0.24f, w * 0.40f),
        cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
        style = stroke,
    )
    // Cradle arc hugging the bottom of the body.
    drawArc(
        color = Color.White,
        startAngle = 20f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(w * 0.26f, w * 0.24f),
        size = Size(w * 0.48f, w * 0.48f),
        style = stroke,
    )
    // Stem + base.
    drawLine(Color.White, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.5f, w * 0.84f), strokeWidth = s)
    drawLine(Color.White, Offset(w * 0.38f, w * 0.84f), Offset(w * 0.62f, w * 0.84f), strokeWidth = s)
  }
}

/** White line-art phone glyph for the header "remote" (control from your phone) button. */
@Composable
private fun RemoteGlyph() {
  Canvas(modifier = Modifier.size(28.dp)) {
    val w = size.minDimension
    val s = w * 0.08f
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Phone body (portrait rounded rect).
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.34f, w * 0.12f),
        size = Size(w * 0.32f, w * 0.66f),
        cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
        style = stroke,
    )
    // Speaker slit near the top, home dot near the bottom.
    drawLine(Color.White, Offset(w * 0.44f, w * 0.22f), Offset(w * 0.56f, w * 0.22f), strokeWidth = s)
    drawCircle(color = Color.White, radius = w * 0.045f, center = Offset(w * 0.5f, w * 0.67f))
  }
}

/** White line-art photo glyph (single frame), matching the stock screensaver. */
@Composable
private fun StackedPhotoIcon() {
  Canvas(modifier = Modifier.size(30.dp)) {
    val w = size.minDimension
    val s = w * 0.075f // stroke width
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Photo frame outline.
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.16f, w * 0.20f),
        size = Size(w * 0.68f, w * 0.60f),
        cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
        style = stroke,
    )
    // Sun.
    drawCircle(
        color = Color.White,
        radius = w * 0.075f,
        center = Offset(w * 0.36f, w * 0.40f),
        style = Stroke(width = s),
    )
    // Mountains.
    val path =
        androidx.compose.ui.graphics.Path().apply {
          moveTo(w * 0.20f, w * 0.72f)
          lineTo(w * 0.40f, w * 0.48f)
          lineTo(w * 0.52f, w * 0.60f)
          lineTo(w * 0.62f, w * 0.50f)
          lineTo(w * 0.80f, w * 0.72f)
        }
    drawPath(path, color = Color.White, style = stroke)
  }
}

/** Bottom-right Manage / Done toggle. */
@Composable
private fun EditButton(editMode: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = if (editMode) Color(0xFFE53935) else Color(0xCC2B2B2B),
      shape = androidx.compose.foundation.shape.CircleShape,
      modifier =
          modifier.size(60.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
            onClick()
          },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(if (editMode) "✓" else "✎", color = Color.White, fontSize = 28.sp)
    }
  }
}

/** Edit-mode "clean up" button — re-packs every tile into a tidy ordered grid. */
@Composable
private fun TidyButton(onClick: () -> Unit) {
  val path = remember { PathParser().parsePathString(ICON_WIDGETS).toPath() }
  Surface(
      color = Color(0xCC2B2B2B),
      shape = androidx.compose.foundation.shape.CircleShape,
      modifier =
          Modifier.size(60.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
            onClick()
          },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Canvas(Modifier.size(26.dp)) {
        val s = size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
      }
    }
  }
}

private data class BatteryReading(val present: Boolean, val percent: Int, val charging: Boolean)

/** Reads the device battery, updating live. Returns present=false on devices
 * without a battery (Portal+, Portal TV, Portal Mini), so callers can hide it. */
@Composable
private fun batteryState(): BatteryReading {
  val context = androidx.compose.ui.platform.LocalContext.current
  var reading by remember { mutableStateOf(BatteryReading(false, 0, false)) }
  DisposableEffect(Unit) {
    fun parse(i: Intent?): BatteryReading {
      if (i == null) return BatteryReading(false, 0, false)
      val present = i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
      val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
      val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
      val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
      val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
      val charging =
          status == BatteryManager.BATTERY_STATUS_CHARGING ||
              status == BatteryManager.BATTERY_STATUS_FULL
      return BatteryReading(present && pct >= 0, pct.coerceIn(0, 100), charging)
    }
    val receiver =
        object : android.content.BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) {
            reading = parse(i)
          }
        }
    // Registering for the sticky ACTION_BATTERY_CHANGED returns the current value.
    val sticky =
        context.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    reading = parse(sticky)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }
  return reading
}

/** Minimal drawn battery glyph + percent, green while charging, red when low. */
@Composable
private fun BatteryIndicator(percent: Int, charging: Boolean) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Canvas(modifier = Modifier.size(width = 36.dp, height = 18.dp)) {
      val cap = 3.dp.toPx()
      val bodyW = size.width - cap
      val stroke = 2.dp.toPx()
      drawRoundRect(
          color = Color.White,
          topLeft = Offset(stroke / 2, stroke / 2),
          size = Size(bodyW - stroke, size.height - stroke),
          cornerRadius = CornerRadius(4f, 4f),
          style = Stroke(width = stroke),
      )
      drawRoundRect(
          color = Color.White,
          topLeft = Offset(bodyW, size.height * 0.3f),
          size = Size(cap, size.height * 0.4f),
          cornerRadius = CornerRadius(2f, 2f),
      )
      val inset = stroke + 2.dp.toPx()
      val fillColor =
          when {
            charging -> Color(0xFF4CAF50)
            percent <= 15 -> Color(0xFFE53935)
            else -> Color.White
          }
      drawRoundRect(
          color = fillColor,
          topLeft = Offset(inset, inset),
          size =
              Size(
                  ((bodyW - inset * 2) * (percent / 100f)).coerceAtLeast(0f),
                  size.height - inset * 2,
              ),
          cornerRadius = CornerRadius(2f, 2f),
      )
    }
    Text("$percent%", color = Color.White, fontSize = 22.sp)
    if (charging) Text("⚡", color = Color(0xFF4CAF50), fontSize = 16.sp)
  }
}

@Composable
private fun PortalHomeTile(onClick: () -> Unit) {
  // Bridge to Meta's stock launcher — the only context allowed to open the
  // trusted-caller apps (Contacts, Camera, Photos), so this is how the user
  // reaches calling. Tapping Portal's home button returns to Immortal.
  BuiltInTile(
      label = "Calls",
      background = Color(0xFF1FA463),
      glyph = ICON_CALL,
      onClick = onClick,
  )
}

// Material-style glyph paths (24x24 viewport), rendered crisply as vectors.
private const val ICON_CALL =
    "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"
private const val ICON_DOWNLOAD = "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
private const val ICON_REFRESH =
    "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
private const val ICON_IMAGE =
    "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"
private const val ICON_WIDGETS =
    "M3 3h8v8H3V3zm10 0h8v5h-8V3zM3 13h5v8H3v-8zm7 0h11v8H10v-8z"
private const val ICON_HELP =
    "M11 18h2v-2h-2v2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5 0-2.21-1.79-4-4-4z"
private const val ICON_GEAR =
    "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"

/** A built-in launcher tile: a rounded colour tile with a centered white vector
 * glyph, styled to sit naturally beside real app icons. */
@Composable
private fun BuiltInTile(
    label: String,
    background: Color,
    glyph: String,
    onClick: () -> Unit,
) {
  val path = remember(glyph) { PathParser().parsePathString(glyph).toPath() }
  val tileDp = LocalTileDp.current
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxWidth().padding(4.dp).tvFocusable(RoundedCornerShape(22.dp)) { onClick() },
  ) {
    GlassTile(
        modifier = Modifier.size(tileDp),
    ) {
      Canvas(Modifier.size(46.dp * (tileDp / 88.dp))) {
        val s = size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        label,
        color = ContentSecondary,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
  }
}


/** Always-present Updates tile. Neutral + refresh glyph when up to date; blue +
 * download glyph (with a badge) when an update is ready. Shows transient status
 * text during a check or install. */
@Composable
private fun UpdatesTile(update: UpdateInfo?, status: String?, onClick: () -> Unit) {
  val available = update != null
  val label = status ?: if (available) "Update ready" else "Up to date"
  val background = if (available) Color(0xFF2D6CDF) else Color(0xFF2B2B2B)
  val glyph = if (available) ICON_DOWNLOAD else ICON_REFRESH
  val path = remember(glyph) { PathParser().parsePathString(glyph).toPath() }
  val tileDp = LocalTileDp.current
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxWidth().padding(4.dp).tvFocusable(RoundedCornerShape(22.dp)) { onClick() },
  ) {
    Box {
      Surface(
          color = background,
          shape = RoundedCornerShape(20.dp),
          modifier = Modifier.size(tileDp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Canvas(Modifier.size(44.dp * (tileDp / 88.dp))) {
            val s = size.minDimension / 24f
            scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
          }
        }
      }
      if (available) {
        Surface(
            color = Color(0xFFE53935),
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.size(18.dp).align(Alignment.TopEnd),
        ) {}
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        label,
        color = Color.White,
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun StoreTile(onClick: () -> Unit) {
  BuiltInTile(
      label = "App Store",
      background = Color(0xFF2D6CDF),
      glyph = ICON_DOWNLOAD,
      onClick = onClick,
  )
}

@Composable
private fun ToolsTile(onClick: () -> Unit) {
  BuiltInTile(
      label = "Tools",
      background = Color(0xFF5A5F6A),
      glyph = ICON_WIDGETS,
      onClick = onClick,
  )
}

@Composable
private fun AddWidgetButton(onClick: () -> Unit) {
  Surface(
      color = Color(0xFF5B6BC0),
      shape = RoundedCornerShape(30.dp),
      modifier = Modifier.width(124.dp).height(60.dp).tvFocusable(RoundedCornerShape(30.dp)) {
        onClick()
      },
  ) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
      Text("+", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
      Text("Widget", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun WidgetTile(
    widget: HomeWidgetStore.HomeWidget,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    editMode: Boolean,
    tileDp: Dp,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
  val info = remember(widget.appWidgetId) {
    if (widget.isAppWidget) manager.getAppWidgetInfo(widget.appWidgetId) else null
  }
  val height =
      tileDp * widget.spanY.toFloat() +
          20.dp * (widget.spanY - 1).coerceAtLeast(0).toFloat()

  LaunchedEffect(widget, tileDp) {
    if (widget.isAppWidget) updateWidgetSizeOptions(manager, widget, tileDp)
  }

  Box(modifier = modifier.fillMaxWidth().height(height).padding(4.dp)) {
    Surface(
        color = Color(0xFF252527),
        shape = RoundedCornerShape(20.dp),
        modifier =
            Modifier.fillMaxSize()
                .alpha(if (dimmed) 0.3f else 1f)
                .jiggle(editMode && !dimmed, widget.key.hashCode()),
    ) {
      if (!widget.isAppWidget) {
        ImmortalWidgetContent(widget = widget, modifier = Modifier.fillMaxSize())
      } else if (info != null) {
        AndroidView(
            factory = {
              host.createView(it, widget.appWidgetId, info).apply {
                setAppWidget(widget.appWidgetId, info)
                setPadding(0, 0, 0, 0)
                isFocusable = true
                isFocusableInTouchMode = true
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
              }
            },
            update = { view ->
              view.setAppWidget(widget.appWidgetId, info)
              view.isEnabled = !editMode
            },
            modifier = Modifier.fillMaxSize(),
        )
      } else {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF303033)),
            contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            WidgetGlyph()
            Text(
                "Widget unavailable",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                widget.providerPackage,
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 18.dp, end = 18.dp),
            )
          }
        }
      }
    }
    if (editMode) {
      // Dim/scrim only — the drag scrim and ✕ / resize controls are added by the enclosing
      // WidgetCell so they layer correctly above the hosted widget's own touch handling.
      Box(
          Modifier.fillMaxSize()
              .jiggle(!dimmed, widget.key.hashCode())
              .clip(RoundedCornerShape(20.dp))
              .background(Color(0x66000000)))
    }
  }
}

@Composable
private fun ImmortalWidgetContent(widget: HomeWidgetStore.HomeWidget, modifier: Modifier = Modifier) {
  when (widget.kind) {
    HomeWidgetStore.KIND_WEATHER -> ImmortalWeatherWidget(modifier)
    HomeWidgetStore.KIND_WORLD_CLOCK -> ImmortalWorldClockWidget(modifier)
    HomeWidgetStore.KIND_TIMERS -> ImmortalTimersWidget(modifier)
    HomeWidgetStore.KIND_CALENDAR -> ImmortalCalendarWidget(modifier)
    else -> UnknownImmortalWidget(widget.kind, modifier)
  }
}

@Composable
private fun ImmortalWidgetShell(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF7AA7FF),
    content: @Composable ColumnScope.() -> Unit,
) {
  Column(
      modifier =
          modifier
              .background(Color(0xFF161618), RoundedCornerShape(24.dp))
              .padding(18.dp),
  ) {
    Text(
        title.uppercase(Locale.getDefault()),
        color = accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
    Spacer(Modifier.size(8.dp))
    content()
  }
}

/**
 * "On this day" photo-memory strip: a glass row with a small thumbnail from the user's screensaver
 * photo folder and a caption; tapping opens the full photo-frame. Only shown when a local photo
 * folder is configured and readable — otherwise it stays hidden (no empty chrome).
 */
@Composable
private fun PhotoMemoryStrip(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val onOpen: () -> Unit = {
    runCatching {
      context.startActivity(
          Intent(context, PhotoFramePreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }
  val photo by
      produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
          runCatching {
            val cfg = ScreensaverConfig.load(context)
            val folder = cfg.folderPath
            if (cfg.usesFolder && folder != null && LocalMedia.isAccessible(folder)) {
              LocalMedia.enumerate(folder, includeVideo = false, max = 60)
                  .filterNot { it.isVideo }
                  .randomOrNull()
                  ?.let { android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            } else null
          }.getOrNull()
        }
      }
  val bmp = photo ?: return // no readable local photos → hide the strip entirely

  LiquidGlassWidget(modifier = modifier.wrapContentHeight()) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Image(
          bitmap = bmp,
          contentDescription = null,
          contentScale = androidx.compose.ui.layout.ContentScale.Crop,
          modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
      )
      Column(Modifier.weight(1f)) {
        Text("On this day", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("A memory from your photos", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      Text("›", color = Color.White.copy(alpha = 0.5f), fontSize = 24.sp)
    }
  }
}

/**
 * Home quick-controls: a glass grid of tap targets for the smart-home stuff a Portal owner reaches
 * for — a Hey Portal voice tile (launches the on-device assistant), and Home Assistant shortcuts
 * that open the HA app (the one-way MQTT bridge can't toggle devices, so we hand off to HA itself).
 * Tiles whose target isn't installed are hidden, so the grid only shows what actually works here.
 */
@Composable
private fun QuickControlsWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val pm = context.packageManager
  val haInstalled = remember {
    runCatching { pm.getLaunchIntentForPackage("io.homeassistant.companion.android") != null }.getOrDefault(false)
  }
  val assistantInstalled = remember {
    runCatching { pm.getLaunchIntentForPackage("com.portal.assistant") != null }.getOrDefault(false)
  }
  fun launch(pkg: String) {
    runCatching { pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) } }
  }

  data class QC(val glyph: String, val label: String, val tint: Color, val onTap: () -> Unit)
  val tiles = buildList {
    if (assistantInstalled) add(QC("🎙", "Hey Portal", Color(0xFF5E5CE6)) { launch("com.portal.assistant") })
    if (haInstalled) {
      add(QC("💡", "Lights", Color(0xFFFF9F0A)) { launch("io.homeassistant.companion.android") })
      add(QC("🎬", "Scenes", Color(0xFF0A84FF)) { launch("io.homeassistant.companion.android") })
      add(QC("🌡", "Climate", Color(0xFF30D158)) { launch("io.homeassistant.companion.android") })
      add(QC("🏠", "Home", Color(0xFF41BDF5)) { launch("io.homeassistant.companion.android") })
    }
  }
  if (tiles.isEmpty()) return // nothing installed to control → don't show an empty card

  LiquidGlassWidget(modifier = modifier.wrapContentHeight()) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Quick controls", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
      // Up to 4 tiles per row. When a row is full it stretches evenly (weight); a partial last row
      // uses fixed-width tiles so a lone tile (e.g. only Hey Portal) doesn't stretch across the card.
      tiles.chunked(4).forEach { row ->
        val full = row.size == 4
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          row.forEach { qc ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = (if (full) Modifier.weight(1f) else Modifier.width(88.dp))
                    .clip(RoundedCornerShape(16.dp)).clickable { qc.onTap() }
                    .background(qc.tint.copy(alpha = 0.22f)).padding(vertical = 12.dp),
            ) {
              Text(qc.glyph, fontSize = 24.sp)
              Text(qc.label, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
          }
          if (full) Unit else Spacer(Modifier.weight(1f))
        }
      }
    }
  }
}

/**
 * Family quick-call row: circular avatars that hand off to the stock Portal calling surface (the
 * only trusted caller for Contacts/calling). Names are placeholders until the user configures them;
 * the whole row routes through the same Portal-home bridge the Calls tile uses.
 */
@Composable
private fun FamilyCallRow(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Route to the stock Portal Home calling surface via its VIEW deep link — the same trusted-caller
  // bridge the Calls tile uses (Contacts rejects an untrusted direct launch). Marks the dream bridge
  // so the screensaver doesn't claw the frame back mid-handoff.
  val onCall: () -> Unit = {
    runCatching {
      DreamPolicy.markBridge(context)
      context.startActivity(
          Intent(Intent.ACTION_VIEW, Uri.parse("portal://launcher/home"))
              .setPackage("com.facebook.alohaapps.launcher")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }
  data class Person(val initial: String, val tint: List<Color>)
  // Placeholder contacts — until the user wires real ones, tapping any opens the Portal call surface.
  val people = listOf(
      Person("M", listOf(Color(0xFFFF9A9E), Color(0xFFFECFEF))),
      Person("D", listOf(Color(0xFFA18CD1), Color(0xFFFBC2EB))),
      Person("S", listOf(Color(0xFF84FAB0), Color(0xFF8FD3F4))),
  )
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    people.forEach { p ->
      Box(
          modifier = Modifier.size(56.dp).clip(RoundedCornerShape(50))
              .background(Brush.linearGradient(p.tint)).clickable { onCall() },
          contentAlignment = Alignment.Center,
      ) {
        Text(p.initial, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
      }
    }
    // Add / open full contacts.
    Box(
        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(50))
            .border(2.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(50)).clickable { onCall() },
        contentAlignment = Alignment.Center,
    ) {
      Text("＋", color = Color.White.copy(alpha = 0.8f), fontSize = 26.sp, fontWeight = FontWeight.Light)
    }
  }
}

/**
 * The glanceable "Today" strip for the left column: a frosted-glass card with three quick rows —
 * next calendar event, weather feels-like + hi/lo, and the moon phase with today's sunrise/sunset.
 * All data is already fetched elsewhere (weather + sun times keyless from Open-Meteo; moon phase is
 * pure date math). Re-reads the clock format on resume so times match the user's 12/24h setting.
 */
@Composable
private fun TodayStripWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var use24 by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  var fahrenheit by remember { mutableStateOf(ImmortalSettings.useFahrenheit(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) {
        use24 = ImmortalSettings.use24HourClock(context)
        fahrenheit = ImmortalSettings.useFahrenheit(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  val current by
      produceState<Weather.Current?>(initialValue = null, fahrenheit) {
        while (true) {
          value = withContext(Dispatchers.IO) { Weather.fetchCurrent(context) }
          delay(if (value == null) 60_000L else 30L * 60 * 1000)
        }
      }
  val forecast by
      produceState<Weather.Forecast?>(initialValue = null, fahrenheit) {
        value = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
      }
  val sun by
      produceState<Weather.SunTimes?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Weather.fetchSunTimes(context) }
      }
  val nextEvent by
      produceState<String?>(initialValue = null, use24) {
        val url = ScreensaverConfig.load(context).calendarUrl
        value = if (url.isNullOrBlank()) null
        else withContext(Dispatchers.IO) {
          runCatching {
            val now = System.currentTimeMillis()
            CalendarFeed.fetch(url)
                .filter { it.startMillis >= now - 60L * 60 * 1000 } // include one just-started
                .minByOrNull { it.startMillis }
                ?.let { calendarEventLine(it, now, use24) }
          }.getOrNull()
        }
      }

  val today = forecast?.days?.firstOrNull()
  val (moonGlyph, moonName) = remember { moonPhase(System.currentTimeMillis()) }
  val sunPair = sun?.formatted(use24)

  LiquidGlassWidget(modifier = modifier.wrapContentHeight()) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      TodayRow("▸", nextEvent ?: "No upcoming events", null)
      TodayRow(
          Weather.emoji(current?.code ?: 0),
          current?.let { "Feels like ${it.temp}°" } ?: "Weather unavailable",
          today?.let { "H:${it.hi}° L:${it.lo}°" },
      )
      TodayRow(
          moonGlyph,
          moonName,
          sunPair?.let { "↑${it.first}  ↓${it.second}" },
      )
    }
  }
}

@Composable
private fun TodayRow(glyph: String, text: String, trailing: String?) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(glyph, color = Color.White.copy(alpha = 0.92f), fontSize = 16.sp, modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
    Text(
        text,
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    if (trailing != null) {
      Text(trailing, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1)
    }
  }
}

/**
 * Moon phase (glyph + name) from the epoch, via the standard synodic-month approximation
 * (29.53059 days from a known new moon). Pure date math — no network, good to ~1 day.
 */
private fun moonPhase(nowMillis: Long): Pair<String, String> {
  val synodic = 29.530588853
  val knownNew = 947182440000L // 2000-01-06 18:14 UTC, a reference new moon
  val days = (nowMillis - knownNew).toDouble() / 86_400_000.0
  val age = ((days % synodic) + synodic) % synodic
  return when {
    age < 1.84 -> "🌑" to "New moon"
    age < 5.53 -> "🌒" to "Waxing crescent"
    age < 9.22 -> "🌓" to "First quarter"
    age < 12.91 -> "🌔" to "Waxing gibbous"
    age < 16.61 -> "🌕" to "Full moon"
    age < 20.30 -> "🌖" to "Waning gibbous"
    age < 23.99 -> "🌗" to "Last quarter"
    age < 27.68 -> "🌘" to "Waning crescent"
    else -> "🌑" to "New moon"
  }
}

@Composable
private fun ImmortalWeatherWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var fahrenheit by remember { mutableStateOf(ImmortalSettings.useFahrenheit(context)) }
  // Forecast mode from Settings > Appearance > Weather widget: Off / Hourly / Daily. Re-read on
  // resume so a change applies when the user returns home.
  var forecastMode by remember { mutableStateOf(ImmortalSettings.load(context).weatherWidget) }
  var use24 by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) {
        fahrenheit = ImmortalSettings.useFahrenheit(context)
        forecastMode = ImmortalSettings.load(context).weatherWidget
        use24 = ImmortalSettings.use24HourClock(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  val current by
      produceState<Weather.Current?>(initialValue = null, fahrenheit) {
        while (true) {
          value = withContext(Dispatchers.IO) { Weather.fetchCurrent(context) }
          delay(if (value == null) 60_000L else 30L * 60 * 1000)
        }
      }
  val forecast by
      produceState<Weather.Forecast?>(initialValue = null, fahrenheit) {
        value = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
      }
  val sunTimes by
      produceState<Weather.SunTimes?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Weather.fetchSunTimes(context) }
      }
  val code = current?.code ?: 0
  val isDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) in 6..18
  val (gTop, gBottom) = weatherGradient(code, isDay)
  // Dynamic gradient card that matches the weather (iOS-style), city + big temp top-left,
  // condition glyph top-right, and an hourly strip along the bottom.
  Box(
      modifier =
          modifier
              .clip(RoundedCornerShape(24.dp))
              .background(Brush.linearGradient(listOf(gTop, gBottom)))
              .padding(18.dp),
  ) {
    Column(Modifier.fillMaxSize()) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
          Text(
              current?.city?.ifBlank { "Weather" } ?: "Weather",
              color = Color.White,
              fontSize = 18.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Text(
              current?.let { "${it.temp}°" } ?: "—",
              color = Color.White,
              fontSize = 46.sp,
              fontWeight = FontWeight.Light,
          )
          // Always-present hero subline: condition · today's H/L · sunset — so the card reads as a
          // premium weather hero even when the forecast strip mode is Off.
          val today = forecast?.days?.firstOrNull()
          val condition = weatherConditionText(code)
          val sunsetLabel = sunTimes?.formatted(use24)?.second
          Text(
              buildString {
                append(condition)
                today?.let { append("  ·  H:${it.hi}° L:${it.lo}°") }
                sunsetLabel?.let { append("  ·  ↓$it") }
              },
              color = Color(0xE6FFFFFF),
              fontSize = 14.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 2.dp),
          )
        }
        Text(Weather.emoji(code), fontSize = 34.sp)
      }
      Spacer(Modifier.weight(1f))
      // Forecast strip honors the Weather-widget mode: Off shows just the current conditions,
      // Hourly shows the next 5 hours, Daily shows the next 5 days with high/low.
      when (forecastMode) {
        ImmortalSettings.WIDGET_HOURLY -> {
          val hours = forecast?.hours?.take(5).orEmpty()
          if (hours.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              hours.forEach { h ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                  Text(h.label, color = Color(0xCCFFFFFF), fontSize = 11.sp, maxLines = 1)
                  Text(Weather.emoji(h.code), fontSize = 18.sp, modifier = Modifier.padding(vertical = 3.dp))
                  Text("${h.temp}°", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
              }
            }
          }
        }
        ImmortalSettings.WIDGET_DAILY -> {
          val days = forecast?.days?.take(5).orEmpty()
          if (days.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              days.forEach { d ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                  Text(d.label, color = Color(0xCCFFFFFF), fontSize = 11.sp, maxLines = 1)
                  Text(Weather.emoji(d.code), fontSize = 18.sp, modifier = Modifier.padding(vertical = 3.dp))
                  Text("${d.hi}°", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                  Text("${d.lo}°", color = Color(0x99FFFFFF), fontSize = 11.sp)
                }
              }
            }
          }
        }
        else -> {} // WIDGET_OFF: current conditions only, no forecast strip.
      }
    }
  }
}

/** A weather-matching two-stop gradient (top → bottom), varying with the condition and day/night. */
private fun weatherGradient(code: Int, isDay: Boolean): Pair<Color, Color> {
  if (!isDay) {
    return when {
      code in 51..99 -> Color(0xFF2A3340) to Color(0xFF49586B)
      code == 3 || code in 45..48 -> Color(0xFF263041) to Color(0xFF3C4A5E)
      else -> Color(0xFF18233F) to Color(0xFF36527E)
    }
  }
  return when {
    code in 71..77 -> Color(0xFF7E93A8) to Color(0xFFBFCEDC) // snow
    code in 51..67 || code in 80..82 -> Color(0xFF45607A) to Color(0xFF7791A6) // rain
    code in 95..99 -> Color(0xFF3A4A5C) to Color(0xFF5E7286) // thunder
    code == 3 -> Color(0xFF5E7E98) to Color(0xFF93ABC0) // overcast
    code in 45..48 -> Color(0xFF7E8E9C) to Color(0xFFAEBBC6) // fog
    else -> Color(0xFF3E8BD0) to Color(0xFF77B8E8) // clear / partly (the blue from the mock)
  }
}

@Composable
private fun ImmortalWorldClockWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var zones by remember { mutableStateOf(ImmortalSettings.worldClockZones(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) zones = ImmortalSettings.worldClockZones(context)
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  var now by remember { mutableStateOf(Date()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = Date()
      delay(1000)
    }
  }
  ImmortalWidgetShell(title = "World Clock", accent = Color(0xFFFFC857), modifier = modifier) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
      zones.take(4).forEach { zone ->
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          AnalogClock(zone, now, Modifier.fillMaxWidth().aspectRatio(1f))
          Spacer(Modifier.size(6.dp))
          Text(
              worldClockLabel(zone),
              color = Color.White,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Text(worldClockOffset(zone, now), color = Color(0xFF9A9A9A), fontSize = 10.sp, maxLines = 1)
        }
      }
    }
  }
}

/** Short city label from a tz id, e.g. "America/New_York" → "New York". */
private fun worldClockLabel(zoneId: String): String =
    zoneId.substringAfterLast('/').replace('_', ' ')

/** Offset of [zoneId] vs the device's zone, e.g. "+5h" / "-3h" / "same". */
private fun worldClockOffset(zoneId: String, now: Date): String {
  val local = TimeZone.getDefault().getOffset(now.time)
  val there = TimeZone.getTimeZone(zoneId).getOffset(now.time)
  val diffH = (there - local) / 3_600_000
  return when {
    diffH == 0 -> "same"
    diffH > 0 -> "+${diffH}h"
    else -> "${diffH}h"
  }
}

/** A small analog clock face for [zoneId], white by day / dark by night, with an orange seconds hand. */
@Composable
private fun AnalogClock(zoneId: String, now: Date, modifier: Modifier = Modifier) {
  val cal = remember(zoneId) { java.util.Calendar.getInstance(TimeZone.getTimeZone(zoneId)) }
  cal.time = now
  val hour = cal.get(java.util.Calendar.HOUR)
  val minute = cal.get(java.util.Calendar.MINUTE)
  val second = cal.get(java.util.Calendar.SECOND)
  val night = cal.get(java.util.Calendar.HOUR_OF_DAY) !in 6..18
  val face = if (night) Color(0xFF1F1F22) else Color.White
  val ink = if (night) Color.White else Color(0xFF1A1A1A)
  val tick = if (night) Color(0x66FFFFFF) else Color(0x55000000)
  Canvas(modifier) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(face, radius = r, center = c)
    // Hour ticks.
    for (i in 0 until 12) {
      val a = Math.toRadians(i * 30.0)
      val sin = kotlin.math.sin(a).toFloat()
      val cos = kotlin.math.cos(a).toFloat()
      drawLine(
          tick,
          start = Offset(c.x + sin * r * 0.82f, c.y - cos * r * 0.82f),
          end = Offset(c.x + sin * r * 0.94f, c.y - cos * r * 0.94f),
          strokeWidth = r * 0.05f,
      )
    }
    fun hand(angleDeg: Double, length: Float, width: Float, color: Color) {
      val a = Math.toRadians(angleDeg)
      drawLine(
          color,
          start = c,
          end = Offset(c.x + (kotlin.math.sin(a) * length).toFloat(), c.y - (kotlin.math.cos(a) * length).toFloat()),
          strokeWidth = width,
          cap = StrokeCap.Round,
      )
    }
    hand((hour % 12 + minute / 60.0) * 30.0, r * 0.5f, r * 0.10f, ink)
    hand((minute + second / 60.0) * 6.0, r * 0.78f, r * 0.07f, ink)
    hand(second * 6.0, r * 0.85f, r * 0.03f, Color(0xFFFF9500))
    drawCircle(Color(0xFFFF9500), radius = r * 0.06f, center = c)
  }
}

@Composable
private fun ImmortalTimersWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var state by remember { mutableStateOf(TimerStore.load(context)) }
  var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
  var showCustom by remember { mutableStateOf(false) }
  // Live updates if the timer is changed elsewhere (e.g. another Timers widget instance).
  DisposableEffect(Unit) {
    val l: () -> Unit = {
      state = TimerStore.load(context)
      nowMs = System.currentTimeMillis()
    }
    TimerStore.addListener(l)
    onDispose { TimerStore.removeListener(l) }
  }
  // Tick the countdown while it's running. When it hits zero we stop ticking but DON'T clear —
  // the exact alarm ([TimerAlarmReceiver]) flips the timer to ringing, which the listener picks up.
  LaunchedEffect(state.running, state.endsAt) {
    while (state.running) {
      nowMs = System.currentTimeMillis()
      if (state.remaining(nowMs) <= 0L) break
      delay(250)
    }
  }
  val remainingMs = state.remaining(nowMs)
  val mins = (remainingMs / 60_000).toInt()
  val secs = ((remainingMs / 1000) % 60).toInt()
  ImmortalWidgetShell(title = "Timers", accent = Color(0xFFFF9F0A), modifier = modifier) {
    Text(
        when {
          state.ringing -> "Time's up"
          remainingMs > 0 -> "%d:%02d".format(mins, secs)
          else -> "Ready"
        },
        color = if (state.ringing) Color(0xFFFF9F0A) else Color.White,
        fontSize = 34.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.size(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state.ringing) {
        TimerChip("Stop") { TimerAlarm.stop(context) }
      } else if (remainingMs > 0) {
        TimerChip(if (state.running) "Pause" else "Start") {
          if (state.running) TimerStore.pause(context) else TimerStore.resume(context)
        }
        TimerChip("Reset") {
          TimerStore.clear(context)
        }
      } else {
        listOf(5, 10, 30).forEach { minutes ->
          TimerChip("${minutes}m") { TimerStore.start(context, minutes * 60_000L) }
        }
        TimerChip("Custom") { showCustom = true }
      }
    }
  }
  if (showCustom) {
    CustomTimerDialog(
        onStart = { ms ->
          if (ms > 0) TimerStore.start(context, ms)
          showCustom = false
        },
        onDismiss = { showCustom = false },
    )
  }
}

/** A keyboard-free duration picker (±steppers, remote/touch friendly) for a custom timer. */
@Composable
private fun CustomTimerDialog(onStart: (Long) -> Unit, onDismiss: () -> Unit) {
  var minutes by remember { mutableStateOf(5) }
  var seconds by remember { mutableStateOf(0) }
  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
    Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(24.dp)) {
      Column(
          modifier = Modifier.padding(28.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text("Custom timer", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(24.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
          DurationStepper("min", minutes, step = 1) { minutes = (minutes + it).coerceIn(0, 180) }
          Text(":", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light)
          DurationStepper("sec", seconds, step = 5) { seconds = (seconds + it + 60) % 60 }
        }
        Spacer(Modifier.size(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          TimerChip("Cancel") { onDismiss() }
          TimerChip("Start ${minutes}:%02d".format(seconds)) {
            onStart((minutes * 60 + seconds) * 1000L)
          }
        }
      }
    }
  }
}

@Composable
private fun DurationStepper(label: String, value: Int, step: Int, onChange: (Int) -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label.uppercase(Locale.getDefault()), color = Color(0xFF9A9A9A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.size(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      StepButton("−") { onChange(-step) }
      Text("%02d".format(value), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
      StepButton("+") { onChange(step) }
    }
  }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
  Surface(
      color = Color(0x22FFFFFF),
      shape = CircleShape,
      modifier = Modifier.size(44.dp).tvFocusable(CircleShape) { onClick() },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
    }
  }
}

@Composable
private fun TimerChip(label: String, onClick: () -> Unit) {
  Surface(
      color = Color(0x22FFFFFF),
      shape = RoundedCornerShape(999.dp),
      modifier = Modifier.tvFocusable(RoundedCornerShape(999.dp)) { onClick() },
  ) {
    Text(
        label,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
    )
  }
}

@Composable
private fun UnknownImmortalWidget(kind: String, modifier: Modifier = Modifier) {
  Box(modifier.background(Color(0xFF303033)), contentAlignment = Alignment.Center) {
    Text(kind, color = Color.White, fontSize = 16.sp)
  }
}

@Composable
private fun WidgetPickerOverlay(
    providers: List<WidgetProviderEntry>,
    status: String?,
    onPick: (WidgetProviderEntry) -> Unit,
    onDismiss: () -> Unit,
) {
  val noRipple = remember { MutableInteractionSource() }
  BackHandler { onDismiss() }
  val gridFocus = remember { FocusRequester() }
  LaunchedEffect(providers) { runCatching { gridFocus.requestFocus() } }

  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) {
                  if (e.type == KeyEventType.KeyUp) onDismiss()
                  true
                } else false
              }
              .background(Color(0xDD000000))
              .clickable(interactionSource = noRipple, indication = null) { onDismiss() },
  ) {
    Surface(
        color = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(28.dp),
        modifier =
            Modifier.widthIn(max = 920.dp)
                .fillMaxWidth(0.78f)
                .heightIn(max = 620.dp)
                .clickable(interactionSource = noRipple, indication = null) {},
    ) {
      Column(modifier = Modifier.padding(28.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Add widget", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Choose a widget provider installed on this Portal.",
                color = Color(0xFF9A9A9A),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
          }
          Surface(
              color = Color(0x33FFFFFF),
              shape = CircleShape,
              modifier = Modifier.size(44.dp).tvFocusable(CircleShape) { onDismiss() },
          ) {
            Box(contentAlignment = Alignment.Center) { Text("✕", color = Color.White, fontSize = 20.sp) }
          }
        }
        if (status != null) {
          Text(
              status,
              color = Color(0xFF8AB4F8),
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 14.dp),
          )
        }
        Spacer(Modifier.size(20.dp))
        if (providers.isEmpty()) {
          Box(
              modifier = Modifier.fillMaxWidth().height(220.dp),
              contentAlignment = Alignment.Center,
          ) {
            Text(
                "No widget providers are installed.",
                color = Color(0xFFB8B8B8),
                fontSize = 16.sp,
            )
          }
        } else {
          LazyVerticalGrid(
              columns = GridCells.Fixed(3),
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
              modifier = Modifier.focusRequester(gridFocus).focusGroup(),
          ) {
            items(providers, key = { it.info?.provider?.flattenToString() ?: "custom:${it.customKind}" }) { provider ->
              WidgetProviderTile(provider = provider, onClick = { onPick(provider) })
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WidgetProviderTile(provider: WidgetProviderEntry, onClick: () -> Unit) {
  Surface(
      color = Color(0xFF29292C),
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.tvFocusable(RoundedCornerShape(18.dp)) { onClick() },
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
          color = Color(0xFF38383C),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.size(58.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (provider.icon != null) {
            Image(
                bitmap = provider.icon,
                contentDescription = null,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)),
            )
          } else if (provider.customKind != null) {
            CustomWidgetGlyph(provider.customKind)
          } else {
            WidgetGlyph()
          }
        }
      }
      Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
        Text(
            provider.label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            provider.packageLabel,
            color = Color(0xFF9A9A9A),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            "${provider.spanX} x ${provider.spanY}",
            color = Color(0xFF7C7C7C),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
      }
    }
  }
}

@Composable
private fun CustomWidgetGlyph(kind: String) {
  val emoji =
      when (kind) {
        HomeWidgetStore.KIND_WEATHER -> "☁"
        HomeWidgetStore.KIND_WORLD_CLOCK -> "◷"
        HomeWidgetStore.KIND_TIMERS -> "⏱"
        else -> "+"
      }
  Text(emoji, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun WidgetGlyph() {
  val path = remember { PathParser().parsePathString(ICON_WIDGETS).toPath() }
  Canvas(Modifier.size(28.dp)) {
    val s = size.minDimension / 24f
    scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
  }
}

/**
 * iOS-style "jiggle": a gentle continuous wobble applied to editable tiles while Manage mode is on.
 * Each tile is given a [seed] so they fall slightly out of phase (rather than wobbling in lockstep).
 * The animation only exists while [enabled]; it leaves composition when Manage mode ends so the
 * long-lived launcher isn't animating in the background.
 */
@Composable
private fun Modifier.jiggle(enabled: Boolean, seed: Int): Modifier {
  if (!enabled) return this
  val transition = rememberInfiniteTransition(label = "jiggle")
  val angle by
      transition.animateFloat(
          initialValue = -1.0f,
          targetValue = 1.0f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                  repeatMode = RepeatMode.Reverse,
                  initialStartOffset = StartOffset(0),
              ),
          label = "jiggle-angle",
      )
  val bob by
      transition.animateFloat(
          initialValue = -0.8f,
          targetValue = 0.8f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                  repeatMode = RepeatMode.Reverse,
                  initialStartOffset = StartOffset(0),
              ),
          label = "jiggle-bob",
      )
  return this.graphicsLayer {
    rotationZ = angle
    translationY = bob
    compositingStrategy = CompositingStrategy.Offscreen
  }
}

/**
 * Round red delete affordance with a crisply-drawn, perfectly-centered ✕ (the unicode glyph
 * isn't vertically centered inside its line box, so it's drawn as two crossing strokes instead).
 */
@Composable
private fun RemoveBadge(size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = Color(0xFFE53935),
      shape = CircleShape,
      modifier = modifier.size(size).tvFocusable(CircleShape) { onClick() },
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val cx = this.size.width / 2f
      val cy = this.size.height / 2f
      val arm = this.size.minDimension * 0.21f
      val stroke = this.size.minDimension * 0.11f
      drawLine(
          Color.White,
          Offset(cx - arm, cy - arm),
          Offset(cx + arm, cy + arm),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
      )
      drawLine(
          Color.White,
          Offset(cx - arm, cy + arm),
          Offset(cx + arm, cy - arm),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
      )
    }
  }
}

/**
 * Gray minus badge for iOS-style edit mode on app icons.
 */
@Composable
private fun MinusBadge(modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = Color(0xE6C7C7CC),
      shape = CircleShape,
      modifier = modifier
          .size(32.dp)
          .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null
          ) { onClick() },
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val cx = this.size.width / 2f
      val cy = this.size.height / 2f
      val armWidth = this.size.minDimension * 0.30f
      val stroke = this.size.minDimension * 0.12f
      drawLine(
          Color.White,
          Offset(cx - armWidth, cy),
          Offset(cx + armWidth, cy),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
      )
    }
  }
}

/**
 * Resize control for widgets: PRESS-AND-DRAG the bottom-right handle to resize (iOS/Android style).
 * Reports the accumulated drag delta continuously via [onResizeDrag]; the caller snaps to grid
 * sizes. [onResizeStart]/[onResizeEnd] bracket the gesture so the caller can capture the start span
 * and reset its accumulator. The drag consumes its pointer events so it never triggers the widget
 * body's reorder handle.
 */
@Composable
private fun ResizeBadge(
    modifier: Modifier = Modifier,
    onResizeStart: () -> Unit,
    onResizeDrag: (dragDelta: Offset) -> Unit,
    onResizeEnd: () -> Unit,
) {
  // Keep latest callbacks so the long-lived pointerInput lambda always calls the current versions.
  val startState = rememberUpdatedState(onResizeStart)
  val dragState = rememberUpdatedState(onResizeDrag)
  val endState = rememberUpdatedState(onResizeEnd)
  Surface(
      color = Color(0xE6C7C7CC),
      shape = CircleShape,
      modifier = modifier
          .size(32.dp)
          .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { startState.value() },
                onDragEnd = { endState.value() },
                onDragCancel = { endState.value() },
            ) { change, dragAmount ->
              // Consume so the reorderable grid's drag handle does not also react to this pointer.
              change.consume()
              dragState.value(dragAmount)
            }
          },
  ) {
    Canvas(Modifier.fillMaxSize()) {
      // Double-headed diagonal resize arrow (↖ ↘): a diagonal shaft with an arrowhead at each end.
      val d = this.size.minDimension
      val stroke = d * 0.09f
      val m = d * 0.30f                 // arrow tip inset from edges
      val tl = Offset(m, m)             // top-left tip
      val br = Offset(d - m, d - m)     // bottom-right tip
      val head = d * 0.14f              // arrowhead leg length
      // shaft
      drawLine(Color.White, tl, br, strokeWidth = stroke, cap = StrokeCap.Round)
      // top-left arrowhead (two legs)
      drawLine(Color.White, tl, Offset(tl.x + head, tl.y), strokeWidth = stroke, cap = StrokeCap.Round)
      drawLine(Color.White, tl, Offset(tl.x, tl.y + head), strokeWidth = stroke, cap = StrokeCap.Round)
      // bottom-right arrowhead (two legs)
      drawLine(Color.White, br, Offset(br.x - head, br.y), strokeWidth = stroke, cap = StrokeCap.Round)
      drawLine(Color.White, br, Offset(br.x, br.y - head), strokeWidth = stroke, cap = StrokeCap.Round)
    }
  }
}

/**
 * Wraps a tile whose own content does NOT swallow touches (built-ins, folders, apps) with a
 * dragged-dim. The drag itself is handled by the stable container detector that wraps the pager, so
 * the gesture survives a page flip (letting a tile be dragged onto another page). Hosted widgets
 * still use their own in-page scrim handle (see [WidgetCell]), since their AndroidView swallows it.
 */
@Composable
private fun HomeTileFrame(
    modifier: Modifier,
    dragged: Boolean,
    content: @Composable () -> Unit,
) {
  Box(modifier = modifier.fillMaxWidth().alpha(if (dragged) 0f else 1f)) { content() }
}

/** Widget tile + the ✕ remove badge in edit mode. Drag is handled by the stable container detector
 * (which grabs widgets via the pointer Initial pass), so widgets drag and move across pages like
 * apps. Widgets are not user-resizable — they keep the size their provider declares. */
@Composable
private fun WidgetCell(
    widget: HomeWidgetStore.HomeWidget,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    editMode: Boolean,
    tileDp: Dp,
    dimmed: Boolean,
    modifier: Modifier,
    onRemove: () -> Unit,
) {
  Box(modifier = modifier) {
    WidgetTile(
        widget = widget,
        host = host,
        manager = manager,
        editMode = editMode,
        tileDp = tileDp,
        dimmed = dimmed,
    )
    if (editMode) {
      RemoveBadge(
          size = 30.dp,
          modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp),
          onClick = onRemove,
      )
    }
  }
}

@Composable
private fun AppTile(
    app: AppEntry,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onDelete: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      // In Manage mode the body tap is inert (drag to reorder, ✕ to remove); the
      // icon launches normally otherwise. A long-press enters Manage mode (iOS-style).
      modifier =
          modifier.fillMaxWidth().padding(4.dp).tvFocusable(
              RoundedCornerShape(22.dp),
              enabled = !editMode,
              onLongClick = onLongPress,
          ) {
            onClick()
          },
  ) {
    // The icon (and its delete badge) jiggle as a unit in Manage mode; the label stays still.
    Box(modifier = Modifier.jiggle(editMode && !dimmed, app.component.packageName.hashCode())) {
      Image(
          bitmap = app.icon,
          contentDescription = app.label,
          modifier =
              Modifier.size(LocalTileDp.current)
                  .alpha(if (dimmed) 0.3f else 1f),
      )
      if (editMode) {
        // Sits at the icon's outer top-right corner (nudged off the icon, iOS-style).
        RemoveBadge(
            size = 26.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-9).dp),
            onClick = onDelete,
        )
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        app.label,
        color = ContentSecondary,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
  }
}

// --- data ---------------------------------------------------------------------

private fun loadApps(context: Context): List<AppEntry> {
  val pm = context.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  return pm.queryIntentActivities(intent, 0)
      .filter {
        val pkg = it.activityInfo.packageName
        pkg != context.packageName && !Curation.isHidden(pkg, "")
      }
      .mapNotNull { ri ->
        runCatching {
              val ai = ri.activityInfo
              val rawLabel = ri.loadLabel(pm).toString()
              // Check for icon override first, then use app's own icon
              val drawable = Curation.iconOverride(ai.packageName)
                  ?.let { resId -> androidx.core.content.ContextCompat.getDrawable(context, resId) }
                  ?: ri.loadIcon(pm)
              // Render with IconRenderer (adaptive composite + squircle mask)
              val bmp: Bitmap = IconRenderer.renderAppIcon(context, drawable, 144)
              // Uninstallable: not a system app OR is an updated system app
              val appInfo = ai.applicationInfo
              val isUninstallable = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
              AppEntry(
                  label = Curation.displayLabel(ai.packageName, rawLabel),
                  component = ComponentName(ai.packageName, ai.name),
                  icon = bmp.asImageBitmap(),
                  folder = Curation.folderFor(ai.packageName),
                  uninstallable = isUninstallable,
              ) to rawLabel
            }
            .getOrNull()
      }
      // Label-based hide (catches gated apps that share a package).
      .filterNot { (_, raw) -> raw in Curation.hiddenLabels }
      .map { it.first }
      // One tile per package — some apps (e.g. Portal Settings) expose a second
      // debug/launcher activity that would otherwise show as a duplicate.
      .distinctBy { it.component.packageName }
      .sortedBy { it.label.lowercase(Locale.getDefault()) }
      .let { apps ->
        // Inject our own iOS-style settings as a grid tile. Our app is excluded from the
        // normal query (pkg == this launcher), and ImmortalSettingsActivity is exported=false
        // with no LAUNCHER filter, so it's launched via the special-case in onLaunch below.
        val settingsIcon = Curation.iconOverride("com.android.settings")
            ?.let { resId -> androidx.core.content.ContextCompat.getDrawable(context, resId) }
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        // Inject the Immortal App Store too (StoreActivity — also our own, excluded from the query).
        val storeIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.icon_apps)
            ?.let { IconRenderer.renderAppIcon(context, it, 144).asImageBitmap() }
        var out = apps
        if (settingsIcon != null) {
          out = out + AppEntry(
              label = "Settings",
              component = ComponentName(context.packageName, ImmortalSettingsActivity::class.java.name),
              icon = settingsIcon,
              uninstallable = false,
          )
        }
        if (storeIcon != null) {
          out = out + AppEntry(
              label = "App Store",
              component = ComponentName(context.packageName, StoreActivity::class.java.name),
              icon = storeIcon,
              uninstallable = false,
          )
        }
        out
      }
}

private fun loadWidgetProviders(context: Context, tileDp: Dp): List<WidgetProviderEntry> {
  val pm = context.packageManager
  val density = context.resources.displayMetrics.densityDpi
  val custom =
      listOf(
          WidgetProviderEntry(
              label = "Weather",
              packageLabel = "Immortal",
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_WEATHER,
          ),
          WidgetProviderEntry(
              label = "World Clock",
              packageLabel = "Immortal",
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_WORLD_CLOCK,
          ),
          WidgetProviderEntry(
              label = "Timers",
              packageLabel = "Immortal",
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_TIMERS,
          ),
      )
  val android =
      AppWidgetManager.getInstance(context)
      .installedProviders
      .mapNotNull { info ->
        runCatching {
              val packageLabel =
                  pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0))
                      .toString()
              val icon =
                  runCatching { info.loadIcon(context, density) }
                      .getOrNull()
                      ?: runCatching { pm.getApplicationIcon(info.provider.packageName) }.getOrNull()
              val iconBitmap = icon?.toBitmap(96, 96)?.asImageBitmap()
              WidgetProviderEntry(
                  label = info.loadLabel(pm).toString().ifBlank { packageLabel },
                  packageLabel = packageLabel,
                  icon = iconBitmap,
                  info = info,
                  spanX =
                      estimateWidgetSpan(appWidgetMinDp(context, info.minWidth), tileDp)
                          .coerceAtLeast(HomeWidgetStore.DEFAULT_SPAN_X),
                  spanY =
                      estimateWidgetSpan(appWidgetMinDp(context, info.minHeight), tileDp)
                          .coerceAtLeast(HomeWidgetStore.DEFAULT_SPAN_Y),
              )
            }
            .getOrNull()
      }
      .sortedWith(
          compareBy(
              { it.packageLabel.lowercase(Locale.getDefault()) },
              { it.label.lowercase(Locale.getDefault()) }))
  return custom + android
}

private fun List<HomeWidgetStore.HomeWidget>.filterLiveWidgets(
    manager: AppWidgetManager,
    host: AppWidgetHost,
): List<HomeWidgetStore.HomeWidget> {
  val live = filter { !it.isAppWidget || manager.getAppWidgetInfo(it.appWidgetId) != null }
  if (live.size != size) {
    val liveIds = live.map { it.appWidgetId }.toSet()
    filter { it.isAppWidget && it.appWidgetId !in liveIds }.forEach {
      runCatching { host.deleteAppWidgetId(it.appWidgetId) }
    }
  }
  return live
}

private fun updateWidgetSizeOptions(
    manager: AppWidgetManager,
    widget: HomeWidgetStore.HomeWidget,
    tileDp: Dp,
) {
  val width =
      (tileDp.value * widget.spanX + 16f * (widget.spanX - 1).coerceAtLeast(0)).roundToInt()
  val height =
      (tileDp.value * widget.spanY + 20f * (widget.spanY - 1).coerceAtLeast(0)).roundToInt()
  runCatching {
    manager.updateAppWidgetOptions(
        widget.appWidgetId,
        Bundle().apply {
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        })
  }
}

private fun appWidgetMinDp(context: Context, raw: Int): Int {
  if (raw <= 0) return 0
  // Android 9/10 on Portal reports AppWidgetProviderInfo sizes as raw complex
  // dimension values (for example, 10241 == 40dp). Newer framework builds may
  // already hand back dp integers, so only decode values that are clearly not
  // plain dp sizes.
  if (raw < 1000) return raw
  val metrics = context.resources.displayMetrics
  return (TypedValue.complexToDimensionPixelSize(raw, metrics) / metrics.density).roundToInt()
}
