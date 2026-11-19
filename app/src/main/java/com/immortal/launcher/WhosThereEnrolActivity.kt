/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.security.SecureRandom

private enum class EnrolStep { FORM, CONSENT, READY, CAPTURE, DONE }

/**
 * Settings › People › Add person (or Set up again): name and role, a plain-words consent, then
 * the guided 4-look capture with a visible live preview. Only numbers are saved, on this Portal.
 */
class WhosThereEnrolActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WhosThere.install(this)
    val existing = intent.getStringExtra(EXTRA_ID)
    setContent { PortalPrimeTheme(darkTheme = false) { EnrolFlow(existing) { finish() } } }
  }

  override fun onPause() {
    super.onPause()
    // The camera is only for set-up in front of the Portal: leaving ends it (and nothing is kept).
    if (!isChangingConfigurations) WhosThereEnrolCapture.cancelActive()
  }

  companion object {
    const val EXTRA_ID = "person"
  }
}

@Composable
private fun EnrolFlow(existingId: String?, onClose: () -> Unit) {
  val context = LocalContext.current
  val household by WhosThereStore.householdState.collectAsState()
  val settings by WhosThereStore.settingsState.collectAsState()
  val existing = existingId?.let(household::person)
  var step by remember { mutableStateOf(if (existing != null) EnrolStep.CONSENT else EnrolStep.FORM) }
  var draft by remember {
    mutableStateOf(existing ?: WhosTherePerson(newId(), "", WhosThereRole.ADULT,
        WhosThereColors.firstFree(household.people.map { it.colorIndex }), System.currentTimeMillis()))
  }
  var error by remember { mutableStateOf<String?>(null) }
  var enable by remember { mutableStateOf(false) }
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) startCapture { err -> if (err == null) step = EnrolStep.CAPTURE else error = err }
    else error = "The camera permission is needed to set up a face."
  }
  BackHandler { if (step == EnrolStep.CAPTURE) WhosThereEnrolCapture.cancelActive(); onClose() }
  when (step) {
    EnrolStep.FORM -> WhosThereEnrolForm(draft, household.people.filter { it.id != draft.id }.map { it.name },
        onChange = { draft = it }, onNext = { step = EnrolStep.CONSENT }, onCancel = onClose)
    EnrolStep.CONSENT -> WhosThereConsent(draft, onAgree = { step = EnrolStep.READY }, onCancel = onClose)
    EnrolStep.READY -> CaptureFrame(onCancel = onClose, cameraOn = false) {
      WhosThereAvatar(draft, 220.dp)
      Spacer(Modifier.height(36.dp))
      CaptureTitle("Ready when you are, ${draft.name}")
      CaptureHint("Stand about an arm’s length from the screen. The camera turns on only after you tap Start.")
      Spacer(Modifier.height(36.dp))
      LightButton("Start face set-up") {
        error = null
        if (WhosThereCamera.granted(context)) startCapture { err -> if (err == null) step = EnrolStep.CAPTURE else error = err }
        else permission.launch(Manifest.permission.CAMERA)
      }
      error?.let { Spacer(Modifier.height(20.dp)); CaptureHint(it, Color(0xFFFFB4A8)) }
      Spacer(Modifier.height(30.dp))
      CaptureHint("Nothing is recorded. Frames are turned into a template in memory, then thrown away.")
    }
    EnrolStep.CAPTURE -> WhosThereEnrolCapture(draft, onCancel = onClose, onDone = { looks ->
      WhosThereStore.updateHousehold(context, { h -> h.upsert(draft).withTemplates(FaceTemplates(draft.id, looks)) })
      WhosThereStore.record(context, AuditKind.ENROLLED)
      step = EnrolStep.DONE
    }, onError = { error = it; step = EnrolStep.READY })
    EnrolStep.DONE -> CaptureFrame(onCancel = onClose, cameraOn = false, cancelLabel = "Close") {
      Box(Modifier.size(180.dp).clip(CircleShape).background(Color(0xFF2BB673)), contentAlignment = Alignment.Center) {
        WhosThereGlyph(WtGlyph.CHECK, Color.White, 110.dp)
      }
      Spacer(Modifier.height(36.dp))
      CaptureTitle(if (draft.role.parentManaged) "You did it, ${draft.name}! ⭐" else "Got it, ${draft.name}")
      CaptureHint("A face template of numbers is saved, encrypted, on this Portal only. No photo was kept.")
      Spacer(Modifier.height(36.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        if (!settings.enabled) LightButton("Turn on recognition") { enable = true }
        LightButton("Done", subtle = !settings.enabled) { onClose() }
      }
    }
  }
  if (enable) WhosThereEnableDialog(onDismiss = { enable = false })
}

/** Loads the models first; reports a friendly reason if this Portal can't run them. */
private fun startCapture(onResult: (String?) -> Unit) {
  WhosThere.ensureModels {
    onResult(WhosThere.modelsUnavailable() ?: if (WhosThere.modelsForEnrolment() == null) FaceModels.UNAVAILABLE else null)
  }
}

private val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
private fun newId(): String = SecureRandom().let { r -> String(CharArray(12) { ID_CHARS[r.nextInt(ID_CHARS.length)] }) }

@Composable
internal fun CaptureFrame(onCancel: () -> Unit, cameraOn: Boolean, cancelLabel: String = "Cancel", content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF1A1F2B), Color(0xFF0B0D12)))),
      contentAlignment = Alignment.TopCenter) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        val pill = RoundedCornerShape(32.dp)
        Row(Modifier.height(64.dp).clip(pill).background(Color.White.copy(alpha = if (cameraOn) .10f else .06f)).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          if (cameraOn) Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF2BB673)))
          else WhosThereGlyph(WtGlyph.CAMERA_OFF, Color.White.copy(alpha = .8f), 24.dp)
          Text(if (cameraOn) "Camera on for set-up · only a template of numbers is kept" else "Camera not in use",
              color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        LightButton(cancelLabel, subtle = true, onClick = onCancel)
      }
      Spacer(Modifier.height(48.dp))
      content()
    }
  }
}

@Composable
internal fun CaptureTitle(text: String) =
    Text(text, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)

@Composable
internal fun CaptureHint(text: String, color: Color = Color.White.copy(alpha = .72f)) =
    Text(text, color = color, fontSize = 23.sp, lineHeight = 32.sp, modifier = Modifier.padding(top = 10.dp))

@Composable
internal fun LightButton(label: String, subtle: Boolean = false, onClick: () -> Unit) {
  val shape = RoundedCornerShape(34.dp)
  Box(Modifier.height(68.dp).clip(shape).background(if (subtle) Color.White.copy(alpha = .12f) else Color.White)
      .tvFocusable(shape, focusScale = 1f, ringColor = Color.White, onClick = onClick).padding(horizontal = 30.dp),
      contentAlignment = Alignment.Center) {
    Text(label, color = if (subtle) Color.White else Color(0xFF111111), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
  }
}
