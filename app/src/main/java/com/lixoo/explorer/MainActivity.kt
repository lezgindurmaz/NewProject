package com.lixoo.explorer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // Selection States
    private val selectedItems = mutableStateListOf<FileItem>()
    private var isSelectionMode by mutableStateOf(false)
    private val clipboardFiles = mutableStateListOf<FileItem>()

    // Settings States (Persistent)
    private var showHiddenFiles by mutableStateOf(false)
    private var isDarkMode by mutableStateOf(true)
    private var primaryColor by mutableStateOf(Color(0xFF6200EE))
    private var isSettingsOpen by mutableStateOf(false)

    private lateinit var prefs: SharedPreferences

    data class FileItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val formattedSize: String = "",
        val formattedDate: String = ""
    )

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("lixoo_prefs", Context.MODE_PRIVATE)
        loadSettings()

        isRootAvailable = RootUtils.isRootAvailable()
        checkPermissions()
        refreshFiles()

        setContent {
            LixooTheme(darkTheme = isDarkMode, primaryColor = primaryColor) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (isSettingsOpen) {
                        SettingsScreen(
                            isDarkMode = isDarkMode,
                            onDarkModeChange = {
                                isDarkMode = it
                                saveSettings()
                            },
                            showHiddenFiles = showHiddenFiles,
                            onHiddenFilesChange = {
                                showHiddenFiles = it
                                saveSettings()
                                refreshFiles()
                            },
                            primaryColor = primaryColor,
                            onPrimaryColorChange = {
                                primaryColor = it
                                saveSettings()
                            },
                            onBack = { isSettingsOpen = false }
                        )
                        BackHandler { isSettingsOpen = false }
                    } else {
                        FileExplorerScreen(
                            currentPath = currentPath,
                            files = filesList,
                            selectedItems = selectedItems,
                            isSelectionMode = isSelectionMode,
                            clipboardCount = clipboardFiles.size,
                            onFileClick = { item ->
                                if (isSelectionMode) {
                                    toggleSelection(item)
                                } else {
                                    if (item.isDirectory) {
                                        currentPath = item.path
                                        refreshFiles()
                                    } else {
                                        openFile(File(item.path))
                                    }
                                }
                            },
                            onFileLongClick = { item ->
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    toggleSelection(item)
                                }
                            },
                            onBack = {
                                if (isSelectionMode) {
                                    clearSelection()
                                } else {
                                    val parent = File(currentPath).parent
                                    if (parent != null && parent != File(currentPath).path) {
                                        currentPath = parent
                                        refreshFiles()
                                    }
                                }
                            },
                            onDelete = {
                                val targets = if (isSelectionMode) selectedItems.toList() else listOf(it)
                                deleteFiles(targets)
                                clearSelection()
                            },
                            onRootToggle = {
                                currentPath = if (currentPath == "/") Environment.getExternalStorageDirectory().absolutePath else "/"
                                refreshFiles()
                            },
                            onCopy = {
                                val targets = if (isSelectionMode) selectedItems.toList() else listOf(it)
                                clipboardFiles.clear()
                                clipboardFiles.addAll(targets)
                                Toast.makeText(this, "${targets.size} öğe kopyalandı", Toast.LENGTH_SHORT).show()
                                clearSelection()
                            },
                            onPaste = { pasteFiles() },
                            onCreateFolder = { createFolder(it) },
                            onCreateFile = { createFile(it) },
                            onRename = { item, newName ->
                                renameFile(item, newName)
                                clearSelection()
                            },
                            onOpenSettings = { isSettingsOpen = true },
                            onClearSelection = { clearSelection() }
                        )
                    }
                }
            }
        }
    }

    private fun loadSettings() {
        showHiddenFiles = prefs.getBoolean("show_hidden", false)
        isDarkMode = prefs.getBoolean("dark_mode", true)
        val colorInt = prefs.getInt("primary_color", Color(0xFF6200EE).toArgb())
        primaryColor = Color(colorInt)
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putBoolean("show_hidden", showHiddenFiles)
            putBoolean("dark_mode", isDarkMode)
            putInt("primary_color", primaryColor.toArgb())
            apply()
        }
    }

    private fun toggleSelection(item: FileItem) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item)
        } else {
            selectedItems.add(item)
        }
        if (selectedItems.isEmpty()) isSelectionMode = false
    }

    private fun clearSelection() {
        selectedItems.clear()
        isSelectionMode = false
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
            val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
                requestPermissionsLauncher.launch(permissions)
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshFiles() }

    private fun refreshFiles() {
        val file = File(currentPath)
        val items = mutableListOf<FileItem>()
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        val listFiles = file.listFiles()
        if (listFiles != null) {
            listFiles.forEach {
                if (showHiddenFiles || !it.name.startsWith(".")) {
                    items.add(FileItem(
                        it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified(),
                        formattedSize = if (it.isDirectory) "" else formatSize(it.length()),
                        formattedDate = sdf.format(Date(it.lastModified()))
                    ))
                }
            }
        } else if (currentPath.startsWith("/") && isRootAvailable) {
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
            refreshFiles()
        }
    }

    private fun createFile(name: String) {
        val newFile = File(currentPath, name)
        try {
            if (newFile.createNewFile()) {
                refreshFiles()
            } else if (isRootAvailable) {
                RootUtils.runCommand("touch \"${newFile.absolutePath}\"")
                refreshFiles()
            }
        } catch (e: Exception) {
            if (isRootAvailable) RootUtils.runCommand("touch \"${newFile.absolutePath}\"").also { refreshFiles() }
        }
    }

    private fun renameFile(item: FileItem, newName: String) {
        val source = File(item.path)
        val destination = File(source.parent, newName)
        if (source.renameTo(destination)) {
            refreshFiles()
        } else if (isRootAvailable) {
            RootUtils.runCommand("mv \"${source.absolutePath}\" \"${destination.absolutePath}\"")
            refreshFiles()
        }
    }

    private fun deleteFiles(items: List<FileItem>) {
        Thread {
            items.forEach { item ->
                val file = File(item.path)
                if (!file.deleteRecursively() && isRootAvailable) {
                    RootUtils.runCommand("rm -rf \"${item.path}\"")
                }
            }
            runOnUiThread { refreshFiles() }
        }.start()
    }

    private fun pasteFiles() {
        val targets = clipboardFiles.toList()
        if (targets.isEmpty()) return

        Thread {
            targets.forEach { source ->
                val dest = File(currentPath, source.name)
                try {
                    if (source.isDirectory) File(source.path).copyRecursively(dest, overwrite = true)
                    else File(source.path).copyTo(dest, overwrite = true)
                } catch (e: Exception) {
                    if (isRootAvailable) RootUtils.runCommand("cp -rf \"${source.path}\" \"${dest.absolutePath}\"")
                }
            }
            runOnUiThread {
                clipboardFiles.clear()
                refreshFiles()
                Toast.makeText(this, "İşlem tamamlandı", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Aç"))
        } catch (e: Exception) {
            Toast.makeText(this, "Açılamadı", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    currentPath: String,
    files: List<MainActivity.FileItem>,
    selectedItems: List<MainActivity.FileItem>,
    isSelectionMode: Boolean,
    clipboardCount: Int,
    onFileClick: (MainActivity.FileItem) -> Unit,
    onFileLongClick: (MainActivity.FileItem) -> Unit,
    onBack: () -> Unit,
    onDelete: (MainActivity.FileItem) -> Unit,
    onRootToggle: () -> Unit,
    onCopy: (MainActivity.FileItem) -> Unit,
    onPaste: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onRename: (MainActivity.FileItem, String) -> Unit,
    onOpenSettings: () -> Unit,
    onClearSelection: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf<DialogType?>(null) }
    var renameTarget by remember { mutableStateOf<MainActivity.FileItem?>(null) }
    var inputName by remember { mutableStateOf("") }

    if (showCreateDialog != null) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = null },
            title = { Text(if (showCreateDialog == DialogType.FOLDER) "Yeni Klasör" else "Yeni Dosya") },
            text = { TextField(value = inputName, onValueChange = { inputName = it }) },
            confirmButton = { Button(onClick = { if (showCreateDialog == DialogType.FOLDER) onCreateFolder(inputName) else onCreateFile(inputName); showCreateDialog = null; inputName = "" }) { Text("Tamam") } }
        )
    }

    if (renameTarget != null) {
        var newName by remember { mutableStateOf(renameTarget!!.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Ad Değiştir") },
            text = { TextField(value = newName, onValueChange = { newName = it }) },
            confirmButton = { Button(onClick = { onRename(renameTarget!!, newName); renameTarget = null }) { Text("Kaydet") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isSelectionMode) "${selectedItems.size} seçildi" else currentPath, maxLines = 1, fontSize = 16.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(if (isSelectionMode) Icons.Default.Close else Icons.Default.ArrowBack, null) } },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = { onCopy(selectedItems.first()) /* Multi copy simplified */ }) { Icon(Icons.Default.ContentCopy, null) }
                        IconButton(onClick = { onDelete(selectedItems.first()) }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                    } else {
                        if (clipboardCount > 0) {
                            IconButton(onClick = onPaste) { BadgedBox(badge = { Badge { Text("$clipboardCount") } }) { Icon(Icons.Default.ContentPaste, null) } }
                        }
                        IconButton(onClick = { showCreateDialog = DialogType.FILE; inputName = "yeni.txt" }) { Icon(Icons.Default.NoteAdd, null) }
                        IconButton(onClick = { showCreateDialog = DialogType.FOLDER; inputName = "" }) { Icon(Icons.Default.CreateNewFolder, null) }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, null) }
                        IconButton(onClick = onRootToggle) { Icon(Icons.Default.Shield, null) }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(files, key = { it.path }) { file ->
                FileRow(
                    file = file,
                    isSelected = selectedItems.contains(file),
                    onFileClick = { onFileClick(file) },
                    onFileLongClick = { onFileLongClick(file) },
                    onRename = { renameTarget = it }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    file: MainActivity.FileItem,
    isSelected: Boolean,
    onFileClick: () -> Unit,
    onFileLongClick: () -> Unit,
    onRename: (MainActivity.FileItem) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = onFileClick, onLongClick = onFileLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.FilePresent,
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, fontWeight = FontWeight.Medium, maxLines = 1)
            if (!file.isDirectory) {
                Text("${file.formattedSize} • ${file.formattedDate}", fontSize = 12.sp, color = Color.Gray)
            }
        }
        if (!isSelected) {
            IconButton(onClick = { onRename(file) }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp), tint = Color.Gray) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDarkMode: Boolean, onDarkModeChange: (Boolean) -> Unit,
    showHiddenFiles: Boolean, onHiddenFilesChange: (Boolean) -> Unit,
    primaryColor: Color, onPrimaryColorChange: (Color) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Ayarlar") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            SettingsToggle("Karanlık Mod", isDarkMode, onDarkModeChange)
            SettingsToggle("Gizli Dosyalar", showHiddenFiles, onHiddenFilesChange)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Tema Rengi", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Color(0xFF6200EE), Color(0xFFF44336), Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFE91E63)).forEach { color ->
                    Box(modifier = Modifier.size(40.dp).background(color, CircleShape).clickable { onPrimaryColorChange(color) }) {
                        if (primaryColor == color) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@Composable
fun LixooTheme(darkTheme: Boolean, primaryColor: Color, content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) darkColorScheme(primary = primaryColor) else lightColorScheme(primary = primaryColor)
    MaterialTheme(colorScheme = colorScheme, content = content)
}
