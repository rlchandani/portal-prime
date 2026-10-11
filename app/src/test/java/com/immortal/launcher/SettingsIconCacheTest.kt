/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SettingsIconCacheTest {
    private data class Config(var density: Int, var locale: String)
    private fun cache(ids: Set<Int> = setOf(1, 2)) =
        SettingsIconCache<Config, Any>(ids) { it.copy() }

    @Test fun repeatedOpeningReusesImmutableDataWithoutReloading() {
        val cache = cache()
        var loads = 0
        val first = cache.get(Config(160, "en"), 1) { loads++; Any() }
        val second = cache.get(Config(160, "en"), 1) { loads++; Any() }
        assertSame(first, second)
        assertEquals(1, loads)
    }

    @Test fun separateIconsAreLoadedOnceWithinAnEpoch() {
        val cache = cache()
        val loads = mutableListOf<Int>()
        val first = cache.get(Config(160, "en"), 1) { loads += it; Any() }
        val other = cache.get(Config(160, "en"), 2) { loads += it; Any() }
        assertNotSame(first, other)
        assertSame(first, cache.get(Config(160, "en"), 1) { loads += it; Any() })
        assertSame(other, cache.get(Config(160, "en"), 2) { loads += it; Any() })
        assertEquals(listOf(1, 2), loads)
    }

    @Test fun densityChangeInvalidatesEveryStoredIcon() {
        val cache = cache()
        val oldOne = cache.get(Config(160, "en"), 1) { Any() }
        val oldTwo = cache.get(Config(160, "en"), 2) { Any() }
        val newOne = cache.get(Config(240, "en"), 1) { Any() }
        val newTwo = cache.get(Config(240, "en"), 2) { Any() }
        assertNotSame(oldOne, newOne)
        assertNotSame(oldTwo, newTwo)
        assertSame(newOne, cache.get(Config(240, "en"), 1) { Any() })
    }

    @Test fun mutationOfCallerConfigurationDoesNotMutateStoredEpoch() {
        val cache = cache()
        val configuration = Config(160, "en")
        val old = cache.get(configuration, 1) { Any() }
        configuration.locale = "fr"
        val current = cache.get(configuration, 1) { Any() }
        assertNotSame(old, current)
        assertSame(current, cache.get(Config(160, "fr"), 1) { Any() })
    }

    @Test fun returningToPreviousConfigurationLoadsANewEpoch() {
        val cache = cache()
        val old = cache.get(Config(160, "en"), 1) { Any() }
        cache.get(Config(240, "en"), 1) { Any() }
        val returned = cache.get(Config(160, "en"), 1) { Any() }
        assertNotSame(old, returned)
        assertSame(returned, cache.get(Config(160, "en"), 1) { Any() })
    }

    @Test fun failedLoadPropagatesAndDoesNotPoisonTheNextRequest() {
        val cache = cache()
        val error = IllegalStateException("load failure")
        var loads = 0
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            cache.get(Config(160, "en"), 1) { loads++; throw error }
        })
        val recovered = cache.get(Config(160, "en"), 1) { loads++; Any() }
        assertSame(recovered, cache.get(Config(160, "en"), 1) { loads++; Any() })
        assertEquals(2, loads)
    }

    @Test fun unsupportedIconIsRejectedBeforeLoaderRuns() {
        val cache = cache()
        var loads = 0
        assertThrows(IllegalArgumentException::class.java) {
            cache.get(Config(160, "en"), 3) { loads++; Any() }
        }
        assertEquals(0, loads)
    }

    @Test fun callerCannotExpandOrRemoveTheCacheAllowlist() {
        val allowed = mutableSetOf(1, 2)
        val cache = cache(allowed)
        allowed.remove(1)
        allowed.add(3)
        val first = cache.get(Config(160, "en"), 1) { Any() }
        assertSame(first, cache.get(Config(160, "en"), 1) { Any() })
        assertThrows(IllegalArgumentException::class.java) {
            cache.get(Config(160, "en"), 3) { Any() }
        }
    }
}
