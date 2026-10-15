/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import android.util.AtomicFile
import android.widget.Toast
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Real admission/persistence paths; Android launch and storage boundaries are doubled. */
class PortalHomeBridgePersistenceTest {
  @Test fun unsuccessfulCommitNeverDispatchesMetaNavigation() = fixture({ false }) { f ->
    assertFalse(PortalHomeBridge.launch(f.context))
    f.assertNoNavigation()
    f.assertCleared()
    Mockito.verify(f.toast).show()
  }

  @Test fun throwingCommitNeverDispatchesMetaNavigation() = fixture({ error("disk fixture") }) { f ->
    assertFalse(PortalHomeBridge.launch(f.context))
    f.assertNoNavigation()
    f.assertCleared()
    Mockito.verify(f.toast).show()
  }

  @Test fun synchronousMarkReportsFailedDurabilityAndClearsOnlyItsRequest() = fixture({ false }) { f ->
    assertEquals(false, DreamPolicy.markBridge(f.context, portalHome = true))
    f.assertCleared()
  }

  @Test fun synchronousMarkReportsSuccessAndKeepsCallsSuppression() = fixture({ true }) { f ->
    assertEquals(true, DreamPolicy.markBridge(f.context, portalHome = true))
    assertTrue(DreamPolicy.inStockHandoff)
    assertTrue(DreamPolicy.portalHomeHandoff)
    assertTrue(DreamPolicy.bridgeAt > 0L)
  }

  @Test fun successfulCommitStillDispatchesTheExistingHomeAndDreamDismissal() = fixture({ true }) { f ->
    assertTrue(PortalHomeBridge.launch(f.context))
    Mockito.verify(f.context).sendBroadcast(Mockito.any(Intent::class.java))
    Mockito.verify(f.context).startActivity(Mockito.any(Intent::class.java))
    Mockito.verify(f.toast, Mockito.never()).show()
    assertTrue(DreamPolicy.inStockHandoff)
    assertTrue(DreamPolicy.portalHomeHandoff)
    Mockito.verify(f.editor).putBoolean("in_stock_handoff", true)
    Mockito.verify(f.editor).putBoolean("portal_home_handoff", true)
  }

  @Test fun failedAttemptCanBeRetriedWithoutRetainingItsSuppression() {
    var succeeds = false
    fixture({ succeeds }) { f ->
      assertFalse(PortalHomeBridge.launch(f.context))
      f.assertCleared()
      assertTrue(DreamBridgePersistence.commit { true })
      succeeds = true
      assertTrue(PortalHomeBridge.launch(f.context))
      assertTrue(DreamPolicy.inStockHandoff)
      assertTrue(DreamPolicy.portalHomeHandoff)
      Mockito.verify(f.context).startActivity(Mockito.any(Intent::class.java))
    }
  }

  @Test fun failedOlderMarkCannotClearANewerBridge() {
    val commits = AtomicInteger()
    var newer: Thread? = null
    var newerResult: Any? = null
    lateinit var context: Context
    fixture({
      if (commits.incrementAndGet() == 1) {
        newer = Thread { newerResult = DreamPolicy.markBridge(context, portalHome = false) }
        newer!!.start()
        val cutoff = System.nanoTime() + 2_000_000_000L
        while (DreamPolicy.portalHomeHandoff && System.nanoTime() < cutoff) Thread.sleep(2)
        check(!DreamPolicy.portalHomeHandoff) { "newer request did not begin" }
        false
      } else true
    }) { f ->
      context = f.context
      try {
        val olderResult: Any = DreamPolicy.markBridge(context, portalHome = true)
        newer?.join(2000)
        assertFalse(newer!!.isAlive)
        assertEquals(false, olderResult)
        assertEquals(true, newerResult)
        assertTrue(DreamPolicy.inStockHandoff)
        assertFalse(DreamPolicy.portalHomeHandoff)
        assertTrue(DreamPolicy.bridgeAt > 0L)
      } finally { newer?.join(2000) }
    }
  }

  private class Fixture(commit: () -> Boolean) {
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    private val memory = mutableMapOf<String, Any>()
    private val editors = CopyOnWriteArrayList<SharedPreferences.Editor>()
    val editor: SharedPreferences.Editor get() = editors.first()
    val resolver = Mockito.mock(ContentResolver::class.java)
    val packages = Mockito.mock(PackageManager::class.java)
    val toast = Mockito.mock(Toast::class.java)
    init {
      Mockito.`when`(context.applicationContext).thenReturn(context)
      Mockito.`when`(context.getSharedPreferences("dream_bridge", Context.MODE_PRIVATE)).thenReturn(prefs)
      Mockito.`when`(prefs.getBoolean(Mockito.anyString(), Mockito.anyBoolean())).thenAnswer {
        synchronized(memory) { memory[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1) }
      }
      Mockito.`when`(prefs.edit()).thenAnswer {
        val writer = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        val pending = mutableMapOf<String, Any?>()
        Mockito.`when`(writer.putLong(Mockito.anyString(), Mockito.anyLong())).thenAnswer {
          pending[it.getArgument<String>(0)] = it.getArgument<Long>(1); writer
        }
        Mockito.`when`(writer.putBoolean(Mockito.anyString(), Mockito.anyBoolean())).thenAnswer {
          pending[it.getArgument<String>(0)] = it.getArgument<Boolean>(1); writer
        }
        Mockito.`when`(writer.remove(Mockito.anyString())).thenAnswer {
          pending[it.getArgument<String>(0)] = null; writer
        }
        Mockito.`when`(writer.commit()).thenAnswer {
          // Android changes preference memory even if persistent storage reports failure.
          synchronized(memory) {
            pending.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
          }
          commit()
        }
        editors.add(writer); writer
      }
      Mockito.`when`(context.filesDir).thenReturn(File(System.getProperty("java.io.tmpdir"), "immortal-bridge-unit"))
      Mockito.`when`(context.contentResolver).thenReturn(resolver)
      Mockito.`when`(context.packageManager).thenReturn(packages)
      Mockito.`when`(packages.resolveActivity(Mockito.any(Intent::class.java), Mockito.eq(0)))
          .thenReturn(Mockito.mock(ResolveInfo::class.java))
    }
    fun assertNoNavigation() {
      Mockito.verify(context, Mockito.never()).sendBroadcast(Mockito.any(Intent::class.java))
      Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
      Mockito.verify(packages, Mockito.never()).resolveActivity(Mockito.any(Intent::class.java), Mockito.anyInt())
    }
    fun assertCleared() {
      assertFalse(DreamPolicy.inStockHandoff)
      assertFalse(DreamPolicy.portalHomeHandoff)
      assertEquals(0L, DreamPolicy.bridgeAt)
      assertTrue(DreamBridgePersistence.commit { true })
      assertFalse(prefs.getBoolean("in_stock_handoff", false))
      assertFalse(prefs.getBoolean("portal_home_handoff", false))
    }
  }

  private fun fixture(commit: () -> Boolean, operation: (Fixture) -> Unit) {
    assertTrue(DreamBridgePersistence.commit { true })
    val f = Fixture(commit)
    val previous = Triple(DreamPolicy.bridgeAt, DreamPolicy.inStockHandoff, DreamPolicy.portalHomeHandoff)
    DreamPolicy.bridgeAt = 0L; DreamPolicy.inStockHandoff = false; DreamPolicy.portalHomeHandoff = false
    try {
      Mockito.mockStatic(Settings.Secure::class.java).use { secure ->
        secure.`when`<String> { Settings.Secure.getString(f.resolver, "screensaver_activate_on_sleep") }.thenReturn("0")
        Mockito.mockStatic(Uri::class.java).use { uri ->
          uri.`when`<Uri> { Uri.parse("portal://launcher/home") }.thenReturn(Mockito.mock(Uri::class.java))
          Mockito.mockStatic(Toast::class.java).use { toasts ->
            toasts.`when`<Toast> { Toast.makeText(f.context, "Calls couldn’t be opened. Please try again.", Toast.LENGTH_LONG) }.thenReturn(f.toast)
            Mockito.mockConstruction(AtomicFile::class.java) { file, _ ->
              Mockito.`when`(file.readFully()).thenReturn("-".toByteArray())
            }.use {
              Mockito.mockConstruction(Intent::class.java, Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
                operation(f)
                assertTrue(DreamBridgePersistence.commit { true })
              }
            }
          }
        }
      }
    } finally {
      assertTrue(DreamBridgePersistence.commit { true })
      DreamPolicy.bridgeAt = previous.first
      DreamPolicy.inStockHandoff = previous.second
      DreamPolicy.portalHomeHandoff = previous.third
    }
  }
}
