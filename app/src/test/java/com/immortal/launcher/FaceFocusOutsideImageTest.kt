/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceFocusOutsideImageTest {
    private val subject = FaceFocus.Box(100, 200, 300, 400)
    private val outside = listOf(
        FaceFocus.Box(-2000, 0, -500, 1000),
        FaceFocus.Box(1500, 0, 3000, 1000),
        FaceFocus.Box(0, -2000, 1000, -500),
        FaceFocus.Box(0, 1500, 1000, 3000),
    )
    private val touching = listOf(
        FaceFocus.Box(-200, 100, 0, 500),
        FaceFocus.Box(1000, 100, 1200, 500),
        FaceFocus.Box(100, -200, 500, 0),
        FaceFocus.Box(100, 1000, 500, 1200),
    )

    @Test fun outsideBoxesDoNotSupplyAFocalPoint() {
        outside.forEach { assertNull(FaceFocus.focalPoint(listOf(it), 1000, 1000)) }
    }

    @Test fun outsideBoxesDoNotSupplyAPeopleWeight() {
        outside.forEach { assertEquals(0f, FaceFocus.peopleWeight(listOf(it), 1000, 1000), 0f) }
    }

    @Test fun outsideBoxesCannotMoveTheVisibleSubjectsFocus() {
        outside.forEach {
            assertEquals(FaceFocus.Focus(.2f, .3f),
                FaceFocus.focalPoint(listOf(subject, it), 1000, 1000))
        }
    }

    @Test fun outsideBoxesCannotInflateTheVisibleSubjectsWeight() {
        outside.forEach {
            assertEquals(.41f, FaceFocus.peopleWeight(listOf(subject, it), 1000, 1000), .0001f)
        }
    }

    @Test fun edgeContactWithoutVisibleAreaDoesNotSupplyAFocalPoint() {
        touching.forEach { assertNull(FaceFocus.focalPoint(listOf(it), 1000, 1000)) }
    }

    @Test fun edgeContactWithoutVisibleAreaDoesNotSupplyAPeopleWeight() {
        touching.forEach { assertEquals(0f, FaceFocus.peopleWeight(listOf(it), 1000, 1000), 0f) }
    }

    @Test fun partiallyVisibleFaceKeepsItsExistingClampedFocus() {
        assertEquals(FaceFocus.Focus(0f, 0f),
            FaceFocus.focalPoint(listOf(FaceFocus.Box(-200, -100, 100, 50)), 1000, 1000))
    }

    @Test fun partiallyVisibleFaceKeepsItsExistingPeopleWeight() {
        assertEquals(.43f,
            FaceFocus.peopleWeight(listOf(FaceFocus.Box(-200, -100, 100, 50)), 1000, 1000), .0001f)
    }

    @Test fun visibleFaceKeepsItsExistingFocus() {
        assertEquals(FaceFocus.Focus(.2f, .3f), FaceFocus.focalPoint(listOf(subject), 1000, 1000))
    }

    @Test fun visibleFaceKeepsItsExistingPeopleWeight() {
        assertEquals(.41f, FaceFocus.peopleWeight(listOf(subject), 1000, 1000), .0001f)
    }
}
