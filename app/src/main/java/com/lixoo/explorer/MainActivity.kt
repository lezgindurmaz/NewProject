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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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

enum class DialogType { FOLDER, FILE }

class MainActivity : ComponentActivity() {

    private var currentPath by mutableStateOf(Environment.getExternalStorageDirectory().absolutePath)
    private var filesList by mutableStateOf(listOf<FileItem>())
    private var isRootAvailable by mutableStateOf(false)
    private var clipboardFile by mutableStateOf<FileItem?>(null)

    // Settings States
    private var showHiddenFiles by mutableStateOf(false)
    private var isDarkMode by mutableStateOf(true)
    private var primaryColor by mutableStateOf(Color(0xFF6200EE))
    private var isSettingsOpen by mutableStateOf(false)

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
            LixooTheme(darkTheme = isDarkMode, primaryColor = primaryColor) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (isSettingsOpen) {
                        SettingsScreen(
                            isDarkMode = isDarkMode,
                            onDarkModeChange = { isDarkMode = it },
                            showHiddenFiles = showHiddenFiles,
                            onHiddenFilesChange = {
                                showHiddenFiles = it
                                refreshFiles()
                            },
                            primaryColor = primaryColor,
                            onPrimaryColorChange = { primaryColor = it },
                            onBack = { isSettingsOpen = false }
                        )
                        BackHandler { isSettingsOpen = false }
                    } else {
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
                                if (parent != null && parent != File(currentPath).path) {
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
                            },
                            onCreateFile = { name ->
                                createFile(name)
                            },
                            onRename = { item, newName ->
                                renameFile(item, newName)
                            },
                            onOpenSettings = { isSettingsOpen = true }
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
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
                if (showHiddenFiles || !it.name.startsWith(".")) {
                    items.add(FileItem(it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified()))
                }
            }
        } else if (currentPath.startsWith("/") && isRootAvailable) {
            // Try root listing
            val output = RootUtils.runCommand("ls -F $currentPath")
            output.forEach { line ->
                if (line.isNotBlank()) {
                    val isDir = line.endsWith("/")
                    val name = line.removeSuffix("/")
                    if (showHiddenFiles || !name.startsWith(".")) {
                        items.add(FileItem(name, "$currentPath/$name".replace("//", "/"), isDir, 0, 0))
                    }
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

    private fun createFile(name: String) {
        val newFile = File(currentPath, name)
        try {
            if (newFile.createNewFile()) {
                Toast.makeText(this, "Dosya oluşturuldu", Toast.LENGTH_SHORT).show()
                refreshFiles()
            } else if (isRootAvailable) {
                RootUtils.runCommand("touch \"${newFile.absolutePath}\"")
                Toast.makeText(this, "Root ile oluşturuldu", Toast.LENGTH_SHORT).show()
                refreshFiles()
            } else {
                Toast.makeText(this, "Oluşturulamadı", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            if (isRootAvailable) {
                RootUtils.runCommand("touch \"${newFile.absolutePath}\"")
                refreshFiles()
            } else {
                Toast.makeText(this, "Hata: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renameFile(item: FileItem, newName: String) {
        val source = File(item.path)
        val destination = File(source.parent, newName)
        if (source.renameTo(destination)) {
            Toast.makeText(this, "Yeniden adlandırıldı", Toast.LENGTH_SHORT).show()
            refreshFiles()
        } else if (isRootAvailable) {
            RootUtils.runCommand("mv \"${source.absolutePath}\" \"${destination.absolutePath}\"")
            Toast.makeText(this, "Root ile yeniden adlandırıldı", Toast.LENGTH_SHORT).show()
            refreshFiles()
        } else {
            Toast.makeText(this, "Başarısız", Toast.LENGTH_SHORT).show()
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
            if (file.deleteRecursively()) {
                Toast.makeText(this, "Silindi", Toast.LENGTH_SHORT).show()
            } else {
                if (isRootAvailable) {
                    RootUtils.runCommand("rm -rf \"${item.path}\"")
                    Toast.makeText(this, "Root ile silindi", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Silinemedi", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (isRootAvailable) {
            RootUtils.runCommand("rm -rf \"${item.path}\"")
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
    onCreateFolder: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onRename: (MainActivity.FileItem, String) -> Unit,
    onOpenSettings: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf<DialogType?>(null) }
    var renameTarget by remember { mutableStateOf<MainActivity.FileItem?>(null) }
    var inputName by remember { mutableStateOf("") }

    if (showCreateDialog != null) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = null },
            title = { Text(if (showCreateDialog == DialogType.FOLDER) "Yeni Klasör" else "Yeni Dosya") },
            text = {
                TextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    placeholder = { Text(if (showCreateDialog == DialogType.FOLDER) "Klasör adı" else "dosya.txt") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (showCreateDialog == DialogType.FOLDER) onCreateFolder(inputName) else onCreateFile(inputName)
                    showCreateDialog = null
                    inputName = ""
                }) { Text("Oluştur") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = null }) { Text("İptal") }
            }
        )
    }

    if (renameTarget != null) {
        var newName by remember { mutableStateOf(renameTarget!!.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Yeniden Adlandır") },
            text = {
                TextField(value = newName, onValueChange = { newName = it })
            },
            confirmButton = {
                Button(onClick = {
                    onRename(renameTarget!!, newName)
                    renameTarget = null
                }) { Text("Kaydet") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("İptal") }
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
                    IconButton(onClick = { showCreateDialog = DialogType.FILE; inputName = "yeni_dosya.txt" }) {
                        Icon(Icons.Default.NoteAdd, contentDescription = "Yeni Dosya")
                    }
                    IconButton(onClick = { showCreateDialog = DialogType.FOLDER; inputName = "" }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Yeni Klasör")
                    }
                    IconButton(onClick = onPaste) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Yapıştır")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ayarlar")
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
                FileRow(file, onFileClick, onDelete, onCopy, onRename = { renameTarget = it })
            }
        }
    }
}

@Composable
fun FileRow(
    file: MainActivity.FileItem,
    onFileClick: (MainActivity.FileItem) -> Unit,
    onDelete: (MainActivity.FileItem) -> Unit,
    onCopy: (MainActivity.FileItem) -> Unit,
    onRename: (MainActivity.FileItem) -> Unit
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
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else Color.Gray
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
            IconButton(onClick = { onRename(file) }) {
                Icon(Icons.Default.Edit, contentDescription = "Adlandır", tint = Color.Gray)
            }
            IconButton(onClick = { onCopy(file) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Kopyala", tint = Color.Gray)
            }
            IconButton(onClick = { onDelete(file) }) {
                Icon(Icons.Default.Delete, contentDescription = "Sil", tint = Color.Red)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    showHiddenFiles: Boolean,
    onHiddenFilesChange: (Boolean) -> Unit,
    primaryColor: Color,
    onPrimaryColorChange: (Color) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Karanlık Mod", modifier = Modifier.weight(1f))
                Switch(checked = isDarkMode, onCheckedChange = onDarkModeChange)
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gizli Dosyaları Göster", modifier = Modifier.weight(1f))
                Switch(checked = showHiddenFiles, onCheckedChange = onHiddenFilesChange)
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Tema Rengi", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val colors = listOf(Color(0xFF6200EE), Color(0xFFF44336), Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFFF9800))
                colors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(color, CircleShape)
                            .clickable { onPrimaryColorChange(color) }
                            .padding(4.dp)
                    ) {
                        if (primaryColor == color) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                        }
                    }
                }
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
fun LixooTheme(darkTheme: Boolean = true, primaryColor: Color = Color(0xFF6200EE), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(primary = primaryColor)
    } else {
        lightColorScheme(primary = primaryColor)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
