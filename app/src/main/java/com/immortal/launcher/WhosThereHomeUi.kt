/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.delay

internal val WtGreen = Color(0xFF2BB673)
private val WtAmber = Color(0xFFB86A12)
private val WtRed = Color(0xFFE5484D)
private const val HI_CHIP_MS = 4_000L

@Composable
internal fun rememberWhosThereUi(): State<WhosThereUi> = WhosThere.state.collectAsState()

/** Tells the runtime whether Home is really in front: page active and the Activity resumed. */
@Composable
internal fun WhosThereHomeVisibility(active: Boolean) {
  val owner = LocalLifecycleOwner.current
  DisposableEffect(owner, active) {
    fun update() = WhosThere.setHomeVisible(active && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    val observer = LifecycleEventObserver { _, _ -> update() }
    owner.lifecycle.addObserver(observer)
    update()
    onDispose {
      owner.lifecycle.removeObserver(observer)
      WhosThere.setHomeVisible(false)
    }
  }
}

internal fun Long.argb(): Color = Color(this.toInt())

@Composable
internal fun WhosThereAvatar(person: WhosTherePerson?, size: Dp, modifier: Modifier = Modifier) {
  val c = person?.color ?: WhosThereColor("Guest", 0xFFC3CAD5, 0xFF7D8796, 0xFF8A94A6)
  Box(modifier.size(size).clip(CircleShape)
      .background(Brush.linearGradient(listOf(c.from.argb(), c.to.argb()))), contentAlignment = Alignment.Center) {
    Text(person?.initial ?: "?", color = Color.White, fontSize = (size.value * 0.44f).sp, fontWeight = FontWeight.SemiBold)
  }
}

/** The pill every indicator state shares: 64 dp, 1.5 dp outline, card fill. */
@Composable
private fun IndicatorPill(modifier: Modifier, description: String, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
  val shape = RoundedCornerShape(32.dp)
  val base = modifier.height(64.dp).clip(shape).background(ApprovedHomePalette.Card).approvedOutline(shape)
  Row(base.then(if (onClick != null) Modifier.tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick) else Modifier)
      .semantics { contentDescription = description }.padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun PillText(text: String, color: Color = ApprovedHomePalette.Text) =
    Text(text, color = color, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp).padding(end = 12.dp))

/**
 * The top-right "who's there" spot. While the camera is open it shows the live preview, a
 * pulsing green dot and "Looking…": always, it can't be hidden.
 */
@Composable
internal fun WhosThereIndicatorChip(use24: Boolean, onWho: () -> Unit, modifier: Modifier = Modifier, fixedSlot: Boolean = false) {
  val ui by rememberWhosThereUi()
  if (ui.indicator == WhosThereIndicator.Hidden) return
  // In the greeting row the chip sits in a fixed-width slot, so its state changes never re-measure
  // the greeting and clock beside it (that was the one long frame when a glance started).
  if (fixedSlot) Box(modifier.width(INDICATOR_SLOT), contentAlignment = Alignment.CenterEnd) { IndicatorFor(ui, use24, onWho, Modifier) }
  else IndicatorFor(ui, use24, onWho, modifier)
}

private val INDICATOR_SLOT = 280.dp

@Composable
private fun IndicatorFor(ui: WhosThereUi, use24: Boolean, onWho: () -> Unit, modifier: Modifier) {
  when (val ind = ui.indicator) {
    WhosThereIndicator.Hidden -> Unit
    // One call site for Ready and Looking keeps the same preview view alive between them.
    WhosThereIndicator.Ready, WhosThereIndicator.Looking -> PreviewPill(ind == WhosThereIndicator.Looking, modifier)
    is WhosThereIndicator.Recognised -> {
      val p = ui.person(ind.personId)
      IndicatorPill(modifier, "Showing ${p?.name}’s Home. Tap if this isn’t you", onWho) {
        WhosThereAvatar(p, 48.dp)
        PillText(if (ind.unsure) "${p?.name}?" else p?.name.orEmpty())
        WhosThereGlyph(WtGlyph.CHEVRON_DOWN, ApprovedHomePalette.Secondary, 24.dp, Modifier.padding(end = 10.dp))
      }
    }
    is WhosThereIndicator.Family -> IndicatorPill(modifier, "Family view. Tap to choose", onWho) {
      Box(Modifier.size(width = (48 + 28 * (ind.personIds.size.coerceAtMost(3) - 1)).dp, height = 48.dp)) {
        ind.personIds.take(3).forEachIndexed { i, id -> WhosThereAvatar(ui.person(id), 48.dp, Modifier.offset(x = (28 * i).dp)) }
      }
      PillText("Family")
      WhosThereGlyph(WtGlyph.CHEVRON_DOWN, ApprovedHomePalette.Secondary, 24.dp, Modifier.padding(end = 10.dp))
    }
    is WhosThereIndicator.Paused -> IndicatorPill(modifier, "Recognition paused. Tap to resume", { WhosThere.resume() }) {
      WhosThereGlyph(WtGlyph.EYE_OFF, WtAmber, 28.dp, Modifier.padding(start = 10.dp))
      PillText("Paused · until ${WhosThereIndicatorPolicy.clock(minuteOf(ind.untilMs), use24)}", WtAmber)
    }
    WhosThereIndicator.InCall -> IndicatorPill(modifier, "Camera in a call", null) {
      WhosThereGlyph(WtGlyph.CAMERA, WtRed, 28.dp, Modifier.padding(start = 10.dp))
      PillText("Camera in a call", WtRed)
    }
    is WhosThereIndicator.QuietHours -> IndicatorPill(modifier, "Recognition off in quiet hours", null) {
      WhosThereGlyph(WtGlyph.MOON, ApprovedHomePalette.Secondary, 26.dp, Modifier.padding(start = 10.dp))
      PillText("Off until ${WhosThereIndicatorPolicy.clock(ind.untilMinute, use24)}", ApprovedHomePalette.Secondary)
    }
    WhosThereIndicator.TooDark -> IndicatorPill(modifier, "Too dark to see who’s here. Tap to choose", onWho) {
      WhosThereGlyph(WtGlyph.BULB, ApprovedHomePalette.Secondary, 28.dp, Modifier.padding(start = 10.dp))
      PillText("Too dark", ApprovedHomePalette.Secondary)
    }
    WhosThereIndicator.CameraCovered -> IndicatorPill(modifier, "Camera covered, recognition off", null) {
      WhosThereGlyph(WtGlyph.CAMERA_OFF, ApprovedHomePalette.Secondary, 28.dp, Modifier.padding(start = 10.dp))
      PillText("Camera covered", ApprovedHomePalette.Secondary)
    }
    is WhosThereIndicator.Unavailable -> IndicatorPill(modifier, ind.message, null) {
      WhosThereGlyph(WtGlyph.EYE_OFF, ApprovedHomePalette.Secondary, 28.dp, Modifier.padding(start = 10.dp))
      PillText("Recognition off", ApprovedHomePalette.Secondary)
    }
  }
}

private fun minuteOf(ms: Long): Int =
    java.util.Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }

/**
 * Ready: an eye on a disc. Looking: the disc lifts off the live preview once the first new frame
 * lands (never an old frame), with the green dot and "Looking…". The preview view stays mounted
 * in both states so starting a glance creates nothing mid-frame.
 */
@Composable
private fun PreviewPill(looking: Boolean, modifier: Modifier) {
  var frameSeen by remember { mutableStateOf(false) }
  LaunchedEffect(looking) { frameSeen = false }
  val description = if (looking) "Camera on: looking who’s here" else "Recognition on. Tap to look again"
  IndicatorPill(modifier, description, if (looking) null else ({ WhosThere.requestGlance(GlanceTrigger.MANUAL) })) {
    Box(Modifier.size(48.dp).clip(CircleShape)) {
      WhosThereGlancePreview(Modifier.fillMaxSize(), onFrame = { if (looking) frameSeen = true })
      if (!looking || !frameSeen) Box(Modifier.fillMaxSize().background(if (looking) Color(0xFF1A1F2B) else ApprovedHomePalette.Card),
          contentAlignment = Alignment.Center) {
        if (!looking) WhosThereGlyph(WtGlyph.EYE, ApprovedHomePalette.Secondary, 30.dp)
      }
    }
    if (looking) {
      PulsingDot()
      PillText("Looking…")
    }
  }
}

@Composable
private fun PulsingDot() {
  val t = rememberInfiniteTransition(label = "wtDot")
  val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "wtDotAlpha")
  Box(Modifier.size(14.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(WtGreen))
}

/** The glance's visible live preview: a small TextureView fed by camera 0 for ~1.5 s. */
@Composable
private fun WhosThereGlancePreview(modifier: Modifier, onFrame: () -> Unit) {
  val frame by androidx.compose.runtime.rememberUpdatedState(onFrame)
  AndroidView(modifier = modifier, factory = { ctx ->
    TextureView(ctx).apply {
      surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        private var surface: Surface? = null
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
          st.setDefaultBufferSize(WhosThereCamera.WIDTH, WhosThereCamera.HEIGHT)
          setTransform(fill(w, h))
          // Hand over after this frame: never start the camera from inside a draw pass.
          val s = Surface(st).also { surface = it }
          post { if (surface === s) WhosThere.onPreviewAvailable(s) }
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = setTransform(fill(w, h))
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
          surface?.let(WhosThere::onPreviewGone)
          surface?.release(); surface = null
          return true
        }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = frame()
      }
    }
  })
}

/** Centre-crop the 4:3 stream into the view (a circle crops it further). */
private fun fill(w: Int, h: Int): Matrix {
  val m = Matrix()
  if (w <= 0 || h <= 0) return m
  val src = WhosThereCamera.WIDTH.toFloat() / WhosThereCamera.HEIGHT
  val dst = w.toFloat() / h
  if (src > dst) m.setScale(src / dst, 1f, w / 2f, h / 2f) else m.setScale(1f, dst / src, w / 2f, h / 2f)
  return m
}

/** The warm room tint: the person's colour washed in from the top (two colours for Family). */
@Composable
internal fun WhosThereTint(modifier: Modifier = Modifier) {
  val ui by rememberWhosThereUi()
  val colors = WhosThereViewPolicy.people(ui.view).mapNotNull { ui.person(it)?.color?.tint?.argb() }
  var last by remember { mutableStateOf(listOf(Color.Transparent)) }
  if (colors.isNotEmpty()) last = colors
  val alpha by animateFloatAsState(if (colors.isEmpty()) 0f else 1f, tween(450), label = "wtTint")
  if (alpha <= 0f) return
  val dark = LocalApprovedPalette.current.dark
  val strength = if (dark) 0.22f else 0.16f
  Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.drawBehind {
    // Family: the two colours side by side, softer; one person: their colour washing down from the top.
    val wash = if (last.size > 1) Brush.horizontalGradient(last.map { it.copy(alpha = strength * 0.7f) })
        else Brush.verticalGradient(listOf(last[0].copy(alpha = strength), last[0].copy(alpha = strength * 0.25f), Color.Transparent))
    drawRect(wash)
  })
}

/** The subtle "Hi Maya" chip that drops in when someone's Home appears, then leaves. */
@Composable
internal fun WhosThereHiChip(modifier: Modifier = Modifier) {
  val ui by rememberWhosThereUi()
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(ui.greetedAtMs) {
    if (ui.greetedAtMs <= 0L || System.currentTimeMillis() - ui.greetedAtMs > HI_CHIP_MS) return@LaunchedEffect
    visible = true
    delay(HI_CHIP_MS)
    visible = false
  }
  val names = WhosThereViewPolicy.people(ui.view).mapNotNull { ui.person(it) }
  AnimatedVisibility(visible && names.isNotEmpty(), modifier,
      enter = fadeIn(tween(300)) + slideInVertically(tween(450)) { -it / 2 },
      exit = fadeOut(tween(400)) + slideOutVertically(tween(400)) { -it / 3 }) {
    val shape = RoundedCornerShape(40.dp)
    Row(Modifier.height(80.dp).clip(shape).background(ApprovedHomePalette.Card).approvedOutline(shape).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      WhosThereAvatar(names.firstOrNull(), 64.dp)
      val text = if (names.size > 1) "Hi " + names.joinToString(" & ") { it.name } else "Hi ${names.first().name}"
      Text(text, color = ApprovedHomePalette.Text, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
      Text(if (names.size > 1) "· shared things only" else "· showing your Home", color = ApprovedHomePalette.Secondary,
          fontSize = 21.sp, maxLines = 1, modifier = Modifier.padding(end = 20.dp))
    }
  }
}
