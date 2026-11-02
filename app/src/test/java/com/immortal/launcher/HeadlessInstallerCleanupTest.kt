/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class HeadlessInstallerCleanupTest {
  @get:Rule val files = TemporaryFolder()

  @Test fun timeoutAbandonsOnlyTheSessionCreatedByThisAttempt() =
      withInstaller(terminalStatus = null) { context, installer, session, install ->
        assertFalse(install())
        Mockito.verify(installer).abandonSession(37)
        Mockito.verify(installer, Mockito.never()).getMySessions()
        Mockito.verify(session).close()
        Mockito.verify(context).unregisterReceiver(Mockito.any(BroadcastReceiver::class.java))
        assertFalse(InstallAutoConfirm.isArmed())
      }

  @Test fun terminalFailureAbandonsTheOwnedSession() =
      withInstaller(terminalStatus = PackageInstaller.STATUS_FAILURE_INVALID) { _, installer, _, install ->
        assertFalse(install())
        Mockito.verify(installer).abandonSession(37)
      }

  @Test fun successfulInstallDoesNotAbandonItsSession() =
      withInstaller { context, installer, session, install ->
        assertTrue(install())
        Mockito.verify(installer, Mockito.never()).abandonSession(Mockito.anyInt())
        Mockito.verify(session).close()
        Mockito.verify(context).unregisterReceiver(Mockito.any(BroadcastReceiver::class.java))
        assertFalse(InstallAutoConfirm.isArmed())
      }

  @Test fun failedSessionOpenStillAbandonsTheCreatedSession() =
      withInstaller { _, installer, _, install ->
        `when`(installer.openSession(37)).thenThrow(IOException("Public fixture open failure"))
        assertFalse(install())
        Mockito.verify(installer).abandonSession(37)
      }

  @Test fun failedWriteClosesAndAbandonsTheOwnedSession() =
      withInstaller { _, installer, session, install ->
        `when`(session.openWrite(Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong()))
            .thenThrow(IOException("Public fixture write failure"))
        assertFalse(install())
        Mockito.verify(session).close()
        Mockito.verify(installer).abandonSession(37)
      }

  @Test fun failedSessionCreationDoesNotAbandonUnrelatedSessions() =
      withInstaller { _, installer, _, install ->
        `when`(installer.createSession(Mockito.any(PackageInstaller.SessionParams::class.java)))
            .thenThrow(IOException("Public fixture creation failure"))
        assertFalse(install())
        Mockito.verify(installer, Mockito.never()).abandonSession(Mockito.anyInt())
      }

  @Test fun cleanupFailureStillDisarmsAndUnregisters() =
      withInstaller(terminalStatus = null) { context, installer, _, install ->
        Mockito.doThrow(SecurityException("Public fixture cleanup failure"))
            .`when`(installer).abandonSession(37)
        assertFalse(install())
        Mockito.verify(installer).abandonSession(37)
        Mockito.verify(context).unregisterReceiver(Mockito.any(BroadcastReceiver::class.java))
        assertFalse(InstallAutoConfirm.isArmed())
      }

  @Test fun failedCommitStillClosesAbandonsAndDisarms() =
      withInstaller { context, installer, session, install ->
        Mockito.doThrow(IllegalStateException("Public fixture commit failure"))
            .`when`(session).commit(Mockito.any(IntentSender::class.java))
        assertFalse(install())
        Mockito.verify(session).close()
        Mockito.verify(installer).abandonSession(37)
        Mockito.verify(context).unregisterReceiver(Mockito.any(BroadcastReceiver::class.java))
        assertFalse(InstallAutoConfirm.isArmed())
      }

  @Test fun foreignFailureCannotCompleteOrCancelAnAwaitingInstall() {
    val committed = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val callback = AtomicReference<Pair<Context, BroadcastReceiver>>()
    val failure = AtomicReference<Throwable>()
    val worker = Thread {
      try {
        withInstaller(terminalStatus = null, timeoutMs = 5_000,
            onCommit = { context, receiver ->
              callback.set(context to receiver)
              committed.countDown()
            }) { _, installer, _, install ->
          assertTrue(install())
          Mockito.verify(installer, Mockito.never()).abandonSession(Mockito.anyInt())
          assertFalse(InstallAutoConfirm.isArmed())
        }
      } catch (t: Throwable) {
        failure.set(t)
      } finally {
        finished.countDown()
      }
    }.apply { name = "headless-installer-check" }
    fun deliver(sessionId: Int, status: Int) {
      callback.get()?.let { (context, receiver) ->
        val result = Mockito.mock(Intent::class.java)
        `when`(result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)).thenReturn(sessionId)
        `when`(result.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)).thenReturn(status)
        receiver.onReceive(context, result)
      }
    }
    worker.start()
    try {
      assertTrue(committed.await(3, TimeUnit.SECONDS))
      deliver(99, PackageInstaller.STATUS_FAILURE_INVALID)
      assertFalse(finished.await(150, TimeUnit.MILLISECONDS))
      deliver(99, PackageInstaller.STATUS_SUCCESS)
      assertFalse(finished.await(150, TimeUnit.MILLISECONDS))
      deliver(-1, PackageInstaller.STATUS_FAILURE_INVALID)
      assertFalse(finished.await(150, TimeUnit.MILLISECONDS))
      deliver(37, PackageInstaller.STATUS_SUCCESS)
      assertTrue(finished.await(3, TimeUnit.SECONDS))
      failure.get()?.let { throw AssertionError("Installer worker failed", it) }
    } finally {
      deliver(37, PackageInstaller.STATUS_SUCCESS)
      worker.join(6_000)
      assertFalse(worker.isAlive)
    }
  }

  @Suppress("DEPRECATION")
  private fun withInstaller(
      terminalStatus: Int? = PackageInstaller.STATUS_SUCCESS,
      timeoutMs: Long = 1,
      onCommit: ((Context, BroadcastReceiver) -> Unit)? = null,
      action: (Context, PackageInstaller, PackageInstaller.Session, () -> Boolean) -> Unit,
  ) {
    val context = Mockito.mock(Context::class.java)
    val packages = Mockito.mock(PackageManager::class.java)
    val installer = Mockito.mock(PackageInstaller::class.java)
    val session = Mockito.mock(PackageInstaller.Session::class.java)
    val sender = Mockito.mock(IntentSender::class.java)
    val pending = Mockito.mock(PendingIntent::class.java)
    val apk = files.newFile("public-fixture.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    `when`(context.applicationContext).thenReturn(context)
    `when`(context.packageName).thenReturn("com.immortal.launcher.debug")
    `when`(context.packageManager).thenReturn(packages)
    `when`(packages.packageInstaller).thenReturn(installer)
    `when`(installer.createSession(Mockito.any(PackageInstaller.SessionParams::class.java)))
        .thenReturn(37)
    `when`(installer.openSession(37)).thenReturn(session)
    `when`(session.openWrite("base.apk", 0L, apk.length())).thenReturn(ByteArrayOutputStream())
    `when`(pending.intentSender).thenReturn(sender)
    var receiver: BroadcastReceiver? = null
    Mockito.doAnswer {
      receiver = it.getArgument(0)
      null
    }.`when`(context).registerReceiver(
        Mockito.any(BroadcastReceiver::class.java), Mockito.any(IntentFilter::class.java))
    val result = Mockito.mock(Intent::class.java)
    if (terminalStatus != null) {
      `when`(result.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE))
          .thenReturn(terminalStatus)
      `when`(result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)).thenReturn(37)
    }
    Mockito.doAnswer {
      if (onCommit != null) onCommit(context, requireNotNull(receiver))
      else if (terminalStatus != null) requireNotNull(receiver).onReceive(context, result)
      null
    }.`when`(session).commit(sender)
    Mockito.mockStatic(Log::class.java).use {
      Mockito.mockStatic(SystemClock::class.java).use {
        Mockito.mockConstruction(IntentFilter::class.java).use {
          Mockito.mockConstruction(PackageInstaller.SessionParams::class.java).use {
            Mockito.mockConstruction(Intent::class.java,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
              Mockito.mockStatic(PendingIntent::class.java).use { intents ->
                intents.`when`<PendingIntent> {
                  PendingIntent.getBroadcast(Mockito.eq(context), Mockito.eq(37),
                      Mockito.any(Intent::class.java), Mockito.anyInt())
                }.thenReturn(pending)
                action(context, installer, session) {
                  HeadlessInstaller.install(context, apk, "public.fixture.app", timeoutMs = timeoutMs)
                }
              }
            }
          }
        }
      }
    }
  }
}
