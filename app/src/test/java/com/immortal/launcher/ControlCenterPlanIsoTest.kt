/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 3D view's math: the mock's isometric matrix, the fit, the 2D → 3D blend and the walls in painter's order. */
class ControlCenterPlanIsoTest {
  private val kitchen = CcRoom("kitchen", "Kitchen", CcRect(0, 0, 10, 8))
  private val living = CcRoom("living", "Living room", CcRect(10, 0, 12, 8))
  private val hall = CcRoom("hall", "Hall", CcRect(0, 8, 22, 4))
  private val patio = CcRoom("patio", "Patio", CcRect(22, 0, 6, 12), outdoor = true)
  private val rooms = listOf(kitchen, living, hall, patio)
  private val box = CcMapBox(0f, 0f, 28f, 12f)
  private val w = 1400f
  private val h = 900f
  private val fit2 = CcPlanGeo.fit(box, w, h)
  private val fit3 = CcIso.fit(box, w, h)
  private val tol = 0.01f

  @Test fun isoIsTheMocksMatrix() {
    // matrix(.866 .5 -.866 .5 0 0): x' = .866x - .866y, y' = .5x + .5y
    assertEquals(0.866f * 10 - 0.866f * 4, CcIso.isoX(10f, 4f), tol)
    assertEquals(0.5f * 10 + 0.5f * 4, CcIso.isoY(10f, 4f), tol)
  }

  @Test fun blendIsTheFlatPlanAtZeroAndExactlyTheIsoFitAtOne() {
    assertTrue(CcIso.blend(fit2, fit3, box.center, 0f).isIdentity)
    val m = CcIso.blend(fit2, fit3, box.center, 1f)
    for ((cx, cy) in listOf(0f to 0f, 28f to 12f, 10f to 8f, 3.5f to 11f)) {
      val px = fit2.x(cx)
      val py = fit2.y(cy)
      assertEquals(fit3.x(cx, cy), m.x(px, py), 0.05f)
      assertEquals(fit3.y(cx, cy), m.y(px, py), 0.05f)
    }
  }

  @Test fun blendTurnsAboutTheFloorCentreAndNeverFlipsThePlan() {
    val c = box.center
    for (step in 0..10) {
      val e = step / 10f
      val m = CcIso.blend(fit2, fit3, c, e)
      assertTrue("e=$e must keep orientation", m.a * m.d - m.b * m.c > 0f)
      // The floor's centre glides straight from its 2D spot to its 3D spot.
      val from = fit2.x(c.x) to fit2.y(c.y)
      val to = fit3.x(c.x, c.y) to fit3.y(c.x, c.y)
      assertEquals(from.first + (to.first - from.first) * e, m.x(from.first, from.second), 0.05f)
      assertEquals(from.second + (to.second - from.second) * e, m.y(from.first, from.second), 0.05f)
    }
  }

  @Test fun inverseTakesATapBackToTheFloor() {
    for (e in listOf(0.25f, 0.6f, 1f)) {
      val m = CcIso.blend(fit2, fit3, box.center, e)
      val back = m.inverse()
      assertNotNull(back)
      back!!
      val (x, y) = 333f to 444f
      assertEquals(x, back.x(m.x(x, y), m.y(x, y)), 0.05f)
      assertEquals(y, back.y(m.x(x, y), m.y(x, y)), 0.05f)
    }
    assertNull(CcAffine(1f, 2f, 2f, 4f, 0f, 0f).inverse())
  }

  @Test fun fitKeepsTheWholeHouseWallsAndSlabInsideTheCanvas() {
    for ((cw, ch) in listOf(1400f to 900f, 833f to 700f, 900f to 1300f, 600f to 360f)) {
      val f = CcIso.fit(box, cw, ch)
      val pts = CcIso.corners(box, f)
      val top = pts.minOf { it.y } - CcIso.WALL_CELLS * f.scale
      val bottom = pts.maxOf { it.y } + CcIso.SLAB_CELLS * f.scale
      assertTrue(pts.minOf { it.x } >= 0f && pts.maxOf { it.x } <= cw)
      assertTrue("top $top", top >= 0f)
      assertTrue("bottom $bottom", bottom <= ch)
      // Centred: equal room left and right.
      assertEquals(pts.minOf { it.x }, cw - pts.maxOf { it.x }, 0.5f)
    }
    assertEquals(20f, CcIso.fit(box, 5000f, 5000f, maxScale = 20f).scale, tol)
    assertEquals(CcIsoFit(1f, 0f, 0f), CcIso.fit(box, 0f, 100f))
  }

  @Test fun wallsAreSharedOnceSplitAtJunctionsAndOrderedBackToFront() {
    val faces = CcIso.walls(rooms)
    // Outdoor slabs have no walls.
    assertTrue(faces.none { it.x1 > 22f || it.x2 > 22f })
    // The kitchen/living wall at x = 10 exists once, from 0 to 8.
    assertEquals(1, faces.count { it.x1 == 10f && it.x2 == 10f && it.y1 == 0f && it.y2 == 8f })
    // The y = 8 line is split where the kitchen/living wall meets it.
    assertTrue(faces.any { it.y1 == 8f && it.y2 == 8f && it.x1 == 0f && it.x2 == 10f })
    assertTrue(faces.any { it.y1 == 8f && it.y2 == 8f && it.x1 == 10f && it.x2 == 22f })
    // Total wall length equals the unique unit edges: 22 (top) + 22 (y=8) + 22 (bottom) + 12 + 8 + 12 (verticals).
    assertEquals(98f, faces.sumOf { (it.x2 - it.x1 + it.y2 - it.y1).toDouble() }.toFloat(), tol)
    assertEquals(faces.sortedBy { it.depth }, faces)
  }

  @Test fun slabShowsOnlyTheSidesFacingTheViewer() {
    val l = listOf(CcRoom("a", "A", CcRect(0, 0, 10, 10)), CcRoom("b", "B", CcRect(10, 0, 6, 4)))
    val faces = CcIso.slab(l)
    // South edges with nothing below: y = 10 for x 0..10, y = 4 for x 10..16. East: x = 16 for y 0..4, x = 10 for y 4..10.
    assertTrue(faces.any { it.y1 == 10f && it.y2 == 10f && it.x1 == 0f && it.x2 == 10f })
    assertTrue(faces.any { it.y1 == 4f && it.y2 == 4f && it.x1 == 10f && it.x2 == 16f })
    assertTrue(faces.any { it.x1 == 16f && it.x2 == 16f && it.y1 == 0f && it.y2 == 4f })
    assertTrue(faces.any { it.x1 == 10f && it.x2 == 10f && it.y1 == 4f && it.y2 == 10f })
    // No north or west sides, and nothing between the two rooms.
    assertTrue(faces.none { it.y1 == 0f && it.y2 == 0f })
    assertTrue(faces.none { it.x1 == 0f && it.x2 == 0f })
    assertEquals(4, faces.size)
  }

  @Test fun verticalScaleTellsLabelsHowTallARoomLooks() {
    assertEquals(1f, CcAffine.IDENTITY.verticalScale, tol)
    val m = CcIso.blend(fit2, fit3, box.center, 1f)
    // A unit square stands (1 + 1) · 0.5 = 1 cell tall in iso, so 2D px grow by exactly the fits' ratio.
    assertEquals(fit3.scale / fit2.scale, m.verticalScale, 0.001f)
  }

  @Test fun theStyleIsRememberedPerPortalAndNeverRebuildsThePlanModel() {
    val iso = ControlCenterConfig.Settings(planStyle = ControlCenterConfig.PLAN_ISO)
    assertTrue(iso.plan3d)
    assertEquals(ControlCenterConfig.Settings(), iso.forPlanModel())
    val spec = com.immortal.launcher.settings.ControlCenterSettings.domain.specs
        .filterIsInstance<com.immortal.launcher.settings.EnumSpec<ControlCenterConfig.Settings>>().single { it.key == "planStyle" }
    assertEquals(listOf(ControlCenterConfig.PLAN_2D, ControlCenterConfig.PLAN_ISO), spec.options.map { it.first })
    assertNull(spec.coerce("cube"))
    assertEquals(ControlCenterConfig.PLAN_ISO, spec.coerce(ControlCenterConfig.PLAN_ISO))
    assertEquals(com.immortal.launcher.settings.SettingScope.DEVICE, spec.scope)
  }

  @Test fun theLayerPartsComposeBackToTheBlend() {
    for (e in listOf(0f, 0.3f, 0.75f, 1f)) {
      val m = CcIso.blend(fit2, fit3, box.center, e)
      val p = CcIso.parts(fit2, fit3, box.center, e)
      val r = Math.toRadians(p.degrees.toDouble())
      for ((x, y) in listOf(100f to 200f, 900f to 50f, 640f to 480f)) {
        // inner: rotate about the pivot; outer: scale about the pivot, then translate (Android RenderNode order).
        val rx = p.pivotX + ((x - p.pivotX) * Math.cos(r) - (y - p.pivotY) * Math.sin(r)).toFloat()
        val ry = p.pivotY + ((x - p.pivotX) * Math.sin(r) + (y - p.pivotY) * Math.cos(r)).toFloat()
        val sx = p.pivotX + (rx - p.pivotX) * p.scaleX + p.tx
        val sy = p.pivotY + (ry - p.pivotY) * p.scaleY + p.ty
        assertEquals(m.x(x, y), sx, 0.05f)
        assertEquals(m.y(x, y), sy, 0.05f)
      }
    }
  }
}
