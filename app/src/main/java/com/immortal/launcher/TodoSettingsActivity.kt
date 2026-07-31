/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * Editor for the local To-Do widget ([TodoStore]). Light iOS layout matching the other widget
 * editors: a card of current items (tap the row to toggle done, ✕ to delete), an add field, and a
 * "Clear completed" action when any items are checked.
 */
class TodoSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { TodoSettingsScreen() } }
  }
}

@Composable
private fun TodoSettingsScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "To-Do",
      subtitle = "A shared checklist on the home screen. Tap an item to check it off.",
      onBack = { activity?.finish() },
  ) { TodoSettingsContent() }
}

@Composable
internal fun TodoSettingsContent() {
  val context = LocalContext.current
  var items by remember { mutableStateOf(TodoStore.load(context)) }
  var draft by remember { mutableStateOf("") }

  fun refresh() {
    items = TodoStore.load(context)
  }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    // Current items (open first, then done).
    if (items.isNotEmpty()) {
      Card {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
          items.sortedBy { it.done }.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                  modifier =
                      Modifier.size(32.dp).clickable {
                        TodoStore.toggle(context, item.id)
                        refresh()
                      },
                  contentAlignment = Alignment.Center,
              ) {
                Text(
                    if (item.done) "☑" else "☐",
                    color = if (item.done) Color(0xFF34C759) else IosSecondary,
                    fontSize = 22.sp,
                )
              }
              Text(
                  item.text,
                  color = if (item.done) IosSecondary else IosLabel,
                  fontSize = Sizes.rowTitle,
                  textDecoration = if (item.done) TextDecoration.LineThrough else null,
                  modifier = Modifier.weight(1f).padding(start = 8.dp),
              )
              Box(
                  modifier =
                      Modifier.size(40.dp).clickable {
                        TodoStore.remove(context, item.id)
                        refresh()
                      },
                  contentAlignment = Alignment.Center,
              ) {
                Text("✕", color = Color(0xFFFF3B30), fontSize = 20.sp)
              }
            }
          }
        }
      }
    }

    // Add field.
    Card {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add item", color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("What needs doing?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Surface(
            color = IosBlue,
            shape = RoundedCornerShape(14.dp),
            modifier =
                Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) {
                  TodoStore.add(context, draft)
                  draft = ""
                  refresh()
                },
        ) {
          Text(
              "Add",
              color = Color.White,
              fontSize = 18.sp,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
          )
        }
      }
    }

    // Clear completed (only when something is checked).
    if (items.any { it.done }) {
      Surface(
          color = IosCard,
          shape = RoundedCornerShape(14.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) {
                TodoStore.clearCompleted(context)
                refresh()
              },
      ) {
        Text(
            "Clear completed",
            color = Color(0xFFFF3B30),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
        )
      }
    }
    Spacer(Modifier.size(8.dp))
  }
}
