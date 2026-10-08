/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Android regressions with owned pixels and captured callbacks; no user position writes. */
internal object SlideshowContractDeviceChecks {
  fun run(instrumentation: Instrumentation, context: Context, storage: File, tall: File,
      small: File, demand: (String, Boolean) -> Unit): JSONObject {
    check(storage.mkdirs())
    fun <T> main(block: () -> T): T {
      var result: T? = null
      var failure: Throwable? = null
      instrumentation.runOnMainSync { try { result = block() } catch (error: Throwable) { failure = error } }
      failure?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return result as T
    }
    val scoped = object : ContextWrapper(context) { override fun getFilesDir(): File = storage }
    val worker = SlideshowLoader(scoped)
    val delivered = ArrayList<Bitmap>()
    val decodeCalls = AtomicInteger()
    val revision = "encoded:" + MessageDigest.getInstance("SHA-256").digest(tall.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val identify: (Bitmap) -> String? = { revision }
    val decode: (Int) -> Bitmap? = { edge ->
      decodeCalls.incrementAndGet()
      SlideshowDecoder.file(tall.path, edge)
    }
    // Reflect the exact six-argument constructor so cached older app classes can still compile
    // the sidecar; execution requires the current identity callback contract.
    fun identified(source: String): SlideshowAsset {
      val constructor = SlideshowAsset::class.java.declaredConstructors.singleOrNull {
        it.parameterTypes.size == 6 && it.parameterTypes.last().name == "kotlin.jvm.functions.Function1"
      }
      demand("loader_encoded_identity_contract_available", constructor != null)
      return checkNotNull(constructor).newInstance(source, decode, null, false, true, identify) as SlideshowAsset
    }
    fun load(asset: SlideshowAsset, pair: Boolean, partners: List<SlideshowAsset> = emptyList(),
        next: SlideshowAsset? = null): Pair<List<SlideshowPhoto>, String?> {
      val ready = CountDownLatch(1)
      var result: Pair<List<SlideshowPhoto>, String?>? = null
      var failed = false
      main {
        worker.load(asset, partners, next, pair, false, { photos, companion ->
          delivered.addAll(photos.map { it.bitmap })
          result = photos to companion
          ready.countDown()
        }, { failed = true; ready.countDown() })
      }
      demand("identity_loader_callback_arrived", ready.await(5, TimeUnit.SECONDS))
      demand("identity_loader_succeeded", !failed && result != null)
      return checkNotNull(result)
    }
    fun retire(photos: List<SlideshowPhoto>) { photos.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() } }
    fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    val result = JSONObject()
    try {
      val asset = identified("owned-url:A")
      val cold = load(asset, false).first.single()
      demand("cold_identity_uses_encoded_revision", cold.key == revision && cold.bitmap.height == 2200)
      val coldKey = cold.key
      retire(listOf(cold))
      val shrunk = load(asset, true).first.single()
      demand("first_portrait_identity_survives_pair_shrink",
          shrunk.key == coldKey && shrunk.bitmap.height == 1400)
      retire(listOf(shrunk))

      val seed = load(SlideshowAsset("owned-url:seed",
          { edge -> SlideshowDecoder.file(small.path, edge) }), false, next = asset).first
      val stop = SystemClock.elapsedRealtime() + 5000
      var warm: Any? = null
      while (SystemClock.elapsedRealtime() < stop) {
        warm = synchronized(worker) { field(worker, "lookahead") }
        if (warm != null) break
        SystemClock.sleep(50)
      }
      demand("identity_warm_2400_asset_acquired", warm != null &&
          (field(checkNotNull(warm), "bitmap") as Bitmap).height == 2200)
      demand("warm_caches_encoded_identity_before_resize", field(checkNotNull(warm), "identity") == coldKey)
      val callsBeforeWarm = decodeCalls.get()
      val warmed = load(asset, true).first.single()
      demand("warm_identity_survives_2400_to_1400_resize",
          warmed.key == coldKey && warmed.bitmap.height == 1400 && decodeCalls.get() == callsBeforeWarm)
      demand("warm_keeps_navigation_source_key", field(warmed, "sourceKey") == asset.key)
      retire(seed); retire(listOf(warmed))

      val duplicate = identified("owned-url:B") // Same encoded bytes, different source URL.
      val paired = load(asset, true, listOf(duplicate))
      demand("identical_content_partner_urls_do_not_form_pair",
          paired.first.size == 1 && paired.second == null && paired.first.single().key == coldKey)
      retire(paired.first)
      result.put("stableEncodedRevisionAcrossSizes", true).put("duplicateContentPartnerRejected", true)
    } finally {
      main { worker.close() }
      delivered.distinct().forEach { if (!it.isRecycled) it.recycle() }
    }

    val first = checkNotNull(SlideshowDecoder.file(small.path))
    val second = checkNotNull(SlideshowDecoder.file(small.path))
    val stage = main { SlideshowStage(context) }
    data class Save(val key: String, val draft: SlideshowAdjustment?, val callback: (Boolean) -> Unit)
    val saves = ArrayList<Save>()
    val controls = main {
      SlideshowViewerControls(context, stage, {}, {}, { if (it) stage.pause() else stage.resume() }, {},
          { key, draft, callback -> saves += Save(key, draft, callback) })
    }
    fun invoke(name: String) {
      controls.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(controls)
    }
    fun drag(action: Int, x: Float, y: Float) {
      val event = MotionEvent.obtain(1, 2, action, x, y, 0)
      try { controls.dispatchViewerTouch(event) } finally { event.recycle() }
    }
    try {
      main {
        stage.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        stage.layout(0, 0, 800, 600)
        controls.visibility = View.VISIBLE
        controls.layout(0, 450, 800, 700)
        val key = "owned-callback:same-asset"
        stage.show(listOf(SlideshowPhoto(key, first)), false, false, 8000)
        invoke("beginEdit"); invoke("save")
        demand("old_save_callback_captured_without_persistence", saves.size == 1)
        controls.photoChanged()
        stage.show(listOf(SlideshowPhoto(key, second)), false, false, 8000)
        invoke("beginEdit")
        val layer = (0 until stage.childCount).map { stage.getChildAt(it) }
            .first { it.visibility == View.VISIBLE } as android.view.ViewGroup
        val holder = layer.getChildAt(0) as android.view.ViewGroup
        val image = holder.getChildAt(0)
        val initialDraft = field(controls, "draft") as SlideshowAdjustment
        stage.adjust(SlideshowAdjustment(1.4f, .4f, .6f))
        demand("drag_zoom_updates_transform_without_recreating_photo_views",
            layer.getChildAt(0) === holder && holder.getChildAt(0) === image &&
                image.scaleX > 0f)
        stage.adjust(initialDraft)
        result.put("editingReusesPhotoViews", true)
        saves.first().callback(true)
        demand("old_save_success_cannot_close_new_editor_for_same_asset",
            field(controls, "editing") == true && field(controls, "saving") == false &&
                stage.currentPhoto?.bitmap === second)
        saves.first().callback(false)
        demand("old_save_failure_cannot_mutate_new_editor_for_same_asset",
            field(controls, "editing") == true && field(controls, "saving") == false)

        invoke("save")
        demand("current_save_callback_captured_without_persistence", saves.size == 2)
        val submitted = saves[1].draft
        val photoDraft = stage.currentPhoto?.adjustment
        drag(MotionEvent.ACTION_DOWN, 100f, 100f)
        drag(MotionEvent.ACTION_MOVE, 180f, 150f)
        drag(MotionEvent.ACTION_UP, 180f, 150f)
        demand("move_while_saving_cannot_mutate_draft_or_pixels",
            field(controls, "draft") == submitted && stage.currentPhoto?.adjustment == photoDraft)
        saves[1].callback(false)
        demand("failed_save_leaves_current_editor_retryable",
            field(controls, "editing") == true && field(controls, "saving") == false)
        invoke("save")
        demand("failed_save_retry_submits_unchanged_draft", saves.size == 3 && saves[2].draft == submitted)
        saves[2].callback(false)
        controls.cancelEdit()
        result.put("sameAssetOldSaveCallbacksIgnored", true).put("savingMoveAndFailedRetryStable", true)
      }
    } finally {
      main { controls.stop(); stage.stop() }
      if (!first.isRecycled) first.recycle()
      if (!second.isRecycled) second.recycle()
    }
    return result
  }
}
