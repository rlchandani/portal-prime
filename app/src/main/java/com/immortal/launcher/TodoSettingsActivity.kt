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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    var itemsResult by remember {
        mutableStateOf<Result<List<TodoStore.TodoItem>>>(runCatching { TodoStore.load(context) })
    }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<TodoStore.TodoItem?>(null) }
    var clearCompleted by remember { mutableStateOf(false) }
    val items = itemsResult.getOrNull()
    val ordered = remember(items) { items?.sortedBy { it.done } }
    fun refresh() { itemsResult = runCatching { TodoStore.load(context) } }
    fun mutate(action: () -> Unit): Boolean {
        val result = runCatching(action)
        refresh()
        error = when {
            result.isFailure -> "Changes couldn't be saved. Try again."
            itemsResult.isFailure -> "Changes were saved, but your to-do list couldn't be read. Try again."
            else -> null
        }
        return result.isSuccess
    }

    Column {
        if (ordered != null) {
            ApprovedEditorsCollection("Your to-do", ordered, key = { it.id }) { item ->
                ApprovedEditorsCollectionRow(item.text,
                    detail = if (item.done) "Completed" else "",
                    onRemove = { error = null; removal = item },
                    action = if (item.done) "Undo" else "Complete",
                    onAction = { mutate { TodoStore.toggle(context, item.id) } })
            }
        } else {
            SectionLabel("Your to-do")
            Card {
                Column {
                    ApprovedEditorsEmpty("Your to-do list is unavailable. It couldn't be read. Try again before making changes.")
                    ApprovedSettingsTextButton("Try again") {
                        refresh()
                        if (itemsResult.isSuccess) error = null
                    }
                }
            }
        }
        ApprovedEditorsForm(actions = {
            ApprovedEditorsActions(error = error, enabled = itemsResult.isSuccess, onCancel = {
                draft = ""; error = null; onBack()
            }) {
                if (draft.isBlank()) error = "Enter what needs doing."
                else if (mutate { TodoStore.add(context, draft) }) draft = ""
            }
        }) {
            ApprovedEditorsField("What needs doing?", draft, { draft = it; error = null },
                enabled = itemsResult.isSuccess)
        }
        if (items?.any { it.done } == true) {
            Spacer(Modifier.height(24.dp))
            ApprovedSettingsTextButton("Clear completed") { error = null; clearCompleted = true }
        }
    }
    removal?.let { item ->
        TodoRemoveDialog(item.text, error, onCancel = { removal = null; error = null }) {
            if (mutate { TodoStore.remove(context, item.id) }) removal = null
        }
    }
    if (clearCompleted) {
        TodoRemoveDialog("completed items", error,
            onCancel = { clearCompleted = false; error = null }) {
            if (mutate { TodoStore.clearCompleted(context) }) clearCompleted = false
        }
    }
}

@Composable
private fun TodoRemoveDialog(
    name: String, error: String?, onCancel: () -> Unit, onRemove: () -> Unit,
) {
    ApprovedSettingsDialog("Remove $name?", onDismiss = onCancel, help = error) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ApprovedEditorsButton("Cancel", onClick = onCancel)
            ApprovedEditorsButton("Remove", primary = true, onClick = onRemove)
        }
    }
}
