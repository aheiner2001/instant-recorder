package app.instantrecorder.ui

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.instantrecorder.data.Category
import app.instantrecorder.data.NoteItem
import app.instantrecorder.tasks.TaskSuggestion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val FILTER_UNASSIGNED = -1L

fun NoteItem.isUnassigned(validCategoryIds: Set<Long>): Boolean =
    categoryId == null || categoryId !in validCategoryIds

fun dateBucketLabel(
    epochMillis: Long,
    now: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val noteDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val daysDiff = ChronoUnit.DAYS.between(noteDate, now)
    return when (daysDiff) {
        0L -> "Today"
        1L -> "Yesterday"
        in 2L..6L -> noteDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d", locale))
        else -> noteDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesList(
    notes: List<NoteItem>,
    categories: List<Category>,
    onAssignCategory: (NoteItem, Long?) -> Unit,
    onDelete: (NoteItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onNoteClick: ((NoteItem) -> Unit)? = null,
    onUpdateText: ((NoteItem, String) -> Unit)? = null,
    onTogglePin: ((NoteItem) -> Unit)? = null,
    onAddTask: ((TaskSuggestion, Long?, Long?) -> Unit)? = null,
    vm: MainViewModel? = null,
) {
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // null = All, -1L = Unassigned, > 0L = specific category id
    var selectedFilterId by rememberSaveable { mutableStateOf<Long?>(null) }
    var assigningNoteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingNoteId by rememberSaveable { mutableStateOf<Long?>(null) }

    val validCategoryIds = remember(categories) { categories.map { it.id }.toSet() }

    val assigningNote = remember(notes, assigningNoteId) {
        if (assigningNoteId == null) null else notes.firstOrNull { it.id == assigningNoteId }
    }
    val editingNote = remember(notes, editingNoteId) {
        if (editingNoteId == null) null else notes.firstOrNull { it.id == editingNoteId }
    }

    val unassignedCount = remember(notes, validCategoryIds) {
        notes.count { it.isUnassigned(validCategoryIds) }
    }

    val filteredNotes = remember(notes, searchQuery, selectedFilterId, validCategoryIds) {
        notes.filter { note ->
            val matchesCategory = when (selectedFilterId) {
                null -> true
                FILTER_UNASSIGNED -> note.isUnassigned(validCategoryIds)
                else -> note.categoryId == selectedFilterId
            }
            val matchesSearch = searchQuery.isBlank() || note.text.contains(searchQuery.trim(), ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    val (pinned, unpinned) = remember(filteredNotes) { filteredNotes.partition { it.pinned } }
    var collapsedSections by rememberSaveable { mutableStateOf(setOf<String>()) }
    val unpinnedByDate = remember(unpinned) {
        unpinned.groupBy { dateBucketLabel(it.createdAt) }
    }

    Column(modifier = modifier) {
        // Search field at top to filter notes by keyword in real time
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search notes…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Filter chips: All, Unassigned (N), and existing category chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            item {
                FilterChip(
                    selected = selectedFilterId == null,
                    onClick = { selectedFilterId = null },
                    label = { Text("All") },
                )
            }
            item {
                val isUnassignedSelected = selectedFilterId == FILTER_UNASSIGNED
                FilterChip(
                    selected = isUnassignedSelected,
                    onClick = {
                        selectedFilterId = if (isUnassignedSelected) null else FILTER_UNASSIGNED
                    },
                    label = {
                        Text(if (unassignedCount > 0) "Unassigned ($unassignedCount)" else "Unassigned")
                    },
                    leadingIcon = if (unassignedCount > 0) {
                        {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = "Unassigned notes triage badge",
                                modifier = Modifier.size(16.dp),
                                tint = if (isUnassignedSelected) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.error,
                            )
                        }
                    } else null,
                    colors = if (unassignedCount > 0) {
                        FilterChipDefaults.filterChipColors(
                            containerColor = if (isUnassignedSelected) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                            },
                            labelColor = if (isUnassignedSelected) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        FilterChipDefaults.filterChipColors()
                    },
                )
            }
            items(categories, key = { it.id }) { cat ->
                val isCategorySelected = selectedFilterId == cat.id
                FilterChip(
                    selected = isCategorySelected,
                    onClick = {
                        selectedFilterId = if (isCategorySelected) null else cat.id
                    },
                    label = { Text(cat.name) },
                    leadingIcon = { CategoryDot(cat.color) },
                )
            }
        }

        // Notes list
        if (notes.isEmpty()) {
            EmptyHint("No notes yet. Speak a note to get started.")
        } else if (filteredNotes.isEmpty()) {
            EmptyHint("No notes match your search or filter.")
        } else {
            LazyColumn(
                contentPadding = contentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (pinned.isNotEmpty()) {
                    item(key = "header_pinned") {
                        PinnedHeader(count = pinned.size)
                    }
                    items(pinned, key = { it.id }) { note ->
                        SwipeToDelete(
                            enabled = true,
                            onDelete = { onDelete(note) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            NoteRow(
                                note = note,
                                category = categories.firstOrNull { it.id == note.categoryId },
                                onAssignCategory = { assigningNoteId = note.id },
                                onClick = {
                                    editingNoteId = note.id
                                    onNoteClick?.invoke(note)
                                },
                            )
                        }
                    }
                }

                unpinnedByDate.forEach { (dateLabel, groupNotes) ->
                    val isCollapsed = dateLabel in collapsedSections
                    stickyHeader(key = "header_$dateLabel") {
                        val groupCategories = remember(groupNotes, categories) {
                            val catMap = categories.associateBy { it.id }
                            groupNotes.mapNotNull { it.categoryId }.distinct().mapNotNull { catMap[it] }
                        }
                        DateGroupHeader(
                            title = dateLabel,
                            count = groupNotes.size,
                            categories = groupCategories,
                            isCollapsed = isCollapsed,
                            onToggleCollapse = {
                                collapsedSections = if (isCollapsed) {
                                    collapsedSections - dateLabel
                                } else {
                                    collapsedSections + dateLabel
                                }
                            },
                        )
                    }
                    if (!isCollapsed) {
                        items(groupNotes, key = { it.id }) { note ->
                            SwipeToDelete(
                                enabled = true,
                                onDelete = { onDelete(note) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                NoteRow(
                                    note = note,
                                    category = categories.firstOrNull { it.id == note.categoryId },
                                    onAssignCategory = { assigningNoteId = note.id },
                                    onClick = {
                                        editingNoteId = note.id
                                        onNoteClick?.invoke(note)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingNote?.let { note ->
        NoteEditorSheet(
            note = note,
            categories = categories,
            onDismiss = { editingNoteId = null },
            onSaveText = { newText ->
                onUpdateText?.invoke(note, newText)
            },
            onSelectCategory = { newCategoryId ->
                onAssignCategory(note, newCategoryId)
            },
            onTogglePin = {
                onTogglePin?.invoke(note)
            },
            onDelete = {
                onDelete(note)
                editingNoteId = null
            },
            onShare = { sharedText ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, sharedText)
                    .putExtra(Intent.EXTRA_SUBJECT, "Note")
                context.launchSafely(Intent.createChooser(send, "Share note"))
            },
            onCalendar = { calText ->
                val title = calText.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Note"
                val intent = Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.Events.DESCRIPTION, calText)
                context.launchSafely(intent)
            },
            onAddTask = { suggestion ->
                onAddTask?.invoke(suggestion, note.categoryId, note.recordingId)
                    ?: vm?.addTaskFromSuggestion(suggestion, note.categoryId, note.recordingId)
            },
        )
    }

    assigningNote?.let { note ->
        AssignCategoryBottomSheet(
            categories = categories,
            selectedCategoryId = if (note.isUnassigned(validCategoryIds)) null else note.categoryId,
            onSelectCategory = { newCategory ->
                onAssignCategory(note, newCategory?.id)
                assigningNoteId = null
            },
            onDismiss = { assigningNoteId = null },
        )
    }
}

@Composable
fun PinnedHeader(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Pinned Thoughts ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun DateGroupHeader(
    title: String,
    count: Int,
    categories: List<Category>,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onToggleCollapse)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (categories.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    categories.forEach { cat ->
                        CategoryDot(cat.color, 8.dp)
                    }
                }
            }
        }
        Icon(
            imageVector = if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = if (isCollapsed) "Expand $title" else "Collapse $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Note card displaying text, creation date, category label (or Unassigned), and "Assign" button. */
@Composable
fun NoteRow(
    note: NoteItem,
    category: Category?,
    onAssignCategory: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val cardModifier = if (onClick != null) {
        modifier.fillMaxWidth().clickable { onClick() }
    } else {
        modifier.fillMaxWidth()
    }
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (note.pinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Pinned note",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = formatTimestamp(note.createdAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (category != null) {
                        CategoryLabel(category)
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        ) {
                            Text(
                                text = "Unassigned",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onAssignCategory,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("Assign", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Text(
                text = note.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Modal bottom sheet displaying existing categories and an option to remove category (Unassigned). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignCategoryBottomSheet(
    categories: List<Category>,
    selectedCategoryId: Long? = null,
    onSelectCategory: (Category?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Assign Category",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )

            // 1-tap option to remove category (Unassigned)
            ListItem(
                headlineContent = { Text("Unassigned") },
                supportingContent = { Text("Remove from category") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.LabelOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    if (selectedCategoryId == null) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                modifier = Modifier
                    .clickable {
                        onSelectCategory(null)
                        onDismiss()
                    }
                    .padding(horizontal = 8.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(categories, key = { it.id }) { category ->
                    val isSelected = category.id == selectedCategoryId
                    ListItem(
                        headlineContent = { Text(category.name) },
                        leadingContent = { CategoryDot(category.color, 12.dp) },
                        trailingContent = {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        modifier = Modifier
                            .clickable {
                                onSelectCategory(category)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/** Overload accepting Category ID directly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignCategoryBottomSheet(
    categories: List<Category>,
    selectedCategoryId: Long? = null,
    onDismiss: () -> Unit,
    onSelectCategoryId: (Long?) -> Unit,
) {
    AssignCategoryBottomSheet(
        categories = categories,
        selectedCategoryId = selectedCategoryId,
        onSelectCategory = { onSelectCategoryId(it?.id) },
        onDismiss = onDismiss,
    )
}
