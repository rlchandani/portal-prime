/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import com.immortal.launcher.ui.theme.ApprovedDarkPalette

internal val LocalApprovedHomePopup = compositionLocalOf { false }
internal val ApprovedHomeTabPalette = ApprovedDarkPalette

/**
 * Home's retained pages have native drawing boundaries. Animating a Compose placement or layer
 * calls its owner's onLayoutChange on every frame, invalidating the page's semantics geometry.
 * Here only native View translations change per frame; page layout and semantics stay local.
 * The child compositions inherit the current theme/content and share the parent's recomposer.
 */
@Composable
internal fun ApprovedHomeTabs(
    selected: ApprovedLauncherTab,
    modifier: Modifier = Modifier,
    content: @Composable (ApprovedLauncherTab) -> Unit,
) {
  if (!BuildConfig.PORTAL_NATIVE_HOME_TABS) {
    ApprovedLauncherTabs(selected, modifier, content)
    return
  }
  val hostOwner = LocalLifecycleOwner.current
  val composition = rememberCompositionContext()
  val latestContent = rememberUpdatedState(content)
  val focus = LocalFocusManager.current
  val view = LocalView.current
  val clearFocus = rememberUpdatedState {
    focus.clearFocus(force = true)
    view.findFocus()?.clearFocus()
  }
  // Keep the saveable key outside the identity-keyed owner group: the Activity changes on restore.
  val pageIds = rememberSaveable { IntArray(ApprovedLauncherTab.entries.size) { View.generateViewId() } }
  key(hostOwner) {
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { context ->
          ApprovedHomeTabViews(context, selected, hostOwner, onSelection = { clearFocus.value() }) { tab ->
            val page = ApprovedHomeTabView(ComposeView(context).apply {
              id = pageIds[tab.ordinal]
              setParentCompositionContext(composition)
              // Temporary detach must retain drafts; final release still disposes explicitly.
              setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(hostOwner))
            }, hostOwner)
            page.view.setContent {
              val active = page.active
              val pageFocus = LocalFocusManager.current
              LaunchedEffect(active) {
                if (!active) pageFocus.clearFocus(force = true)
              }
              Box(Modifier.fillMaxSize().focusProperties { canFocus = active }
                  .pointerInput(page.input) {
                    try {
                      awaitPointerEventScope {
                        while (true) {
                          val event = awaitPointerEvent(PointerEventPass.Initial)
                          if (event.changes.any { it.pressed }) page.input.beginTouch()
                          if (!page.input.acceptsAction()) event.changes.forEach { it.consume() }
                          // Reject a release from a hold that crossed a hide/show boundary.
                          if (event.changes.none { it.pressed }) page.input.endTouch()
                        }
                      }
                    } finally { page.input.endTouch() }
                  }.then(if (active) Modifier else Modifier.clearAndSetSemantics { })) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides hostOwner,
                    LocalApprovedLauncherPageActive provides active,
                    LocalApprovedLauncherHostOwner provides hostOwner,
                ) {
                  ApprovedLauncherPageLifecycle(active, page.input) { latestContent.value(tab) }
                }
              }
            }
            page
          }
        },
        update = { it.select(selected) },
        onRelease = { it.release() },
    )
  }
}

private class ApprovedHomeTabView(val view: ComposeView, private val host: LifecycleOwner) {
  // Translate a plain native drawing boundary, keeping Compose's own host coordinates stable.
  val layer = FrameLayout(view.context).apply {
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    clipChildren = true
    addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
  }
  private val originalLayerType = layer.layerType
  fun cacheDrawing(value: Boolean) {
    val target = if (value && layer.isHardwareAccelerated) View.LAYER_TYPE_HARDWARE else originalLayerType
    if (layer.layerType != target) layer.setLayerType(target,null)
  }
  val input = LauncherPageInputGate()
  var active by mutableStateOf(false)
    private set

  fun activate(value: Boolean) {
    // Close the input gate at admission, before the child composition applies its lifecycle.
    input.update(value && host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    active = value
    view.descendantFocusability = if (value) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    view.importantForAccessibility = if (value) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  }
}

/** At most three attached pages; hidden pages retain state while their existing lifecycle pauses. */
private class ApprovedHomeTabViews(
    context: Context,
    initial: ApprovedLauncherTab,
    private val host: LifecycleOwner,
    private val onSelection: () -> Unit,
    private val createPage: (ApprovedLauncherTab) -> ApprovedHomeTabView,
) : FrameLayout(context) {
  private val transition = ApprovedHomeTabTransition(initial)
  private val pages = linkedMapOf<ApprovedLauncherTab, ApprovedHomeTabView>()
  private var animator: ValueAnimator? = null
  private var pending: ApprovedHomeTabTransition.Plan? = null
  private var released = false
  private var attached = false
  private val lifecycleObserver = LifecycleEventObserver { _, event ->
    if (!released) {
      if (event == Lifecycle.Event.ON_DESTROY) release()
      else synchronizeAnimation()
    }
  }

  init {
    clipChildren = true
    clipToPadding = true
    if (host.lifecycle.currentState == Lifecycle.State.DESTROYED) release()
    else {
      ensurePage(initial)
      updatePages()
      host.lifecycle.addObserver(lifecycleObserver)
    }
  }

  fun select(tab: ApprovedLauncherTab) {
    if (released) return
    val plan = transition.select(tab) ?: return
    // The new plan owns positions before cancel can deliver callbacks from the old animator.
    cancelAnimation()
    onSelection()
    val incoming = ensurePage(tab)
    updatePages()
    applyPositions()
    pending = plan
    // First-use content gets its initial measure/layout before the slide starts.
    if (width > 0 && incoming.view.isLaidOut && !isLayoutRequested) synchronizeAnimation()
    else requestLayout()
  }

  private fun ensurePage(tab: ApprovedLauncherTab): ApprovedHomeTabView =
      pages.getOrPut(tab) {
        createPage(tab).also {
          it.activate(tab == transition.selected)
          // Match the previous host's Home/Apps/Tools draw order even after out-of-order visits.
          val index = pages.keys.count { existing -> existing.ordinal < tab.ordinal }
          addView(it.layer, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
      }

  private fun updatePages() {
    pages.forEach { (tab, page) ->
      page.activate(tab == transition.selected)
      // INVISIBLE keeps measurement and composition; no offscreen page display list is drawn.
      page.layer.visibility = if (tab in transition.visible) View.VISIBLE else View.INVISIBLE
    }
  }

  private fun applyPositions() {
    pages.forEach { (tab, page) -> page.layer.translationX = transition.offset(tab) * width }
  }

  private fun canRunPageMotion(): Boolean = !released && attached &&
      host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

  private fun synchronizeAnimation(afterLayout: Boolean = false) {
    if (released) return
    val current = animator
    if (!canRunPageMotion()) {
      current?.pause()
      cacheVisiblePages(false)
    } else if (current != null) {
      cacheVisiblePages(true)
      if (current.isPaused) current.resume()
    } else {
      cacheVisiblePages(true)
      startPending(afterLayout)
    }
  }

  private fun startPending(afterLayout: Boolean = false) {
    if (!canRunPageMotion() || width <= 0) return
    val incoming = pages[transition.selected]?.view ?: return
    // onLayout has laid out children before the host clears its own layout-request flag.
    if (!incoming.isLaidOut || (!afterLayout && isLayoutRequested)) return
    val plan = pending ?: return
    pending = null
    cacheVisiblePages(true)
    val next = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 160L
      interpolator = TimeInterpolator { FastOutSlowInEasing.transform(it) }
      addUpdateListener {
        if (animator === it && canRunPageMotion() && transition.advance(plan, it.animatedValue as Float))
          applyPositions()
      }
      addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
          if (!released && animator === animation && transition.finish(plan)) {
            animator = null
            updatePages()
            cacheVisiblePages(true)
            applyPositions()
          }
        }
      })
    }
    // A zero system animation scale can finish synchronously inside start().
    animator = next
    next.start()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    attached = true
    applyPositions()
    synchronizeAnimation()
  }

  override fun onDetachedFromWindow() {
    // isAttachedToWindow may still be true during this callback; close our gate first.
    attached = false
    synchronizeAnimation()
    super.onDetachedFromWindow()
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    applyPositions()
    synchronizeAnimation(afterLayout = true)
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    // The animator owns normalized positions, so resizing preserves its current progress.
    applyPositions()
  }

  private fun cacheVisiblePages(value: Boolean) {
    // Keep built textures across tab visits (at most three pages), rather than allocating
    // them for every 160ms movement. Stop/detach/release clears all cached pages.
    pages.forEach { (tab,page) ->
      if (!value || tab in transition.visible) page.cacheDrawing(value)
    }
  }

  private fun cancelAnimation() {
    val old = animator
    animator = null
    old?.removeAllUpdateListeners()
    old?.removeAllListeners()
    old?.cancel()
  }

  fun release() {
    if (released) return
    released = true
    attached = false
    host.lifecycle.removeObserver(lifecycleObserver)
    pending = null
    transition.settle()
    cancelAnimation()
    pages.values.forEach {
      it.cacheDrawing(false)
      it.input.update(false)
      // Uninstall the host-lifecycle observer as well as disposing the composition. Otherwise
      // an early release retains the obsolete page View until the Activity is destroyed.
      it.view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
      it.view.disposeComposition()
    }
    removeAllViews()
    pages.clear()
  }
}

internal object ApprovedHomePalette {
  val Background: Color @Composable get() =
      LocalApprovedPalette.current.background
  val Card: Color @Composable get() =
      LocalApprovedPalette.current.surface
  val Chrome: Color @Composable get() =
      LocalApprovedPalette.current.surface2
  val Text: Color @Composable get() =
      LocalApprovedPalette.current.text
  val Secondary: Color @Composable get() =
      LocalApprovedPalette.current.secondary
  val Accent: Color @Composable get() =
      LocalApprovedPalette.current.accent
  val Border: Color @Composable get() =
      LocalApprovedPalette.current.divider
  val Selected: Color @Composable get() =
      LocalApprovedPalette.current.selected
}

@Composable
internal fun ApprovedHomeSurface(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
  val shape = RoundedCornerShape(radius)
  Box(modifier.clip(shape).background(ApprovedHomePalette.Card)
      .border(1.dp, ApprovedHomePalette.Border, shape), content = content)
}

@Composable
internal fun ApprovedHomeTextButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
  Box(modifier.heightIn(min = 52.dp)
      .tvFocusable(RoundedCornerShape(16.dp), ringColor = ApprovedHomePalette.Accent) { if (enabled) onClick() }
      .padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
    Text(label, color = ApprovedHomePalette.Accent.copy(alpha = if (enabled) 1f else .4f),
        fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
  }
}

/** Home-only empty/loading copy; shared legacy widgets retain their own presentation. */
@Composable
internal fun ApprovedHomeWidgetMessage(text: String) {
  Text(text, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
}

@Composable
internal fun ApprovedHomeIcon(
    name: String,
    modifier: Modifier = Modifier.size(30.dp),
    tint: Color = ApprovedHomePalette.Secondary,
) {
  ApprovedIcon(name, modifier, tint)
}

@Composable
internal fun ApprovedHomeDock(selected: String, onSelect: (String) -> Unit) {
  ApprovedHomeSurface(radius = 26.dp, modifier = Modifier.height(92.dp)) {
    Row(Modifier.fillMaxHeight().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
      listOf("home" to "Home", "apps" to "Apps", "tools" to "Tools").forEach { (id, label) ->
        val active = selected == id
        Row(Modifier.width(176.dp).fillMaxHeight().clip(RoundedCornerShape(18.dp))
            .background(if (active) ApprovedHomePalette.Selected else Color.Transparent)
            .tvFocusable(RoundedCornerShape(18.dp), ringColor = ApprovedHomePalette.Accent) { onSelect(id) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
          ApprovedHomeIcon(id, tint = if (active) ApprovedHomePalette.Accent else ApprovedHomePalette.Secondary)
          Text(label, color = if (active) ApprovedHomePalette.Accent else ApprovedHomePalette.Secondary,
              fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
        }
      }
    }
  }
}

@Composable
internal fun ApprovedHomeWidgetHeading(title: String, icon: String, onOpen: () -> Unit) {
  Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    ApprovedHomeIcon(icon, Modifier.size(27.dp))
    Text(title, color = ApprovedHomePalette.Secondary, fontSize = 24.sp, lineHeight = 32.sp,
        fontWeight = FontWeight.Medium, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    // The negative vertical margin in the approved CSS gives this action a 68dp touch target.
    Box(Modifier.width(68.dp).height(32.dp)
        .tvFocusable(RoundedCornerShape(9.dp), ringColor = ApprovedHomePalette.Accent, onClick = onOpen),
        contentAlignment = Alignment.Center) {
      Text("Open", color = ApprovedHomePalette.Accent, fontSize = 18.sp, lineHeight = 24.sp,
          fontWeight = FontWeight.Medium)
    }
  }
}
