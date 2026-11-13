package org.fieldradio.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * P2 Library SAF (Storage Access Framework) Integration
 * 
 * Allows operator to import audio files (MP3, WAV, FLAC) from device storage
 * using Android's standard file picker. Files are copied to app-specific storage.
 * Supports drag-and-drop and batch imports.
 */

data class LibraryItem(
    val id: String,
    val name: String,
    val uri: Uri,
    val sizeBytes: Long = 0,
    val format: String = "unknown", // mp3, wav, flac, etc.
    val duration: Long = 0, // milliseconds
)

enum class AudioFileFormat(val mimeTypes: List<String>, val extensions: List<String>) {
    MP3(listOf("audio/mpeg"), listOf("mp3")),
    WAV(listOf("audio/wav", "audio/x-wav"), listOf("wav")),
    FLAC(listOf("audio/flac"), listOf("flac")),
    AAC(listOf("audio/aac"), listOf("aac", "m4a")),
    OGG(listOf("audio/ogg"), listOf("ogg", "oga")),
    ;
    
    companion object {
        fun fromExtension(ext: String): AudioFileFormat? {
            return values().firstOrNull { ext.lowercase() in it.extensions }
        }
        
        fun supportedMimeTypes(): List<String> {
            return values().flatMap { it.mimeTypes }.distinct()
        }
    }
}

object LibraryImportUtils {
    fun getAudioFileFormat(filename: String): AudioFileFormat? {
        val ext = filename.substringAfterLast(".").lowercase()
        return AudioFileFormat.fromExtension(ext)
    }
    
    fun isAudioFile(filename: String): Boolean {
        return getAudioFileFormat(filename) != null
    }
    
    suspend fun copyFileToLibrary(
        context: Context,
        sourceUri: Uri,
        destDir: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        return@withContext try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext Result.failure(Exception("Cannot open source file"))
            
            val filename = DocumentFile.fromSingleUri(context, sourceUri)?.name
                ?: "unknown_${System.currentTimeMillis()}"
            
            val destFile = File(destDir, filename)
            inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@Composable
fun LibraryImportScreen(
    items: List<LibraryItem>,
    onImportFiles: (List<Uri>) -> Unit,
    onRemoveItem: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportFiles(uris)
        }
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Library Files",
                style = MaterialTheme.typography.headlineSmall
            )
            FloatingActionButton(
                onClick = {
                    filePicker.launch(arrayOf("audio/*"))
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add files")
            }
        }
        
        // File list
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.FilePresent,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "No files imported",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { filePicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text("Import Audio Files")
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items) { item ->
                    LibraryItemRow(
                        item = item,
                        onRemove = { onRemoveItem(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryItemRow(
    item: LibraryItem,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.FilePresent,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "${item.format.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• ${formatFileSize(item.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (item.duration > 0) {
                        Text(
                            "• ${formatDuration(item.duration)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / (1024.0))
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

@Composable
fun LibraryImportStatusIndicator(
    isImporting: Boolean,
    importProgress: Float = 0f,
    errorMessage: String? = null,
) {
    if (isImporting) {
        LinearProgressIndicator(
            progress = { importProgress },
            modifier = Modifier.fillMaxWidth()
        )
    }
    
    if (errorMessage != null) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                errorMessage,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
