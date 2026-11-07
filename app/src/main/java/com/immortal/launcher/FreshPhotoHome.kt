/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.OnResume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Same amber as the slideshow's "years ago today" badge (FaceRenderer caption). */
internal val FreshBadgeAmber = Color(0xFFFFD66E)
private const val MAX_EDGE = 1280

/**
 * The Home photo widget's fresh-photo turn: draws the newly shared photo over the widget's usual
 * image and reports the badge text. One deadline timer per turn; idle when nothing is fresh.
 */
@Composable
internal fun rememberFreshHomePhoto(): FreshPhoto? {
  val context = LocalContext.current.applicationContext
  val queue by remember { FreshPhotoStore.queue(context) }.collectAsState()
  var tick by remember { mutableLongStateOf(0L) }
  OnResume { tick++ }
  // Starting a turn writes the queue, so it runs as an effect, never during composition.
  val fresh by produceState<FreshPhoto?>(null, queue.pending.size, queue.showing?.id, tick) {
    value = FreshPhotoStore.forHome(context)
  }
  LaunchedEffect(fresh, queue.showingUntil) {
    if (fresh == null) return@LaunchedEffect
    delay((queue.showingUntil - FreshPhotoStore.clock()).coerceAtLeast(1_000L))
    tick++
  }
  return fresh
}

/** Full-bleed fresh photo. INTEGRATOR NOTE: inserted inside ApprovedHomePhoto, under its scrim. */
@Composable
internal fun FreshHomePhotoLayer(fresh: FreshPhoto?, modifier: Modifier = Modifier) {
  fresh ?: return
  val image by produceState<Bitmap?>(null, fresh.path) {
    value = withContext(Dispatchers.IO) { decodeSampled(fresh.path) }
  }
  image?.let { Image(remember(it) { it.asImageBitmap() }, null, modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

/** "● New from Rohit's Pixel · just now" above the widget caption. */
@Composable
internal fun FreshHomePhotoBadge(fresh: FreshPhoto?, modifier: Modifier = Modifier) {
  fresh ?: return
  val context = LocalContext.current
  if (!ShareConfig.load(context).freshPhotoBadge) return
  val label = FreshPhotoCaption.label(fresh.from, fresh.at, FreshPhotoStore.clock(), FreshPhotoStore.zone())
  Row(modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(12.dp).background(FreshBadgeAmber, CircleShape))
    Text(label, color = FreshBadgeAmber, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
  }
}

private fun decodeSampled(path: String): Bitmap? = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(path, bounds)
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
  BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
