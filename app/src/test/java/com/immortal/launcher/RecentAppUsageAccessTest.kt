/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Real gate and launcher; mocks stop at Android app-op, permission, and activity boundaries. */
class RecentAppUsageAccessTest {
    private val packageName = "com.immortal.launcher.debug"
    private val uid = 12345

    @Test fun allowedAppOpChecksTheActualPackageAndUidWithoutPermissionFallback() {
        val fixture = Fixture(AppOpsManager.MODE_ALLOWED, PackageManager.PERMISSION_DENIED)
        assertTrue(RecentAppUsageAccess.allowed(fixture.context))
        Mockito.verify(fixture.ops).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, packageName)
        Mockito.verify(fixture.context, Mockito.never()).checkSelfPermission(Mockito.anyString())
    }

    @Test fun defaultAppOpAcceptsAnExplicitUsagePermissionGrant() {
        val fixture = Fixture(AppOpsManager.MODE_DEFAULT, PackageManager.PERMISSION_GRANTED)
        assertTrue(RecentAppUsageAccess.allowed(fixture.context))
        Mockito.verify(fixture.context).checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    }

    @Test fun defaultAppOpRejectsADeniedUsagePermission() {
        val fixture = Fixture(AppOpsManager.MODE_DEFAULT, PackageManager.PERMISSION_DENIED)
        assertFalse(RecentAppUsageAccess.allowed(fixture.context))
    }

    @Test fun ignoredErroredAndUnknownModesRejectEvenAnExplicitPermissionGrant() {
        for (mode in listOf(AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED, 999)) {
            val fixture = Fixture(mode, PackageManager.PERMISSION_GRANTED)
            assertFalse("App-op mode $mode must deny usage reads", RecentAppUsageAccess.allowed(fixture.context))
            Mockito.verify(fixture.context, Mockito.never()).checkSelfPermission(Mockito.anyString())
        }
    }

    @Test fun missingAppOpsManagerFailsClosed() {
        val fixture = Fixture(AppOpsManager.MODE_ALLOWED, PackageManager.PERMISSION_GRANTED)
        Mockito.`when`(fixture.context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(null)
        assertFalse(RecentAppUsageAccess.allowed(fixture.context))
        Mockito.verifyNoInteractions(fixture.ops)
    }

    @Test fun appOpReadFailureFailsClosedWithoutFallingBackToPermission() {
        val fixture = Fixture(AppOpsManager.MODE_ALLOWED, PackageManager.PERMISSION_GRANTED)
        Mockito.`when`(fixture.ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, packageName))
            .thenThrow(SecurityException("generated-usage-app-op-unavailable"))
        assertFalse(RecentAppUsageAccess.allowed(fixture.context))
        Mockito.verify(fixture.context, Mockito.never()).checkSelfPermission(Mockito.anyString())
    }

    @Test fun defaultPermissionReadFailureFailsClosed() {
        val fixture = Fixture(AppOpsManager.MODE_DEFAULT, PackageManager.PERMISSION_GRANTED)
        Mockito.`when`(fixture.context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS))
            .thenThrow(IllegalStateException("generated-usage-permission-unavailable"))
        assertFalse(RecentAppUsageAccess.allowed(fixture.context))
    }

    @Test fun preferredLaunchTargetsThisPackageAndNeedsNoFallback() = withLauncher { context, uri, attempts ->
        assertTrue(RecentAppUsageAccess.open(context))
        assertEquals(1, attempts.size)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, attempts.single().action)
        assertSame(uri, attempts.single().data)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, attempts.single().flags)
    }

    @Test fun failedPreferredLaunchFallsBackOnceToGenericUsageSettings() = withLauncher(
        { attempt -> if (attempt == 1) ActivityNotFoundException("generated-preferred-settings-unavailable") else null },
    ) { context, uri, attempts ->
        assertTrue(RecentAppUsageAccess.open(context))
        assertEquals(2, attempts.size)
        assertSame(uri, attempts[0].data)
        assertNull(attempts[1].data)
        attempts.forEach {
            assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, it.action)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, it.flags)
        }
    }

    @Test fun bothLaunchFailuresReturnFalseWithoutAThirdAttempt() = withLauncher(
        { SecurityException("generated-settings-launch-denied") },
    ) { context, uri, attempts ->
        assertFalse(RecentAppUsageAccess.open(context))
        assertEquals(2, attempts.size)
        assertSame(uri, attempts[0].data)
        assertNull(attempts[1].data)
        attempts.forEach {
            assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, it.action)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, it.flags)
        }
    }

    private inner class Fixture(mode: Int, permission: Int) {
        val context = Mockito.mock(Context::class.java)
        val ops = Mockito.mock(AppOpsManager::class.java)
        init {
            val application = Mockito.mock(ApplicationInfo::class.java).apply { this.uid = this@RecentAppUsageAccessTest.uid }
            Mockito.`when`(context.packageName).thenReturn(packageName)
            Mockito.`when`(context.applicationInfo).thenReturn(application)
            Mockito.`when`(context.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(ops)
            Mockito.`when`(ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, packageName)).thenReturn(mode)
            Mockito.`when`(context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS)).thenReturn(permission)
        }
    }

    private data class Launch(val action: String?, val data: Uri?, var flags: Int = 0)

    private fun withLauncher(
        failure: (Int) -> Exception? = { null },
        check: (Context, Uri, List<Launch>) -> Unit,
    ) {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.packageName).thenReturn(packageName)
        val uri = Mockito.mock(Uri::class.java)
        val intents = linkedMapOf<Intent, Launch>()
        val attempts = mutableListOf<Launch>()
        Mockito.mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse("package:$packageName") }.thenReturn(uri)
            Mockito.mockConstruction(Intent::class.java) { intent, construction ->
                val args = construction.arguments()
                val launch = Launch(args.firstOrNull() as? String, args.getOrNull(1) as? Uri)
                intents[intent] = launch
                Mockito.doAnswer { call ->
                    launch.flags = launch.flags or call.getArgument<Int>(0)
                    intent
                }.`when`(intent).addFlags(Mockito.anyInt())
            }.use {
                Mockito.doAnswer { call ->
                    attempts.add(checkNotNull(intents[call.getArgument<Intent>(0)]))
                    failure(attempts.size)?.let { throw it }
                    null
                }.`when`(context).startActivity(Mockito.any(Intent::class.java))
                check(context, uri, attempts)
                uris.verify { Uri.parse("package:$packageName") }
            }
        }
    }
}
