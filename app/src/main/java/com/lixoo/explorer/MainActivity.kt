package com.lixoo.explorer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var currentPath by mutableStateOf(Environment.getExternalStorageDirectory().absolutePath)
    private var filesList by mutableStateOf(listOf<FileItem>())
    private var isRootAvailable by mutableStateOf(false)
    private var clipboardFile by mutableStateOf<FileItem?>(null)

    data class FileItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isRootAvailable = RootUtils.isRootAvailable()
        checkPermissions()
        refreshFiles()

        setContent {
            LixooTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    FileExplorerScreen(
                        currentPath = currentPath,
                        files = filesList,
                        onFileClick = { item ->
                            if (item.isDirectory) {
                                currentPath = item.path
                                refreshFiles()
                            } else {
                                openFile(File(item.path))
                            }
                        },
                        onBack = {
                            val parent = File(currentPath).parent
                            if (parent != null) {
                                currentPath = parent
                                refreshFiles()
                            }
                        },
                        onDelete = { item ->
                            deleteFile(item)
                            refreshFiles()
                        },
                        onRootToggle = {
                            if (currentPath == "/") {
                                currentPath = Environment.getExternalStorageDirectory().absolutePath
                            } else {
                                currentPath = "/"
                            }
                            refreshFiles()
                        },
                        onCopy = { item ->
                            clipboardFile = item
                            Toast.makeText(this, "Kopyalandı: ${item.name}", Toast.LENGTH_SHORT).show()
                        },
                        onPaste = {
                            pasteFile()
                        },
                        onCreateFolder = { name ->
                            createFolder(name)
                        }
                    )
                }
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
                requestPermissionsLauncher.launch(permissions)
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshFiles() }

    private fun refreshFiles() {
        val file = File(currentPath)
        val items = mutableListOf<FileItem>()

        val listFiles = file.listFiles()
        if (listFiles != null) {
            listFiles.forEach {
                items.add(FileItem(it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified()))
            }
        } else if (currentPath.startsWith("/") && isRootAvailable) {
            // Try root listing
            val output = RootUtils.runCommand("ls -F $currentPath")
            output.forEach { line ->
                if (line.isNotBlank()) {
                    val isDir = line.endsWith("/")
                    val name = line.removeSuffix("/")
                    items.add(FileItem(name, "$currentPath/$name".replace("//", "/"), isDir, 0, 0))
                }
            }
        }

        filesList = items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun createFolder(name: String) {
        val newFolder = File(currentPath, name)
        if (newFolder.mkdir()) {
            Toast.makeText(this, "Klasör oluşturuldu", Toast.LENGTH_SHORT).show()
            refreshFiles()
        } else if (isRootAvailable) {
            RootUtils.runCommand("mkdir -p \"${newFolder.absolutePath}\"")
            Toast.makeText(this, "Root ile oluşturuldu", Toast.LENGTH_SHORT).show()
            refreshFiles()
        } else {
            Toast.makeText(this, "Oluşturulamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteFile() {
        val source = clipboardFile ?: return
        val destination = File(currentPath, source.name)

        Thread {
            try {
                if (source.isDirectory) {
                    copyDirectory(File(source.path), destination)
                } else {
                    File(source.path).copyTo(destination, overwrite = true)
                }
                runOnUiThread {
                    Toast.makeText(this, "Yapıştırıldı", Toast.LENGTH_SHORT).show()
                    refreshFiles()
                }
            } catch (e: Exception) {
                if (isRootAvailable) {
                    RootUtils.runCommand("cp -rf \"${source.path}\" \"${destination.absolutePath}\"")
                    runOnUiThread {
                        Toast.makeText(this, "Root ile yapıştırıldı", Toast.LENGTH_SHORT).show()
                        refreshFiles()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Hata: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun copyDirectory(source: File, destination: File) {
        if (!destination.exists()) destination.mkdirs()
        source.listFiles()?.forEach { file ->
            val destFile = File(destination, file.name)
            if (file.isDirectory) {
                copyDirectory(file, destFile)
            } else {
                file.copyTo(destFile, overwrite = true)
            }
        }
    }

    private fun deleteFile(item: FileItem) {
        val file = File(item.path)
        if (file.exists()) {
            if (file.delete()) {
                Toast.makeText(this, "Silindi", Toast.LENGTH_SHORT).show()
            } else {
                if (isRootAvailable) {
                    RootUtils.runCommand("rm -rf ${item.path}")
                    Toast.makeText(this, "Root ile silindi", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Silinemedi", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (isRootAvailable) {
            RootUtils.runCommand("rm -rf ${item.path}")
            Toast.makeText(this, "Root ile silindi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Aç"))
        } catch (e: Exception) {
            Toast.makeText(this, "Açılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    currentPath: String,
    files: List<MainActivity.FileItem>,
    onFileClick: (MainActivity.FileItem) -> Unit,
    onBack: () -> Unit,
    onDelete: (MainActivity.FileItem) -> Unit,
    onRootToggle: () -> Unit,
    onCopy: (MainActivity.FileItem) -> Unit,
    onPaste: () -> Unit,
    onCreateFolder: (String) -> Unit
) {
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Yeni Klasör") },
            text = {
                TextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text("Klasör adı") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    onCreateFolder(newFolderName)
                    showCreateFolderDialog = false
                    newFolderName = ""
                }) { Text("Oluştur") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("İptal") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentPath, maxLines = 1, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateFolderDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Yeni Klasör")
                    }
                    IconButton(onClick = onPaste) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Yapıştır")
                    }
                    IconButton(onClick = onRootToggle) {
                        Icon(Icons.Default.Shield, contentDescription = "Root")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(files) { file ->
                FileRow(file, onFileClick, onDelete, onCopy)
            }
        }
    }
}

@Composable
fun FileRow(
    file: MainActivity.FileItem,
    onFileClick: (MainActivity.FileItem) -> Unit,
    onDelete: (MainActivity.FileItem) -> Unit,
    onCopy: (MainActivity.FileItem) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onFileClick(file) }
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.FilePresent,
            contentDescription = null,
            tint = if (file.isDirectory) Color(0xFF2196F3) else Color.Gray
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge)
            if (!file.isDirectory) {
                Text(
                    "${formatSize(file.size)} - ${formatDate(file.lastModified)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Row {
            IconButton(onClick = { onCopy(file) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Kopyala", tint = Color.Gray)
            }
            IconButton(onClick = { onDelete(file) }) {
                Icon(Icons.Default.Delete, contentDescription = "Sil", tint = Color.Red)
            }
        }
    }
}

fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
fun LixooTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6200EE),
            secondary = Color(0xFF03DAC5)
        ),
        content = content
    )
}
