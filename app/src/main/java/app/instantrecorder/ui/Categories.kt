package app.instantrecorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.instantrecorder.data.Category

/** Readable on both light and dark backgrounds. */
internal val CATEGORY_COLORS = listOf(
    0xFF43A047, 0xFF1E88E5, 0xFFFB8C00, 0xFF8E24AA, 0xFFE53935, 0xFF00897B, 0xFFD81B60, 0xFF6D4C41,
).map { it.toInt() }

@Composable
fun CategoryDot(color: Int, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(color)))
}

/** Dot + name, for task rows. */
@Composable
fun CategoryLabel(category: Category) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CategoryDot(category.color, 8.dp)
        Text(category.name, style = MaterialTheme.typography.labelMedium)
    }
}

/** One-tap choice of a category (or none), as a scrollable row of chips. */
@Composable
fun CategoryPicker(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = contentPadding) {
        item {
            FilterChip(selected = selectedId == null, onClick = { onSelect(null) }, label = { Text("None") })
        }
        items(categories, key = { it.id }) { category ->
            FilterChip(
                selected = selectedId == category.id,
                onClick = { onSelect(category.id) },
                label = { Text(category.name) },
                leadingIcon = { CategoryDot(category.color) },
            )
        }
    }
}

/** Create ([initial] null) or edit a category: name, color and the keywords that suggest it. */
@Composable
fun CategoryDialog(initial: Category?, onDismiss: () -> Unit, onSave: (Category) -> Unit, onDelete: (() -> Unit)?) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var color by rememberSaveable { mutableIntStateOf(initial?.color ?: CATEGORY_COLORS.first()) }
    var keywords by rememberSaveable { mutableStateOf(initial?.keywords.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New category" else "Edit category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CATEGORY_COLORS.forEach { c ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .then(if (c == color) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = c }
                                .semantics {
                                    contentDescription = "Color"
                                    selected = c == color
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c == color) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                }
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("Keywords") },
                    placeholder = { Text("office, meeting, report") },
                    supportingText = {
                        Text(
                            "Tasks mentioning these words get this category. You can also start a note with " +
                                "\"${name.ifBlank { "Work" }}:\" to file what follows here."
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleaned = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                    onSave((initial ?: Category(name = "", color = color)).copy(name = name.trim(), color = color, keywords = cleaned))
                },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
