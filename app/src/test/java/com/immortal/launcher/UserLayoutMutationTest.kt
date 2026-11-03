/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.`when`

/** Generated storage only: the actual native editor boundary and remote layout store. */
class UserLayoutMutationTest {
  private class Fixture {
    val prefs = ScreensaverTestPrefs()
    val widgets = ScreensaverTestPrefs()
    val context = prefs.context
    init {
      `when`(context.getSharedPreferences("immortal_layout", Context.MODE_PRIVATE)).thenReturn(prefs)
      `when`(context.getSharedPreferences("immortal_home_widgets", Context.MODE_PRIVATE)).thenReturn(widgets)
    }
    fun restore(assignments: Map<String, String>, order: List<String> = emptyList()) {
      val layout = RemoteLayoutStore.read(context)
          .put("assignments", JSONObject(assignments))
          .put("homeOrder", JSONArray(order))
      assertTrue(RemoteLayoutPolicy.validate(layout, emptyList()).valid)
      RemoteLayoutStore.apply(context, layout)
    }
  }

  @Test fun remoteAdditionRejectsStaleFolderMoveWithoutWriting() {
    val f = Fixture()
    f.restore(mapOf("com.alpha" to "Alpha"))
    val retained = UserLayout.load(f.context)
    f.restore(mapOf("com.alpha" to "Alpha", "com.remote" to "Remote"))
    val writes = f.prefs.writeCount
    assertFalse(UserLayout.saveAssignmentsFromSnapshot(f.context, retained,
        retained + ("com.native" to "Native")))
    assertEquals(writes, f.prefs.writeCount)
    assertEquals(mapOf("com.alpha" to "Alpha", "com.remote" to "Remote"), UserLayout.load(f.context))
  }

  @Test fun remoteRemovalCannotBeResurrectedByFolderEditor() {
    val f = Fixture()
    f.restore(mapOf("com.offline" to "Old", "com.native" to "Alpha"))
    val retained = UserLayout.load(f.context)
    f.restore(mapOf("com.native" to "Alpha"))
    assertFalse(UserLayout.saveAssignmentsFromSnapshot(f.context, retained,
        retained + ("com.native" to "")))
    assertEquals(mapOf("com.native" to "Alpha"), UserLayout.load(f.context))
  }

  @Test fun remoteRenameRejectsStaleRenameOfSameFolder() {
    val f = Fixture()
    f.restore(mapOf("com.native" to "Alpha", "com.offline" to "Alpha"))
    val retained = UserLayout.load(f.context)
    f.restore(mapOf("com.native" to "Remote", "com.offline" to "Remote"))
    val changed = UserLayout.renamedFolderAssignments(retained, "Alpha", "Local",
        listOf("com.native"), listOf("Alpha"))
    assertFalse(UserLayout.saveAssignmentsFromSnapshot(f.context, retained, changed))
    assertEquals(mapOf("com.native" to "Remote", "com.offline" to "Remote"), UserLayout.load(f.context))
  }

  @Test fun remoteOrderRejectsStaleFavoriteEditWithoutWriting() {
    val f = Fixture()
    f.restore(emptyMap(), listOf("com.alpha/.Main", "com.beta/.Main"))
    val retained = UserLayout.loadOrder(f.context)
    f.restore(emptyMap(), listOf("com.remote/.Main", "com.beta/.Main", "com.alpha/.Main"))
    val writes = f.prefs.writeCount
    assertFalse(UserLayout.saveOrderFromSnapshot(f.context, retained,
        listOf("com.beta/.Main", "com.alpha/.Main")))
    assertEquals(writes, f.prefs.writeCount)
    assertEquals(listOf("com.remote/.Main", "com.beta/.Main", "com.alpha/.Main"), UserLayout.loadOrder(f.context))
  }

  @Test fun remoteEmptyOrderRejectsRetainedFavoriteDialog() {
    val f = Fixture()
    f.restore(emptyMap(), listOf("com.alpha/.Main"))
    val retained = UserLayout.loadOrder(f.context)
    f.restore(emptyMap())
    assertFalse(UserLayout.saveOrderFromSnapshot(f.context, retained, listOf("com.beta/.Main")))
    assertEquals(emptyList<String>(), UserLayout.loadOrder(f.context))
  }

  @Test fun refreshedFolderRetryKeepsRemoteAndOfflineAssignments() {
    val f = Fixture()
    f.restore(mapOf("com.remote" to "Remote", "com.offline" to "Offline"))
    val current = UserLayout.load(f.context)
    assertTrue(UserLayout.saveAssignmentsFromSnapshot(f.context, current,
        current + ("com.native" to "")))
    assertEquals(mapOf("com.remote" to "Remote", "com.offline" to "Offline", "com.native" to ""),
        UserLayout.load(f.context))
  }

  @Test fun currentRenameRetainsExistingMembershipRules() {
    val f = Fixture()
    f.restore(mapOf("com.native" to "Alpha", "com.offline" to "Alpha", "com.other" to "Beta"))
    val current = UserLayout.load(f.context)
    val changed = UserLayout.renamedFolderAssignments(current, "Alpha", "Local",
        listOf("com.native", "com.curated"), listOf("Alpha", "Beta"))
    assertTrue(UserLayout.saveAssignmentsFromSnapshot(f.context, current, changed))
    assertEquals(mapOf("com.native" to "Local", "com.offline" to "Local",
        "com.other" to "Beta", "com.curated" to "Local"), UserLayout.load(f.context))
  }

  @Test fun currentOrderPreservesComponentAndShortcutIdentity() {
    val f = Fixture()
    f.restore(emptyMap(), listOf("com.alpha/.Main"))
    val current = UserLayout.loadOrder(f.context)
    val shortcut = "com.immortal.launcher/com.immortal.launcher.ToolsActivity"
    assertTrue(UserLayout.saveOrderFromSnapshot(f.context, current,
        listOf(shortcut, "com.alpha/.Other", "com.alpha/.Main", shortcut)))
    assertEquals(listOf(shortcut, "com.alpha/.Other", "com.alpha/.Main"), UserLayout.loadOrder(f.context))
  }

  @Test fun assignmentsChangedAloneDoNotRejectCurrentOrder() {
    val f = Fixture()
    f.restore(emptyMap(), listOf("com.alpha/.Main"))
    val retained = UserLayout.loadOrder(f.context)
    f.restore(mapOf("com.remote" to "Remote"), retained)
    assertTrue(UserLayout.saveOrderFromSnapshot(f.context, retained, listOf("com.beta/.Main")))
    assertEquals(mapOf("com.remote" to "Remote"), UserLayout.load(f.context))
  }

  @Test fun genericRemoteReplacementCanRemoveSavedEntries() {
    val f = Fixture()
    f.restore(mapOf("com.old" to "Old"), listOf("com.old/.Main"))
    f.restore(emptyMap())
    assertEquals(emptyMap<String, String>(), UserLayout.load(f.context))
    assertEquals(emptyList<String>(), UserLayout.loadOrder(f.context))
  }
}
