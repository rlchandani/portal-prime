/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

class TodoSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme(darkTheme = false) { TodoSettingsScreen() } }
    }
}

@Composable
private fun TodoSettingsScreen() {
    val onBack = rememberApprovedSettingsBack()
    SettingsScaffold(title = "To-do", onBack = onBack) { TodoSettingsContent() }
}

@Composable
internal fun TodoSettingsContent() {
    val context = LocalContext.current
    val onBack = rememberApprovedSettingsBack()
    var items by remember { mutableStateOf(TodoStore.load(context)) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<TodoStore.TodoItem?>(null) }
    var clearCompleted by remember { mutableStateOf(false) }
    val ordered = remember(items) { items.sortedBy { it.done } }
    fun refresh() { items = TodoStore.load(context) }

    Column {
        ApprovedEditorsCollection("Your to-do", ordered, key = { it.id }) { item ->
            ApprovedEditorsCollectionRow(item.text,
                detail = if (item.done) "Completed" else "",
                onRemove = { removal = item },
                action = if (item.done) "Undo" else "Complete",
                onAction = { TodoStore.toggle(context, item.id); refresh() })
        }
        ApprovedEditorsForm(actions = {
            ApprovedEditorsActions(error = error, onCancel = {
                draft = ""; error = null; onBack()
            }) {
                if (draft.isBlank()) error = "Enter what needs doing."
                else { TodoStore.add(context, draft); draft = ""; error = null; refresh() }
            }
        }) {
            ApprovedEditorsField("What needs doing?", draft, { draft = it; error = null })
        }
        if (items.any { it.done }) {
            Spacer(Modifier.height(24.dp))
            ApprovedSettingsTextButton("Clear completed") { clearCompleted = true }
        }
    }
    removal?.let { item ->
        ApprovedEditorsRemoveDialog(item.text, onCancel = { removal = null }) {
            TodoStore.remove(context, item.id); removal = null; refresh()
        }
    }
    if (clearCompleted) {
        ApprovedEditorsRemoveDialog("completed items", onCancel = { clearCompleted = false }) {
            TodoStore.clearCompleted(context); clearCompleted = false; refresh()
        }
    }
}
