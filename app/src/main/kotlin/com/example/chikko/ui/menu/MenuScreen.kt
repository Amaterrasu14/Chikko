package com.example.chikko.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID
import com.example.chikko.core.Money
import com.example.chikko.data.db.CategoryEntity
import com.example.chikko.data.db.MenuItemEntity
import com.example.chikko.ui.LocalApp
import kotlinx.coroutines.launch

@Composable
fun MenuScreen(pad: PaddingValues) {
    val app = LocalApp.current
    val repo = app.repo
    val scope = rememberCoroutineScope()
    val categories by repo.observeCategories().collectAsState(initial = emptyList())
    val items by repo.observeItems().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<MenuItemEntity?>(null) }
    var addingItem by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    if (addingItem || editing != null) {
        MenuItemEditor(
            existing = editing,
            categories = categories,
            onDismiss = { addingItem = false; editing = null },
            onSave = { row ->
                scope.launch {
                    runCatching { repo.upsertMenuItem(row) }
                        .onFailure { message = it.message ?: "Save failed" }
                }
                addingItem = false
                editing = null
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Menu", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            Button(onClick = { addingItem = true }, enabled = categories.isNotEmpty()) { Text("Add item") }
        }
        if (categories.isEmpty()) {
            Text("No categories yet. Add a category first.")
        }
        if (message.isNotEmpty()) Text(message)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                val catName = categories.firstOrNull { it.id == item.categoryId }?.name.orEmpty()
                Card(Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { editing = item }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Text("$catName • ${Money.formatCentavos(item.priceCentavos)}")
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Switch(
                                checked = item.active,
                                onCheckedChange = { on ->
                                    scope.launch { repo.upsertMenuItem(item.copy(active = on)) }
                                },
                            )
                            Text(if (item.active) "Active" else "Inactive")
                            TextButton(onClick = { scope.launch { repo.deleteMenuItem(item.id) } }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItemEditor(
    existing: MenuItemEntity?,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (MenuItemEntity) -> Unit,
) {
    val pad = PaddingValues(0.dp)
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var price by remember {
        mutableStateOf(existing?.let { Money.formatCentavos(it.priceCentavos).removePrefix("₱").replace(",", "") } ?: "")
    }
    var categoryId by remember { mutableStateOf(existing?.categoryId ?: categories.firstOrNull()?.id.orEmpty()) }
    var error by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (existing == null) "New menu item" else "Edit menu item")
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = price,
            onValueChange = { price = it },
            label = { Text("Price (₱)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Category")
        categories.forEach { c ->
            TextButton(onClick = { categoryId = c.id }) {
                Text((if (c.id == categoryId) "● " else "○ ") + c.name)
            }
        }
        if (error.isNotEmpty()) Text(error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val cents = Money.parsePesosToCentavos(price)
                when {
                    name.isBlank() -> error = "Name is required"
                    cents == null -> error = "Enter a valid price"
                    categoryId.isEmpty() -> error = "Choose a category"
                    else -> onSave(
                        MenuItemEntity(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            categoryId = categoryId,
                            name = name.trim(),
                            priceCentavos = cents,
                            active = existing?.active ?: true,
                            notes = existing?.notes.orEmpty(),
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                }
            }) { Text("Save") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}
