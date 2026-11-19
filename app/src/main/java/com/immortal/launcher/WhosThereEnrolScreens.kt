/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Mint = Color(0xFF7FE0B0)

/** Step 1: who is this — name, adult / teen / kid, colour. */
@Composable
internal fun WhosThereEnrolForm(draft: WhosTherePerson, others: List<String>, onChange: (WhosTherePerson) -> Unit,
                                onNext: () -> Unit, onCancel: () -> Unit) {
  var tried by remember { mutableStateOf(false) }
  val error = WhosThereLimits.validName(draft.name, others)
  SettingsScaffold(title = "Add person", onBack = onCancel) {
    SectionLabel("Name")
    IosTextField(draft.name, { onChange(draft.copy(name = it.take(WhosThereLimits.MAX_NAME))) }, "First name")
    if (tried && error != null) Text(error, color = IosRed, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp))
    SectionLabel("Who are they?")
    Card {
      WhosThereRole.entries.forEachIndexed { i, role ->
        if (i > 0) Divider()
        NavRow(role.label, value = if (draft.role == role) "✓" else "", subLabel = when (role) {
          WhosThereRole.ADULT -> "Their own Home, with private reminders and events"
          WhosThereRole.TEEN -> "13–17: their own Home, chores, and they can pause recognition"
          WhosThereRole.KID -> "Under 13: parent-managed, routines and chores, nothing private"
        }) { onChange(draft.copy(role = role)) }
      }
    }
    SectionLabel("Colour")
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      WhosThereColors.all.forEachIndexed { i, c ->
        val selected = draft.colorIndex == i
        Box(Modifier.size(64.dp).clip(CircleShape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(c.from.argb(), c.to.argb())))
            .then(if (selected) Modifier.border(4.dp, IosLabel, CircleShape) else Modifier)
            .tvFocusable(CircleShape, focusScale = 1f, ringColor = IosBlue) { onChange(draft.copy(colorIndex = i)) })
      }
    }
    Spacer(Modifier.height(28.dp))
    PrimaryPillButton("Next") { tried = true; if (error == null) { onChange(draft.copy(name = WhosThereLimits.cleanName(draft.name))); onNext() } }
  }
}

/** Step 2: consent in plain words. Nothing happens until the box is ticked; Cancel keeps nothing. */
@Composable
internal fun WhosThereConsent(p: WhosTherePerson, onAgree: () -> Unit, onCancel: () -> Unit) {
  var agreed by remember { mutableStateOf(false) }
  val kid = p.role.parentManaged
  SettingsScaffold(title = "Before we start", onBack = onCancel) {
    Card {
      CONSENT.forEachIndexed { i, (title, body) ->
        if (i > 0) Divider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp)) {
          Text(title, color = IosLabel, fontSize = Sizes.rowTitle, lineHeight = 34.sp)
          Text(body.replace("{name}", p.name.ifBlank { "this person" }), color = IosSecondary, fontSize = Sizes.rowSub,
              lineHeight = 28.sp, modifier = Modifier.padding(top = 6.dp))
        }
      }
    }
    Spacer(Modifier.height(20.dp))
    Card {
      ToggleRow(if (kid) "I’m ${p.name}’s parent or guardian, and I agree" else "I’m ${p.name}, an adult, and I agree", agreed) { agreed = it }
    }
    Spacer(Modifier.height(24.dp))
    PrimaryPillButton("Continue", enabled = agreed, onClick = onAgree)
    Spacer(Modifier.height(12.dp))
    ApprovedSettingsTextButton("Cancel — keep nothing", onCancel)
  }
}

private val CONSENT = listOf(
    "What’s kept" to "A face template: a short list of numbers (about 4 KB) made from 8 looks at {name}. Not a photo. It can’t be turned back into one.",
    "Where" to "Only on this Portal, encrypted. Never sent anywhere, never on a phone or in the cloud. Other Portals need their own set-up.",
    "What it’s for" to "Showing {name}’s own Home when they walk up. It is not a lock and never unlocks or buys anything.",
    "What’s never kept" to "Photos, video, sound, or the faces of visitors. Someone the Portal doesn’t know is forgotten at once.",
    "How long" to "Until you delete it: Settings › People › {name} › Delete, or Delete all on the Privacy page.",
)

/** The live capture: ring of 64 ticks around the visible preview, front → left → right → up. */
internal object WhosThereEnrolCapture {
  @Volatile private var active: WhosThereEnrolSession? = null
  fun cancelActive() { active?.cancel(); active = null }
  internal fun set(s: WhosThereEnrolSession?) { active = s }
}

@Composable
internal fun WhosThereEnrolCapture(p: WhosTherePerson, onCancel: () -> Unit, onDone: (List<Embedding>) -> Unit, onError: (String) -> Unit) {
  val context = LocalContext.current
  var state by remember { mutableStateOf(EnrolState()) }
  val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
  DisposableEffect(Unit) {
    WhosThere.enrolling = true
    onDispose {
      WhosThereEnrolCapture.cancelActive()
      WhosThere.enrolling = false
    }
  }
  CaptureFrame(onCancel = { WhosThereEnrolCapture.cancelActive(); onCancel() }, cameraOn = true) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      val ring = min(min(maxWidth, 600.dp), if (portrait) 600.dp else 470.dp)
      Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
        Ring(WhosThereEnrolPolicy.progress(state))
        Box(Modifier.size(ring - 96.dp).clip(CircleShape).background(Color(0xFF1A1F2B))) {
          AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            TextureView(ctx).apply {
              surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var surface: Surface? = null
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                  st.setDefaultBufferSize(WhosThereCamera.WIDTH, WhosThereCamera.HEIGHT)
                  setTransform(android.graphics.Matrix().apply { setScale(4f / 3f, 1f, w / 2f, h / 2f) })
                  val s = Surface(st).also { surface = it }
                  val (models, analysis) = WhosThere.modelsForEnrolment() ?: return onError(FaceModels.UNAVAILABLE)
                  val rotations = if (portrait) listOf(270, 90, 0) else listOf(0)
                  val session = WhosThereEnrolSession(context, s, models, analysis, rotations, onState = { state = it },
                      onFinished = { looks, err ->
                        WhosThereEnrolCapture.set(null)
                        when {
                          looks != null -> onDone(looks)
                          err != null -> onError(err)
                        }
                      })
                  WhosThereEnrolCapture.set(session)
                  post { if (surface === s) session.start() }
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                  WhosThereEnrolCapture.cancelActive(); surface?.release(); surface = null
                  return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
              }
            }
          })
        }
      }
    }
    val look = state.currentLook ?: EnrolLook.UP
    val kid = p.role.parentManaged
    Spacer(Modifier.height(24.dp))
    CaptureTitle(if (kid) look.kidTitle else look.title)
    CaptureHint(state.hint.text.ifEmpty { if (kid) look.kidHint else look.hint })
    Spacer(Modifier.height(22.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      EnrolLook.entries.forEachIndexed { i, l ->
        val done = i < state.look
        val on = i == state.look
        val shape = RoundedCornerShape(22.dp)
        Row(Modifier.height(44.dp).clip(shape).background(if (on) Color.White else Color.White.copy(alpha = .10f)).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (done) WhosThereGlyph(WtGlyph.CHECK, Mint, 20.dp)
          Text(LOOK_LABELS[i], color = if (on) Color(0xFF111111) else if (done) Mint else Color.White.copy(alpha = .7f),
              fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}

private val LOOK_LABELS = listOf("Front", "Left", "Right", "Up")

@Composable
private fun Ring(progress: Float) {
  Canvas(Modifier.fillMaxSize()) {
    val n = 64
    val filled = (n * progress).toInt()
    val r = size.minDimension / 2 - 10.dp.toPx()
    val inner = r - 22.dp.toPx()
    val c = center
    for (k in 0 until n) {
      val a = (k.toFloat() / n) * 2 * PI.toFloat() - PI.toFloat() / 2
      drawLine(if (k < filled) Mint else Color.White.copy(alpha = .18f),
          Offset(c.x + cos(a) * inner, c.y + sin(a) * inner), Offset(c.x + cos(a) * r, c.y + sin(a) * r),
          strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
    }
  }
}
