/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Exercises the production renderer's retained bucket boundary with inert Android views. */
class FaceRendererPaperBackingTest {
  @Test fun mixedChildrenKeepIndependentColorsAndStablePadding() = fixture { renderer ->
    val clock = Mockito.mock(View::class.java)
    val caption = Mockito.mock(View::class.java)
    val bucket = bucket(renderer, listOf(clock, caption))
    @Suppress("UNCHECKED_CAST")
    val colors = field(renderer, "paperForegrounds").get(renderer) as MutableMap<View, Int>
    colors[clock] = 0xFF343A30.toInt()
    Mockito.mockConstruction(PaperWidgetBackground::class.java).use { backgrounds ->
      renderer.setPaperPhotoSurface(true)
      renderer.refreshPaperPhotoSurface()
      assertEquals(1, backgrounds.constructed().size)
      val background = backgrounds.constructed().single()
      Mockito.verify(background, Mockito.times(2)).update(listOf(
          clock to 0xFF343A30.toInt(), caption to 0xFFFFFFFF.toInt()))
      Mockito.verify(bucket, Mockito.times(1)).setPadding(12, 12, 12, 12)
      renderer.setPaperPhotoSurface(false)
      assertNull(bucket.background)
      Mockito.verify(bucket).setPadding(0, 0, 0, 0)
      Mockito.verify(clock, Mockito.never()).setBackground(Mockito.any())
      Mockito.verify(caption, Mockito.never()).setBackground(Mockito.any())
    }
  }

  @Test fun hiddenNestedMetadataCreatesNeitherPlateNorPhotoReservation() = fixture { renderer ->
    val hidden = Mockito.mock(TextView::class.java)
    Mockito.`when`(hidden.visibility).thenReturn(View.GONE)
    val wrapper = Mockito.mock(LinearLayout::class.java)
    Mockito.`when`(wrapper.childCount).thenReturn(1)
    Mockito.`when`(wrapper.getChildAt(0)).thenReturn(hidden)
    val bucket = bucket(renderer, listOf(wrapper))
    Mockito.`when`(bucket.width).thenReturn(300)
    Mockito.`when`(bucket.height).thenReturn(24)
    Mockito.mockConstruction(PaperWidgetBackground::class.java).use { backgrounds ->
      renderer.setPaperPhotoSurface(true)
      assertTrue(backgrounds.constructed().isEmpty())
      assertTrue(renderer.photoOcclusions().isEmpty())
      Mockito.verify(bucket, Mockito.never()).setPadding(Mockito.anyInt(), Mockito.anyInt(),
          Mockito.anyInt(), Mockito.anyInt())
    }
  }

  @Test fun retainedBucketRetiresAndReadmitsBackingAsContentHidesAndReturns() = fixture { renderer ->
    val timer = Mockito.mock(TextView::class.java)
    Mockito.`when`(timer.currentTextColor).thenReturn(0xFF000000.toInt())
    val bucket = bucket(renderer, listOf(timer))
    Mockito.mockConstruction(PaperWidgetBackground::class.java).use { backgrounds ->
      renderer.setPaperPhotoSurface(true)
      Mockito.verify(backgrounds.constructed().single()).update(listOf(timer to 0xFF000000.toInt()))
      Mockito.`when`(timer.visibility).thenReturn(View.GONE)
      renderer.refreshPaperPhotoSurface()
      assertNull(bucket.background)
      renderer.refreshPaperPhotoSurface()
      Mockito.verify(bucket, Mockito.times(1)).setPadding(0, 0, 0, 0)
      Mockito.`when`(timer.visibility).thenReturn(View.VISIBLE)
      renderer.refreshPaperPhotoSurface()
      assertEquals(2, backgrounds.constructed().size)
      Mockito.verify(bucket, Mockito.times(2)).setPadding(12, 12, 12, 12)
    }
  }

  private fun bucket(renderer: FaceRenderer, children: List<View>): LinearLayout {
    val bucket = Mockito.mock(LinearLayout::class.java)
    Mockito.`when`(bucket.childCount).thenReturn(children.size)
    children.forEachIndexed { i, child -> Mockito.`when`(bucket.getChildAt(i)).thenReturn(child) }
    var background: Drawable? = null
    Mockito.`when`(bucket.background).thenAnswer { background }
    Mockito.doAnswer { background = it.getArgument(0); null }.`when`(bucket).setBackground(Mockito.any())
    @Suppress("UNCHECKED_CAST")
    val buckets = field(renderer, "buckets").get(renderer) as MutableMap<GridPosition, LinearLayout>
    buckets[GridPosition.BOTTOM_RIGHT] = bucket
    return bucket
  }

  private fun fixture(test: (FaceRenderer) -> Unit) {
    val context = Mockito.mock(Context::class.java)
    val resources = Mockito.mock(Resources::class.java)
    val metrics = Mockito.mock(DisplayMetrics::class.java).apply { density = 1f }
    val prefs = Mockito.mock(SharedPreferences::class.java)
    Mockito.`when`(context.resources).thenReturn(resources)
    Mockito.`when`(resources.displayMetrics).thenReturn(metrics)
    Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(prefs)
    Mockito.mockStatic(Looper::class.java).use { loops ->
      loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(Mockito.mock(Looper::class.java))
      Mockito.mockConstruction(Handler::class.java).use {
        Mockito.mockConstruction(FrameLayout::class.java).use {
          Mockito.mockConstruction(FrameLayout.LayoutParams::class.java).use {
            val renderer = FaceRenderer(context)
            try { test(renderer) } finally { renderer.stop() }
          }
        }
      }
    }
  }

  private fun field(value: Any, name: String) =
      value.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
