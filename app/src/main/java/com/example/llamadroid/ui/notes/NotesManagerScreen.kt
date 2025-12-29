package com.example.llamadroid.ui.notes

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Notes Manager Screen - Unified view for transcriptions, PDF summaries, and manual notes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val clipboardManager = LocalClipboardManager.current
    
    // State
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<NoteType?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<NoteEntity?>(null) }
    var fullScreenNote by remember { mutableStateOf<NoteEntity?>(null) }  // Full screen view
    
    // Load notes
    val allNotes by db.noteDao().getAllNotes().collectAsState(initial = emptyList())
    
    // Filter notes based on search and type filter
    val filteredNotes = remember(allNotes, searchQuery, selectedFilter) {
        allNotes.filter { note ->
            val matchesSearch = searchQuery.isEmpty() || 
                note.title.contains(searchQuery, ignoreCase = true) ||
                note.content.contains(searchQuery, ignoreCase = true)
            val matchesType = selectedFilter == null || note.type == selectedFilter
            matchesSearch && matchesType
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notes_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, stringResource(R.string.notes_new))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search notes...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Filter chips - horizontal scroll for more filters
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = selectedFilter == NoteType.TRANSCRIPTION,
                    onClick = { selectedFilter = if (selectedFilter == NoteType.TRANSCRIPTION) null else NoteType.TRANSCRIPTION },
                    label = { Text("🎤") }
                )
                FilterChip(
                    selected = selectedFilter == NoteType.PDF_SUMMARY,
                    onClick = { selectedFilter = if (selectedFilter == NoteType.PDF_SUMMARY) null else NoteType.PDF_SUMMARY },
                    label = { Text("📄") }
                )
                FilterChip(
                    selected = selectedFilter == NoteType.VIDEO_SUMMARY,
                    onClick = { selectedFilter = if (selectedFilter == NoteType.VIDEO_SUMMARY) null else NoteType.VIDEO_SUMMARY },
                    label = { Text("🎬") }
                )
                FilterChip(
                    selected = selectedFilter == NoteType.WORKFLOW,
                    onClick = { selectedFilter = if (selectedFilter == NoteType.WORKFLOW) null else NoteType.WORKFLOW },
                    label = { Text("⚙️") }
                )
                FilterChip(
                    selected = selectedFilter == NoteType.MANUAL,
                    onClick = { selectedFilter = if (selectedFilter == NoteType.MANUAL) null else NoteType.MANUAL },
                    label = { Text("📝") }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Notes list
            if (filteredNotes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No notes yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            "Create a note or transcribe audio to get started",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { fullScreenNote = note },  // Open full screen
                            onEdit = { selectedNote = note },
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(note.content))
                            },
                            onDelete = {
                                noteToDelete = note
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            }
        }
    }
    
    // Add/Edit note dialog
    if (showAddDialog || selectedNote != null) {
        NoteEditDialog(
            note = selectedNote,
            onDismiss = {
                showAddDialog = false
                selectedNote = null
            },
            onSave = { title, content ->
                scope.launch {
                    if (selectedNote != null) {
                        db.noteDao().update(
                            selectedNote!!.copy(
                                title = title,
                                content = content,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    } else {
                        db.noteDao().insert(
                            NoteEntity(
                                title = title,
                                content = content,
                                type = NoteType.MANUAL
                            )
                        )
                    }
                    showAddDialog = false
                    selectedNote = null
                }
            }
        )
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog && noteToDelete != null) {
        AlertDialog(
            onDismissRequest = { 
                showDeleteDialog = false
                noteToDelete = null
            },
            title = { Text("Delete Note?") },
            text = { Text("Are you sure you want to delete \"${noteToDelete?.title}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            noteToDelete?.let { db.noteDao().delete(it) }
                            showDeleteDialog = false
                            noteToDelete = null
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showDeleteDialog = false
                    noteToDelete = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Full screen note view
    fullScreenNote?.let { note ->
        NoteFullScreenDialog(
            note = note,
            onDismiss = { fullScreenNote = null },
            onEdit = {
                fullScreenNote = null
                selectedNote = note
            },
            onCopy = {
                clipboardManager.setText(AnnotatedString(note.content))
            },
            onDelete = {
                fullScreenNote = null
                noteToDelete = note
                showDeleteDialog = true
            }
        )
    }
}

@Composable
private fun NoteCard(
    note: NoteEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Type badge
                val (emoji, badgeColor) = when (note.type) {
                    NoteType.TRANSCRIPTION -> "🎤" to MaterialTheme.colorScheme.primaryContainer
                    NoteType.PDF_SUMMARY -> "📄" to MaterialTheme.colorScheme.secondaryContainer
                    NoteType.VIDEO_SUMMARY -> "🎬" to MaterialTheme.colorScheme.tertiaryContainer
                    NoteType.WORKFLOW -> "⚙️" to MaterialTheme.colorScheme.surfaceVariant
                    NoteType.MANUAL -> "📝" to MaterialTheme.colorScheme.surfaceContainerHigh
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(badgeColor)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(emoji, style = MaterialTheme.typography.bodyMedium)
                    }
                    // Audio indicator
                    if (note.audioPath != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.PlayArrow, "Has audio", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                
                // Actions
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Share, "Copy", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                note.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                note.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                note.sourceFile?.let {
                    Text(
                        "Source: ${it.substringAfterLast("/")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Text(
                    dateFormat.format(Date(note.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditDialog(
    note: NoteEntity?,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String) -> Unit
) {
    var title by remember(note) { mutableStateOf(note?.title ?: "") }
    var content by remember(note) { mutableStateOf(note?.content ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize(0.95f),
        title = { Text(if (note == null) "New Note" else "Edit Note") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp),
                    maxLines = 50
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, content) },
                enabled = title.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Full screen dialog for viewing long notes with audio playback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteFullScreenDialog(
    note: NoteEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    
    // Audio playback state
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    
    // Cleanup media player
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }
    
    // Type info
    val (emoji, typeName) = when (note.type) {
        NoteType.TRANSCRIPTION -> "🎤" to "Transcription"
        NoteType.PDF_SUMMARY -> "📄" to "PDF Summary"
        NoteType.VIDEO_SUMMARY -> "🎬" to "Video Summary"
        NoteType.WORKFLOW -> "⚙️" to "Workflow"
        NoteType.MANUAL -> "📝" to "Note"
    }
    
    AlertDialog(
        onDismissRequest = {
            mediaPlayer?.release()
            onDismiss()
        },
        modifier = Modifier.fillMaxSize(0.95f)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with actions only
                TopAppBar(
                    title = { Text("Note Details") },
                    navigationIcon = {
                        IconButton(onClick = { 
                            mediaPlayer?.release()
                            onDismiss() 
                        }) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    },
                    actions = {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, "Edit")
                        }
                        IconButton(onClick = onCopy) {
                            Icon(Icons.Default.Share, "Copy")
                        }
                        IconButton(onClick = {
                            mediaPlayer?.release()
                            onDelete()
                        }) {
                            Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
                
                // Visible title card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            note.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "$emoji $typeName • ${dateFormat.format(Date(note.updatedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Audio player (if available)
                note.audioPath?.let { audioPath ->
                    if (File(audioPath).exists()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    if (isPlaying) {
                                        mediaPlayer?.pause()
                                        isPlaying = false
                                    } else {
                                        if (mediaPlayer == null) {
                                            mediaPlayer = MediaPlayer().apply {
                                                setDataSource(audioPath)
                                                prepare()
                                                setOnCompletionListener { isPlaying = false }
                                            }
                                        }
                                        mediaPlayer?.start()
                                        isPlaying = true
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                                    if (isPlaying) "Stop" else "Play",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "🎵 Audio",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // Scrollable content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Source file info
                    note.sourceFile?.let {
                        Text(
                            "Source: ${it.substringAfterLast("/")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // Note content
                    Text(
                        note.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

