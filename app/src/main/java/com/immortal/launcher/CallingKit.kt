/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Calling tokens from the approved mock (`--okc`, `--badc`, `.vidscr`, `.ind`, `.ctl`). Units: dp at 1920×1280. */
internal object CallColors {
  val Ok = Color(0xFF2E9E6A)
  val Bad = Color(0xFFD93F4C)
  val Busy = Color(0xFFE0A43A)
  val Quiet = Color(0xFF8B7FD6)
  val Off = Color(0xFF9AA3AD)
  val VideoBg = Color(0xFF0B0C10)
  val RingBg = Color(0xFF0D0E12)
  val Glass = Color(0x24FFFFFF)
  val GlassEdge = Color(0x38FFFFFF)
  val GlassChip = Color(0x21FFFFFF)
  val GlassChipEdge = Color(0x24FFFFFF)
  val Bar = Color(0xB80E0F13)
  val Controls = Color(0xBD0E0F13)
  val ControlFill = Color(0x24FFFFFF)
  val LiveRed = Color(0xFFFF4D4D)
  val DotIdle = Color(0xFF8B9099)
  val BarDivider = Color(0x33FFFFFF)
  val EmberOn = Color(0x47F0A65A)
  val EmberOnIcon = Color(0xFFFFD2A1)
  val OffIcon = Color(0xFF16181D)
  val DropTop = Color(0xFFB3262F)
  val DropBottom = Color(0xFF8E1C24)
  val Toast = Color(0xE614161B)
  val Scrim = Color(0x9E0A0B0E)
  val Popover = Color(0xF518191F)
  val ShutterA = Color(0xFF1C1E23)
  val ShutterB = Color(0xFF23262C)
  val WarnTint = Color(0x2EE0A43A)
  val WarnIcon = Color(0xFFF2C26B)
  /** Avatar gradients (`a1`, `a2`) from the mock, picked by name so a room keeps its colour. */
  private val avatars = listOf(
      Color(0xFFF4B26A) to Color(0xFFC8622E), Color(0xFF86B9DD) to Color(0xFF2F5F92),
      Color(0xFFC9A0DC) to Color(0xFF7A3E9D), Color(0xFF9ED9B5) to Color(0xFF2F8A5B),
      Color(0xFF7ED6C6) to Color(0xFF1F7F6E), Color(0xFFF29E8E) to Color(0xFFC2414F),
      Color(0xFFF2D06B) to Color(0xFFC08A1E))

  fun avatar(name: String): Pair<Color, Color> = avatars[Math.floorMod(name.lowercase().hashCode(), avatars.size)]

  fun presence(p: CallPresence): Color = when (p) {
    CallPresence.AVAILABLE -> Ok
    CallPresence.IN_CALL -> Busy
    CallPresence.QUIET -> Quiet
    CallPresence.OFFLINE -> Off
  }
}

internal enum class CallButtonStyle { DEFAULT, PRIMARY, OK, BAD, GHOST, GLASS }
internal enum class CallButtonSize(val height: Dp, val text: TextUnit, val padding: Dp, val icon: Dp) {
  SM(72.dp, 24.sp, 30.dp, 28.dp), MD(88.dp, 27.sp, 40.dp, 34.dp), LG(110.dp, 33.sp, 56.dp, 38.dp)
}

/** Surface colours for pills on the current page (light/dark Calls app, or the dark call screen). */
@Immutable
internal data class CallSurface(val fill: Color, val ink: Color, val outline: Color, val accent: Color, val onAccent: Color)

internal val CallDarkSurface = CallSurface(Color(0xFF2B2B2B), Color.White, Color(0xFF555860), Color.White, Color(0xFF14161B))

/** `.btn`: a pill with an optional icon. Minimum 72 dp tall (Hearthpane targets are ≥ 64 dp). */
@Composable
internal fun CallPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null,
                      style: CallButtonStyle = CallButtonStyle.DEFAULT, size: CallButtonSize = CallButtonSize.MD,
                      enabled: Boolean = true, surface: CallSurface = CallDarkSurface) {
  val (fill, ink) = when (style) {
    CallButtonStyle.DEFAULT -> surface.fill to surface.ink
    CallButtonStyle.PRIMARY -> surface.accent to surface.onAccent
    CallButtonStyle.OK -> CallColors.Ok to Color.White
    CallButtonStyle.BAD -> CallColors.Bad to Color.White
    CallButtonStyle.GHOST -> Color.Transparent to surface.ink
    CallButtonStyle.GLASS -> CallColors.Glass to Color.White
  }
  val edge = when (style) {
    CallButtonStyle.DEFAULT -> surface.outline
    CallButtonStyle.GLASS -> CallColors.GlassEdge
    else -> null
  }
  val shape = RoundedCornerShape(50)
  Row(modifier.alpha(if (enabled) 1f else DISABLED_ALPHA).height(size.height).background(fill, shape)
      .let { if (edge != null) it.border(OUTLINE, edge, shape) else it }
      .callClick(label, enabled, onClick).padding(horizontal = size.padding),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
    icon?.let { CallIcon(it, ink, size.icon) }
    Text(label, color = ink, fontSize = size.text, fontWeight = FontWeight.SemiBold, maxLines = 1,
        overflow = if (currentLayoutClass().compact) TextOverflow.Ellipsis else TextOverflow.Clip)
  }
}

/** `.rb`: an 88 dp round button with an optional count badge. */
@Composable
internal fun CallRoundButton(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0,
                             surface: CallSurface = CallDarkSurface, size: Dp = 88.dp) {
  Box(modifier.size(size)) {
    Box(Modifier.size(size).background(surface.fill, CircleShape).border(OUTLINE, surface.outline, CircleShape)
        .callClick(label, true, onClick), contentAlignment = Alignment.Center) { CallIcon(icon, surface.ink, size * ICON_SHARE) }
    if (badge > 0) Box(Modifier.align(Alignment.TopEnd).padding(0.dp).heightIn(min = 36.dp).width(36.dp)
        .background(CallColors.Bad, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
      Text(badge.coerceAtMost(MAX_BADGE).toString(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
  }
}

internal enum class CallChipTone { PLAIN, OK, WARN, QUIET, RED }

/** `.chip`: 56 dp, pill, tinted by tone. */
@Composable
internal fun CallChip(text: String, icon: String? = null, tone: CallChipTone = CallChipTone.PLAIN,
                      plainFill: Color = Color(0xFF363636), plainInk: Color = Color(0xFFB8B8B8)) {
  val (fill, ink) = when (tone) {
    CallChipTone.PLAIN -> plainFill to plainInk
    CallChipTone.OK -> CallColors.Ok.copy(alpha = 0.16f) to Color(0xFF66C49E)
    CallChipTone.WARN -> CallColors.Busy.copy(alpha = 0.2f) to CallColors.Busy
    CallChipTone.QUIET -> CallColors.Quiet.copy(alpha = 0.2f) to Color(0xFFB4ABF0)
    CallChipTone.RED -> CallColors.Bad.copy(alpha = 0.18f) to Color(0xFFFF747D)
  }
  Row(Modifier.height(56.dp).background(fill, RoundedCornerShape(50)).padding(horizontal = 24.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    icon?.let { CallIcon(it, ink, 26.dp) }
    Text(text, color = ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** `.gchip`: the white-on-glass chip used on call screens. */
@Composable
internal fun CallGlassChip(text: String, icon: String? = null) {
  val shape = RoundedCornerShape(50)
  Row(Modifier.height(64.dp).background(CallColors.GlassChip, shape).border(OUTLINE, CallColors.GlassChipEdge, shape)
      .padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    icon?.let { CallIcon(it, Color.White, 28.dp) }
    Text(text, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** A room avatar: gradient tile (radius 28%) with initials, optional presence dot. */
@Composable
internal fun CallRoomAvatar(name: String, size: Dp, presence: CallPresence? = null, ring: Color = Color(0xFF2B2B2B)) {
  val (a1, a2) = remember(name) { CallColors.avatar(name) }
  val shape = RoundedCornerShape(percent = AVATAR_RADIUS_PCT)
  Box(Modifier.size(size)) {
    Box(Modifier.size(size).background(Brush.radialGradient(listOf(a1, a2), center = Offset.Unspecified), shape)
        .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
      Text(CallingCopy.initials(name), color = Color.White, fontSize = (size.value * INITIALS_SHARE).sp, fontWeight = FontWeight.Bold)
    }
    presence?.let { p ->
      val dot = size * DOT_SHARE
      Box(Modifier.align(Alignment.BottomEnd).size(dot).background(ring, CircleShape).padding(dot * DOT_RING)
          .background(CallColors.presence(p), CircleShape))
    }
  }
}

/** `.rip`: two expanding rings around an avatar while it rings. Static when [motion] is off. */
@Composable
internal fun CallRipple(size: Dp, motion: Boolean, content: @Composable () -> Unit) {
  Box(Modifier.size(size), contentAlignment = Alignment.Center) {
    if (motion) {
      val t = rememberInfiniteTransition(label = "ripple")
      val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(RIPPLE_MS, easing = FastOutSlowInEasing)), label = "p")
      Box(Modifier.size(size).drawBehind {
        val base = this.size.minDimension / 2 + RIPPLE_INSET.toPx()
        for (offset in listOf(0f, 0.5f)) {
          val f = (p.value + offset) % 1f
          drawCircle(Color.White.copy(alpha = RIPPLE_ALPHA * (1f - f)), radius = base * (RIPPLE_FROM + (RIPPLE_TO - RIPPLE_FROM) * f),
              style = Stroke(RIPPLE_STROKE.toPx()))
        }
      })
    }
    content()
  }
}

/** The ring background: near-black with two soft blobs in the caller's colours, drifting slowly. */
@Composable
internal fun CallRingBackground(name: String, motion: Boolean, modifier: Modifier = Modifier) {
  val (a1, a2) = remember(name) { CallColors.avatar(name) }
  val drift = if (motion) {
    val t = rememberInfiniteTransition(label = "blobs")
    t.animateFloat(0f, 1f, infiniteRepeatable(tween(BLOB_MS, easing = LinearEasing), RepeatMode.Reverse), label = "d").value
  } else 0.5f
  Box(modifier.fillMaxSize().background(CallColors.RingBg).drawBehind {
    val w = this.size.width
    val h = this.size.height
    val r = minOf(w, h) * BLOB_RADIUS
    drawCircle(Brush.radialGradient(listOf(a1.copy(alpha = BLOB_ALPHA), Color.Transparent),
        Offset(w * (0.22f + 0.08f * drift), h * (0.3f + 0.1f * drift)), r), r, Offset(w * (0.22f + 0.08f * drift), h * (0.3f + 0.1f * drift)))
    drawCircle(Brush.radialGradient(listOf(a2.copy(alpha = BLOB_ALPHA), Color.Transparent),
        Offset(w * (0.78f - 0.08f * drift), h * (0.72f - 0.1f * drift)), r), r, Offset(w * (0.78f - 0.08f * drift), h * (0.72f - 0.1f * drift)))
  })
}

/** `.bigb`: 176 dp ring answer/decline buttons with a label under them. */
@Composable
internal fun CallBigButton(icon: String, label: String, color: Color, onClick: () -> Unit, nudge: Boolean = false,
                           iconRotation: Float = 0f, size: Dp = 176.dp, iconSize: Dp = 70.dp) {
  val dy = if (nudge) {
    val t = rememberInfiniteTransition(label = "nudge")
    t.animateFloat(0f, 1f, infiniteRepeatable(tween(NUDGE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y").value
  } else 0f
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
    Box(Modifier.graphicsLayer { translationY = -NUDGE_DP * density * dy }.size(size).background(color, CircleShape)
        .callClick(label, true, onClick), contentAlignment = Alignment.Center) {
      CallIcon(icon, Color.White, iconSize, Modifier.graphicsLayer { rotationZ = iconRotation })
    }
    Text(label, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
  }
}

/** Title + subtitle block used on ring and result screens. */
@Composable
internal fun CallHeadline(title: String, sub: String?, titleSize: TextUnit = 72.sp, subSize: TextUnit = 30.sp) {
  // Compact (the Mini): the subtitle wraps within the window instead of a fixed 1100 dp column.
  val compact = currentLayoutClass().compact
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(title, color = Color.White, fontSize = titleSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        letterSpacing = (-0.5).sp, maxLines = 2, overflow = if (compact) TextOverflow.Ellipsis else TextOverflow.Clip)
    sub?.let { Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = subSize, textAlign = TextAlign.Center,
        modifier = if (compact) Modifier.widthIn(max = 1100.dp) else Modifier.width(1100.dp)) }
  }
}

/** Accessible click with a ripple and a button role. */
@Composable
internal fun Modifier.callClick(label: String, enabled: Boolean, onClick: () -> Unit): Modifier =
    semantics { role = Role.Button; contentDescription = label }
        .clickable(remember { MutableInteractionSource() }, ripple(), enabled = enabled, role = Role.Button, onClick = onClick)

internal fun Modifier.callPanel(fill: Color, shape: Shape = RoundedCornerShape(32.dp), edge: Color? = null): Modifier =
    background(fill, shape).let { if (edge != null) it.border(OUTLINE, edge, shape) else it }

private val OUTLINE = 1.5.dp
private const val DISABLED_ALPHA = 0.4f
private const val ICON_SHARE = 0.43f
private const val MAX_BADGE = 99
private const val AVATAR_RADIUS_PCT = 28
private const val INITIALS_SHARE = 0.4f
private const val DOT_SHARE = 0.26f
private const val DOT_RING = 0.16f
private const val RIPPLE_MS = 2_400
private val RIPPLE_INSET = 20.dp
private val RIPPLE_STROKE = 4.dp
private const val RIPPLE_ALPHA = 0.35f
private const val RIPPLE_FROM = 0.9f
private const val RIPPLE_TO = 1.45f
private const val BLOB_MS = 22_000
private const val BLOB_RADIUS = 0.55f
private const val BLOB_ALPHA = 0.55f
private const val NUDGE_MS = 900
private const val NUDGE_DP = 10f
