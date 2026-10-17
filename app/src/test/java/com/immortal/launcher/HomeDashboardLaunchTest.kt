/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Browser
import android.widget.Toast
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class HomeDashboardLaunchTest {
    private val address = "https://dashboard.invalid/view/?mode=touch#room"
    private val schemeAddresses = listOf("http", "https", "HTTP", "HTTPS", "hTtP", "hTtPs")
        .map { "$it://dashboard.invalid/view/?mode=touch#room" }
    private data class Attempt(val intent: Intent, val packageName: String?)

    private fun withGateway(failure: (Int) -> Exception? = { null },
        address: String = this.address,
        check: (Boolean, List<Attempt>, PackageManager, List<Intent>, Int) -> Unit) {
        val prefs = HomeDashboardTestPrefs(address)
        val context = prefs.context
        val manager = Mockito.mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(manager)
        `when`(context.packageName).thenReturn("com.immortal.launcher")
        `when`(manager.getLaunchIntentForPackage("org.chromium.chrome"))
            .thenReturn(Mockito.mock(Intent::class.java))
        var packageName: String? = null
        val attempts = mutableListOf<Attempt>()
        var notifications = 0
        val toast = Mockito.mock(Toast::class.java)
        Mockito.doAnswer { notifications++; null }.`when`(toast).show()
        Mockito.mockStatic(Toast::class.java).use { toasts ->
            toasts.`when`<Toast> { Toast.makeText(context, "Your dashboard couldn’t be opened.", Toast.LENGTH_LONG) }
                .thenReturn(toast)
            Mockito.mockStatic(Uri::class.java).use { uris ->
                // Distinct sentinels test our handoff, not Android's actual URI parser.
                val rawUri = Mockito.mock(Uri::class.java, "rawUri")
                val normalizedUri = Mockito.mock(Uri::class.java, "normalizedUri")
                `when`(rawUri.normalizeScheme()).thenReturn(normalizedUri)
                uris.`when`<Uri> { Uri.parse(address) }.thenReturn(rawUri)
                val constructorArguments = mutableListOf<List<Any?>>()
                Mockito.mockConstruction(Intent::class.java,
                    Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { intent, construction ->
                    constructorArguments.add(construction.arguments().toList())
                    Mockito.doAnswer { call -> packageName = call.getArgument(0); intent }
                        .`when`(intent).setPackage(Mockito.nullable(String::class.java))
                }.use { intents ->
                    Mockito.doAnswer { call ->
                        attempts.add(Attempt(call.getArgument(0), packageName))
                        failure(attempts.size)?.let { throw it }
                        null
                    }.`when`(context).startActivity(Mockito.any(Intent::class.java))
                    val opened = HomeDashboardActivity.openDashboard(context)
                    assertEquals(0, prefs.writeCount)
                    uris.verify { Uri.parse(address) }
                    assertEquals(1, constructorArguments.size)
                    assertEquals(Intent.ACTION_VIEW, constructorArguments.single()[0])
                    assertSame("Browser intent must receive the normalized URI", normalizedUri,
                        constructorArguments.single()[1])
                    Mockito.verify(rawUri).normalizeScheme()
                    Mockito.verify(intents.constructed().single()).addCategory(Intent.CATEGORY_BROWSABLE)
                    Mockito.verify(intents.constructed().single()).putExtra(
                        Browser.EXTRA_APPLICATION_ID, "com.immortal.launcher")
                    Mockito.verify(intents.constructed().single()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    check(opened, attempts, manager, intents.constructed(), notifications)
                }
            }
        }
    }

    @Test fun preferredBrowserLaunchSkipsPackageQueriesAndDispatchesOnce() {
        schemeAddresses.forEach { address ->
            withGateway(address = address) { opened, attempts, manager, intents, notifications ->
                assertTrue(opened); assertEquals(0, notifications)
                assertEquals(listOf("org.chromium.chrome"), attempts.map { it.packageName })
                assertEquals(1, intents.size)
                Mockito.verifyNoInteractions(manager)
            }
        }
    }

    @Test fun missingPreferredHandlerFallsBackOnceWithTheSameUrlIntent() {
        schemeAddresses.forEach { address ->
            withGateway({ if (it == 1) ActivityNotFoundException("synthetic") else null }, address) {
                opened, attempts, manager, intents, notifications ->
                assertTrue(opened); assertEquals(0, notifications)
                assertEquals(listOf("org.chromium.chrome", null), attempts.map { it.packageName })
                assertEquals(1, intents.size)
                assertSame(attempts[0].intent, attempts[1].intent)
                Mockito.verifyNoInteractions(manager)
            }
        }
    }

    @Test fun securityFailureDoesNotTryAnotherBrowser() {
        withGateway({ SecurityException("synthetic") }) { opened, attempts, _, _, notifications ->
            assertFalse(opened); assertEquals(1, attempts.size); assertEquals(1, notifications)
        }
    }

    @Test fun missingFallbackReportsFailureWithoutAThirdAttempt() {
        withGateway({ ActivityNotFoundException("synthetic") }) { opened, attempts, _, _, notifications ->
            assertFalse(opened); assertEquals(2, attempts.size); assertEquals(1, notifications)
            assertEquals(listOf("org.chromium.chrome", null), attempts.map { it.packageName })
        }
    }

    @Test fun fallbackFailureDoesNotHideOrRepeatTheError() {
        withGateway({ if (it == 1) ActivityNotFoundException("synthetic") else IllegalStateException("synthetic") }) {
            opened, attempts, _, _, notifications ->
            assertFalse(opened); assertEquals(2, attempts.size); assertEquals(1, notifications)
        }
    }
}
