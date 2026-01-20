package com.lixoo.explorer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
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
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class DialogType { FOLDER, FILE, ARCHIVE_FORMAT }
enum class Screen { EXPLORER, SETTINGS, EDITOR, PLAYER }

class MainActivity : ComponentActivity() {

    private var currentPath by mutableStateOf(Environment.getExternalStorageDirectory().absolutePath)
    private var filesList by mutableStateOf(listOf<FileItem>())
    private var isRootAvailable by mutableStateOf(false)

    // Navigation
    private var currentScreen by mutableStateOf(Screen.EXPLORER)
    private var activeFile by mutableStateOf<File?>(null)

    // Archive Preview State
    private var isArchivePreview by mutableStateOf(false)
    private var currentArchivePath by mutableStateOf("")
    private var archiveInternalPath by mutableStateOf("")

    // Selection States
    private val selectedItems = mutableStateListOf<FileItem>()
    private var isSelectionMode by mutableStateOf(false)
    private val clipboardFiles = mutableStateListOf<FileItem>()

    // Settings States (Persistent)
    private var showHiddenFiles by mutableStateOf(false)
    private var isDarkMode by mutableStateOf(true)
    private var primaryColor by mutableStateOf(Color(0xFF6200EE))

    private lateinit var prefs: SharedPreferences
    private var mediaPlayer: MediaPlayer? = null

    data class FileItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val formattedSize: String = "",
        val formattedDate: String = "",
        val icon: ImageVector = Icons.Default.FilePresent,
        val isArchive: Boolean = false,
        val isText: Boolean = false,
        val isAudio: Boolean = false
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
                    Box(modifier = Modifier.fillMaxSize()) {
                        Crossfade(targetState = currentScreen) { screen ->
                            when (screen) {
                                Screen.EXPLORER -> {
                                    FileExplorerScreen(
                                        currentPath = if (isArchivePreview) "$currentArchivePath/$archiveInternalPath" else currentPath,
                                        files = filesList,
                                        selectedItems = selectedItems,
                                        isSelectionMode = isSelectionMode,
                                        isArchivePreview = isArchivePreview,
                                        clipboardCount = clipboardFiles.size,
                                        onFileClick = { handleFileClick(it) },
                                        onFileLongClick = { item ->
                                            if (!isSelectionMode && !isArchivePreview) {
                                                isSelectionMode = true
                                                toggleSelection(item)
                                            }
                                        },
                                        onBack = { handleBack() },
                                        onDelete = {
                                            val targets = if (isSelectionMode) selectedItems.toList() else listOf(it)
                                            deleteFiles(targets)
                                            clearSelection()
                                        },
                                        onRootToggle = {
                                            if (!isArchivePreview) {
                                                currentPath = if (currentPath == "/") Environment.getExternalStorageDirectory().absolutePath else "/"
                                                refreshFiles()
                                            }
                                        },
                                        onCopy = {
                                            val targets = if (isSelectionMode) selectedItems.toList() else listOf(it)
                                            clipboardFiles.clear()
                                            clipboardFiles.addAll(targets)
                                            Toast.makeText(this@MainActivity, "${targets.size} öğe kopyalandı", Toast.LENGTH_SHORT).show()
                                            clearSelection()
                                        },
                                        onPaste = { pasteFiles() },
                                        onCreateFolder = { createFolder(it) },
                                        onCreateFile = { createFile(it) },
                                        onRename = { item, newName ->
                                            renameFile(item, newName)
                                            clearSelection()
                                        },
                                        onOpenSettings = { currentScreen = Screen.SETTINGS },
                                        onArchive = { format ->
                                            createArchive(selectedItems.toList(), format)
                                            clearSelection()
                                        },
                                        onExtract = { item ->
                                            if (isArchivePreview) extractSelective(item)
                                            else extractArchive(item)
                                            clearSelection()
                                        }
                                    )
                                }
                                Screen.SETTINGS -> {
                                    SettingsScreen(
                                        isDarkMode = isDarkMode,
                                        onDarkModeChange = { isDarkMode = it; saveSettings() },
                                        showHiddenFiles = showHiddenFiles,
                                        onHiddenFilesChange = { showHiddenFiles = it; saveSettings(); refreshFiles() },
                                        primaryColor = primaryColor,
                                        onPrimaryColorChange = { primaryColor = it; saveSettings() },
                                        onBack = { currentScreen = Screen.EXPLORER }
                                    )
                                }
                                Screen.EDITOR -> {
                                    activeFile?.let { file ->
                                        TextEditorScreen(
                                            file = file,
                                            onSave = { saveTextFile(file, it) },
                                            onBack = { currentScreen = Screen.EXPLORER }
                                        )
                                    }
                                }
                                Screen.PLAYER -> {
                                    activeFile?.let { file ->
                                        SoundPlayerScreen(
                                            file = file,
                                            onBack = { stopAudio(); currentScreen = Screen.EXPLORER }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    BackHandler { handleBack() }
                }
            }
        }
    }

    private fun handleFileClick(item: FileItem) {
        if (isSelectionMode) {
            toggleSelection(item)
        } else {
            if (item.isArchive && !isArchivePreview) {
                enterArchivePreview(item.path)
            } else if (item.isDirectory) {
                if (isArchivePreview) {
                    if (item.path.isEmpty()) {
                        archiveInternalPath += if (archiveInternalPath.isEmpty()) item.name else "/${item.name}"
                        refreshFiles()
                    }
                } else {
                    currentPath = item.path
                    refreshFiles()
                }
            } else if (item.isText && !isArchivePreview) {
                activeFile = File(item.path)
                currentScreen = Screen.EDITOR
            } else if (item.isAudio && !isArchivePreview) {
                activeFile = File(item.path)
                currentScreen = Screen.PLAYER
            } else {
                if (!isArchivePreview) openFile(File(item.path))
            }
        }
    }

    private fun handleBack() {
        when (currentScreen) {
            Screen.SETTINGS, Screen.EDITOR -> currentScreen = Screen.EXPLORER
            Screen.PLAYER -> { stopAudio(); currentScreen = Screen.EXPLORER }
            Screen.EXPLORER -> {
                if (isSelectionMode) clearSelection()
                else if (isArchivePreview) {
                    if (archiveInternalPath.isEmpty()) {
                        isArchivePreview = false
                        currentArchivePath = ""
                        refreshFiles()
                    } else {
                        archiveInternalPath = archiveInternalPath.substringBeforeLast("/", "")
                        refreshFiles()
                    }
                } else {
                    val parent = File(currentPath).parent
                    if (parent != null && parent != File(currentPath).path && currentPath != "/") {
                        currentPath = parent
                        refreshFiles()
                    } else if (currentPath != "/") {
                        currentPath = "/"
                        refreshFiles()
                    } else finish()
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
        if (selectedItems.any { it.path == item.path && it.name == item.name }) {
            selectedItems.removeAll { it.path == item.path && it.name == item.name }
        } else selectedItems.add(item)
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
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                if (isArchivePreview) loadArchiveFiles() else loadLocalFiles()
            }
            filesList = items
        }
    }

    private fun loadLocalFiles(): List<FileItem> {
        val file = File(currentPath)
        val items = mutableListOf<FileItem>()
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        val listFiles = file.listFiles()
        if (listFiles != null) {
            listFiles.forEach {
                if (showHiddenFiles || !it.name.startsWith(".")) {
                    val ext = it.extension.lowercase()
                    val isArchive = ext in listOf("zip", "tar", "7z", "gz", "bz2", "xz", "lz4", "tgz", "tbz2")
                    val isText = ext in listOf("txt", "log", "conf", "xml", "json", "sh", "prop")
                    val isAudio = ext in listOf("mp3", "wav", "ogg", "m4a", "flac")
                    val icon = if (it.isDirectory) Icons.Default.Folder else IconUtils.getIconForExtension(ext)

                    items.add(FileItem(
                        it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified(),
                        formattedSize = if (it.isDirectory) "" else formatSize(it.length()),
                        formattedDate = sdf.format(Date(it.lastModified())),
                        icon = icon, isArchive = isArchive, isText = isText, isAudio = isAudio
                    ))
                }
            }
        } else if (currentPath.startsWith("/") && isRootAvailable) {
            RootUtils.runCommand("ls -F $currentPath").forEach { line ->
                if (line.isNotBlank()) {
                    val isDir = line.endsWith("/")
                    val name = line.removeSuffix("/")
                    if (showHiddenFiles || !name.startsWith(".")) {
                        items.add(FileItem(name, "$currentPath/$name".replace("//", "/"), isDir, 0, 0, icon = if (isDir) Icons.Default.Folder else Icons.Default.FilePresent))
                    }
                }
            }
        }
        return items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun loadArchiveFiles(): List<FileItem> {
        val archiveFile = File(currentArchivePath)
        val entries = try { ArchiveUtils.listContents(archiveFile) } catch (e: Exception) { emptyList() }
        val items = mutableListOf<FileItem>()
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val seenFolders = mutableSetOf<String>()

        entries.forEach { entry ->
            val fullName = entry.name.removeSuffix("/")
            val isImmediateChild = if (archiveInternalPath.isEmpty()) !fullName.contains("/")
            else fullName.startsWith("$archiveInternalPath/") && !fullName.substringAfter("$archiveInternalPath/").contains("/")

            if (isImmediateChild) {
                val displayName = if (archiveInternalPath.isEmpty()) fullName else fullName.substringAfter("$archiveInternalPath/")
                if (displayName.isNotBlank()) {
                    val icon = if (entry.isDirectory) Icons.Default.Folder else IconUtils.getIconForExtension(File(displayName).extension)
                    items.add(FileItem(
                        displayName, entry.name, entry.isDirectory, entry.size, entry.lastModified,
                        formattedSize = if (entry.isDirectory) "" else formatSize(entry.size),
                        formattedDate = sdf.format(Date(entry.lastModified)),
                        icon = icon
                    ))
                }
            } else if (fullName.startsWith(if (archiveInternalPath.isEmpty()) "" else "$archiveInternalPath/")) {
                val relative = if (archiveInternalPath.isEmpty()) fullName else fullName.substringAfter("$archiveInternalPath/")
                val folderName = relative.substringBefore("/")
                if (folderName.isNotBlank() && seenFolders.add(folderName)) {
                    items.add(FileItem(folderName, "", true, 0, 0, icon = Icons.Default.Folder))
                }
            }
        }
        return items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun enterArchivePreview(path: String) {
        isArchivePreview = true
        currentArchivePath = path
        archiveInternalPath = ""
        refreshFiles()
    }

    private fun createFolder(name: String) {
        val newFolder = File(currentPath, name)
        if (newFolder.mkdir()) { refreshFiles() }
        else if (isRootAvailable) { RootUtils.runCommand("mkdir -p \"${newFolder.absolutePath}\""); refreshFiles() }
    }

    private fun createFile(name: String) {
        val newFile = File(currentPath, name)
        try { if (newFile.createNewFile()) refreshFiles() }
        catch (e: Exception) { if (isRootAvailable) RootUtils.runCommand("touch \"${newFile.absolutePath}\"").also { refreshFiles() } }
    }

    private fun renameFile(item: FileItem, newName: String) {
        val source = File(item.path)
        val destination = File(source.parent, newName)
        if (source.renameTo(destination)) refreshFiles()
        else if (isRootAvailable) { RootUtils.runCommand("mv \"${source.absolutePath}\" \"${destination.absolutePath}\""); refreshFiles() }
    }

    private fun deleteFiles(items: List<FileItem>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                items.forEach { item ->
                    val file = File(item.path)
                    if (!file.deleteRecursively() && isRootAvailable) RootUtils.runCommand("rm -rf \"${item.path}\"")
                }
            }
            refreshFiles()
        }
    }

    private fun pasteFiles() {
        val targets = clipboardFiles.toList()
        if (targets.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                targets.forEach { source ->
                    val dest = File(currentPath, source.name)
                    try {
                        if (source.isDirectory) File(source.path).copyRecursively(dest, overwrite = true)
                        else File(source.path).copyTo(dest, overwrite = true)
                    } catch (e: Exception) {
                        if (isRootAvailable) RootUtils.runCommand("cp -rf \"${source.path}\" \"${dest.absolutePath}\"")
                    }
                }
            }
            clipboardFiles.clear()
            refreshFiles()
        }
    }

    private fun createArchive(items: List<FileItem>, format: String) {
        val outputName = (if (items.size == 1) items.first().name else "arsiv") + "." + format
        val outputFile = File(currentPath, outputName)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { try { ArchiveUtils.compress(items.map { File(it.path) }, outputFile, format) } catch (e: Exception) {} }
            refreshFiles()
        }
    }

    private fun extractArchive(item: FileItem) {
        val archiveFile = File(item.path)
        val outputDir = File(archiveFile.parent, archiveFile.nameWithoutExtension)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { try { ArchiveUtils.extract(archiveFile, outputDir) } catch (e: Exception) {} }
            refreshFiles()
        }
    }

    private fun extractSelective(item: FileItem) {
        val archiveFile = File(currentArchivePath)
        val outputDir = File(archiveFile.parent, "extracted_${archiveFile.nameWithoutExtension}")
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { try { ArchiveUtils.extract(archiveFile, outputDir, item.path) } catch (e: Exception) {} }
            Toast.makeText(this@MainActivity, "Çıkartıldı: ${outputDir.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveTextFile(file: File, content: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try { file.writeText(content) } catch (e: Exception) {
                    if (isRootAvailable) {
                        val tmp = File(cacheDir, "tmp_txt").apply { writeText(content) }
                        RootUtils.runCommand("cp \"${tmp.absolutePath}\" \"${file.absolutePath}\"")
                    }
                }
            }
            Toast.makeText(this@MainActivity, "Kaydedildi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAudio() { mediaPlayer?.release(); mediaPlayer = null }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Aç"))
        } catch (e: Exception) { Toast.makeText(this, "Açılamadı", Toast.LENGTH_SHORT).show() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    currentPath: String, files: List<MainActivity.FileItem>,
    selectedItems: List<MainActivity.FileItem>, isSelectionMode: Boolean,
    isArchivePreview: Boolean, clipboardCount: Int,
    onFileClick: (MainActivity.FileItem) -> Unit, onFileLongClick: (MainActivity.FileItem) -> Unit,
    onBack: () -> Unit, onDelete: (MainActivity.FileItem) -> Unit,
    onRootToggle: () -> Unit, onCopy: (MainActivity.FileItem) -> Unit,
    onPaste: () -> Unit, onCreateFolder: (String) -> Unit,
    onCreateFile: (String) -> Unit, onRename: (MainActivity.FileItem, String) -> Unit,
    onOpenSettings: () -> Unit, onArchive: (String) -> Unit,
    onExtract: (MainActivity.FileItem) -> Unit
) {
    var showDialog by remember { mutableStateOf<DialogType?>(null) }
    var renameTarget by remember { mutableStateOf<MainActivity.FileItem?>(null) }
    var inputName by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    if (showDialog == DialogType.FOLDER || showDialog == DialogType.FILE) {
        AlertDialog(
            onDismissRequest = { showDialog = null },
            title = { Text(if (showDialog == DialogType.FOLDER) "Yeni Klasör" else "Yeni Dosya") },
            text = { TextField(value = inputName, onValueChange = { inputName = it }) },
            confirmButton = { Button(onClick = { if (showDialog == DialogType.FOLDER) onCreateFolder(inputName) else onCreateFile(inputName); showDialog = null; inputName = "" }) { Text("Tamam") } }
        )
    }

    if (showDialog == DialogType.ARCHIVE_FORMAT) {
        AlertDialog(
            onDismissRequest = { showDialog = null },
            title = { Text("Format") },
            text = {
                Column {
                    listOf("zip", "7z", "tar", "tar.gz", "tar.xz", "tar.lz4", "gz", "bz2", "xz", "lz4").forEach { format ->
                        Text(format.uppercase(), modifier = Modifier.fillMaxWidth().clickable { onArchive(format); showDialog = null }.padding(12.dp))
                    }
                }
            },
            confirmButton = {}
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
                title = { Text(currentPath, maxLines = 1, fontSize = 14.sp, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(if (isSelectionMode) Icons.Default.Close else Icons.Default.ArrowBack, null) } },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = { showDialog = DialogType.ARCHIVE_FORMAT }) { Icon(Icons.Default.Inventory, null) }
                        IconButton(onClick = { onCopy(selectedItems.first()) }) { Icon(Icons.Default.ContentCopy, null) }
                        IconButton(onClick = { onDelete(selectedItems.first()) }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                    } else if (!isArchivePreview) {
                        if (clipboardCount > 0) IconButton(onClick = onPaste) { BadgedBox(badge = { Badge { Text("$clipboardCount") } }) { Icon(Icons.Default.ContentPaste, null) } }
                        IconButton(onClick = { showDialog = DialogType.FILE; inputName = "yeni.txt" }) { Icon(Icons.Default.NoteAdd, null) }
                        IconButton(onClick = { showDialog = DialogType.FOLDER; inputName = "" }) { Icon(Icons.Default.CreateNewFolder, null) }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, null) }
                        IconButton(onClick = onRootToggle) { Icon(Icons.Default.Shield, null) }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.padding(padding)) {
            items(files, key = { it.path + it.name }) { file ->
                FileRow(
                    file = file,
                    isSelected = selectedItems.any { it.path == file.path && it.name == file.name },
                    isArchivePreview = isArchivePreview,
                    onFileClick = { onFileClick(file) },
                    onFileLongClick = { onFileLongClick(file) },
                    onRename = { renameTarget = it },
                    onExtract = { onExtract(file) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    file: MainActivity.FileItem, isSelected: Boolean, isArchivePreview: Boolean,
    onFileClick: () -> Unit, onFileLongClick: () -> Unit,
    onRename: (MainActivity.FileItem) -> Unit, onExtract: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = onFileClick, onLongClick = onFileLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else if (file.isArchive) Color(0xFFFF9800) else if (file.isAudio) Color(0xFF4CAF50) else Color.Gray
        Icon(file.icon, null, tint = tint, modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!file.isDirectory) Text("${file.formattedSize} • ${file.formattedDate}", fontSize = 11.sp, color = Color.Gray)
        }
        if (!isSelected) {
            if (file.isArchive || isArchivePreview) IconButton(onClick = onExtract) { Icon(Icons.Default.Unarchive, null, modifier = Modifier.size(18.dp), tint = Color.Gray) }
            if (!isArchivePreview) IconButton(onClick = { onRename(file) }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp), tint = Color.Gray) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(file: File, onSave: (String) -> Unit, onBack: () -> Unit) {
    var content by remember { mutableStateOf("") }
    LaunchedEffect(file) { content = withContext(Dispatchers.IO) { try { file.readText() } catch (e: Exception) { "" } } }
    Scaffold(
        topBar = { TopAppBar(title = { Text(file.name, maxLines = 1, fontSize = 14.sp) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }, actions = { IconButton(onClick = { onSave(content) }) { Icon(Icons.Default.Save, null) } }) }
    ) { padding ->
        TextField(value = content, onValueChange = { content = it }, modifier = Modifier.fillMaxSize().padding(padding), colors = TextFieldDefaults.textFieldColors(containerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundPlayerScreen(file: File, onBack: () -> Unit) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val mediaPlayer = remember { MediaPlayer.create(context, Uri.fromFile(file)) }
    LaunchedEffect(isPlaying) { while (isPlaying) { progress = mediaPlayer.currentPosition.toFloat() / mediaPlayer.duration; delay(500) } }
    DisposableEffect(Unit) { onDispose { mediaPlayer.release() } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Müzik Çalar") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(24.dp))
            Text(file.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
            Slider(value = progress, onValueChange = {}, modifier = Modifier.padding(32.dp))
            Row { IconButton(onClick = { if (isPlaying) mediaPlayer.pause() else mediaPlayer.start(); isPlaying = !isPlaying }, modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(32.dp)) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(isDarkMode: Boolean, onDarkModeChange: (Boolean) -> Unit, showHiddenFiles: Boolean, onHiddenFilesChange: (Boolean) -> Unit, primaryColor: Color, onPrimaryColorChange: (Color) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Ayarlar") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            SettingsToggle("Karanlık Mod", isDarkMode, onDarkModeChange)
            SettingsToggle("Gizli Dosyalar", showHiddenFiles, onHiddenFilesChange)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Tema Rengi", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Color(0xFF6200EE), Color(0xFFF44336), Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFE91E63)).forEach { color ->
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(color).clickable { onPrimaryColorChange(color) }) { if (primaryColor == color) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.Center)) }
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
