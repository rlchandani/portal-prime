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

class GreetingPreviewSpecTest {
    private val settings = WelcomeConfig.Settings(userName = "Sam")

    @Test fun greetingFollowsTheOverlaysHours() {
        assertEquals("Good night", GreetingPreview.greetingFor(4, settings))
        assertEquals("Good morning", GreetingPreview.greetingFor(5, settings))
        assertEquals("Good morning", GreetingPreview.greetingFor(11, settings))
        assertEquals("Good afternoon", GreetingPreview.greetingFor(12, settings))
        assertEquals("Good evening", GreetingPreview.greetingFor(17, settings))
        assertEquals("Good night", GreetingPreview.greetingFor(22, settings))
    }

    @Test fun nameIsAddedOnlyWhenSet() {
        assertEquals("Good morning, Sam", GreetingPreview.fullGreeting(9, settings))
        assertEquals("Good morning", GreetingPreview.fullGreeting(9, settings.copy(userName = " ")))
    }

    @Test fun captionShowsDurationAndSpeech() {
        assertEquals("Shown for 3.2 s", GreetingPreview.caption(settings))
        assertEquals("Shown for 10.0 s · spoken", GreetingPreview.caption(settings.copy(durationMs = 60_000, enableTts = true)))
    }

    @Test fun specCarriesSwitchesScaleAndOpacity() {
        val spec = GreetingPreview.spec(9, settings.copy(showGreeting = false, textScale = WelcomeConfig.SCALE_LARGE,
            backgroundOpacity = 2f))
        assertNull(spec.greeting)
        assertEquals(1.25f, spec.scale, 0f)
        assertEquals(1f, spec.backgroundOpacity, 0f)
        assertEquals(.8f, GreetingPreview.scale(WelcomeConfig.SCALE_SMALL), 0f)
        assertEquals(1f, GreetingPreview.scale("unknown"), 0f)
    }
}
