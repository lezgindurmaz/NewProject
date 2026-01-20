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
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class DialogType { FOLDER, FILE, ARCHIVE_FORMAT, DISK_IMAGE_FORMAT }
enum class Screen { EXPLORER, SETTINGS, EDITOR, PLAYER, HTML_VIEWER }

class MainActivity : ComponentActivity() {

    companion object {
        private val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    }

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
    private var isCutMode by mutableStateOf(false)

    // Settings States (Persistent)
    private var showHiddenFiles by mutableStateOf(false)
    private var isDarkMode by mutableStateOf(true)
    private var primaryColor by mutableStateOf(Color(0xFF6200EE))

    // Loading State
    private var loadingMessage by mutableStateOf<String?>(null)
    private var refreshJob: kotlinx.coroutines.Job? = null

    // Search State
    private var searchQuery by mutableStateOf("")
    private var isSearchActive by mutableStateOf(false)
    private val searchResults = mutableStateListOf<FileItem>()

    private lateinit var prefs: SharedPreferences

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
        val isAudio: Boolean = false,
        val isHtml: Boolean = false
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
                                        loadingMessage = loadingMessage,
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
                                            isCutMode = false
                                            Toast.makeText(this@MainActivity, "${targets.size} öğe kopyalandı", Toast.LENGTH_SHORT).show()
                                            clearSelection()
                                        },
                                        onCut = {
                                            val targets = if (isSelectionMode) selectedItems.toList() else listOf(it)
                                            clipboardFiles.clear()
                                            clipboardFiles.addAll(targets)
                                            isCutMode = true
                                            Toast.makeText(this@MainActivity, "${targets.size} öğe kesildi", Toast.LENGTH_SHORT).show()
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
                                        onDiskImage = { format, fs ->
                                            createDiskImage(selectedItems.toList(), format, fs)
                                            clearSelection()
                                        },
                                        onExtract = { item ->
                                            if (isArchivePreview) extractSelective(item)
                                            else extractArchive(item)
                                            clearSelection()
                                        },
                                        onRequestDataPermission = { requestDataPermission() },
                                        clipboardFiles = clipboardFiles,
                                        isCutMode = isCutMode,
                                        searchQuery = searchQuery,
                                        searchResults = searchResults,
                                        isSearchActive = isSearchActive,
                                        onSearchQueryChange = {
                                            searchQuery = it
                                            refreshFiles()
                                        },
                                        onSearchToggle = {
                                            isSearchActive = !isSearchActive
                                            if (!isSearchActive) {
                                                searchQuery = ""
                                                refreshFiles()
                                            }
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
                                Screen.HTML_VIEWER -> {
                                    activeFile?.let { file ->
                                        HtmlViewerScreen(
                                            file = file,
                                            onBack = { currentScreen = Screen.EXPLORER }
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
                    isSearchActive = false
                    searchQuery = ""
                    currentPath = item.path
                    refreshFiles()
                }
            } else if (item.isText && !isArchivePreview) {
                activeFile = File(item.path)
                currentScreen = Screen.EDITOR
            } else if (item.isAudio && !isArchivePreview) {
                activeFile = File(item.path)
                currentScreen = Screen.PLAYER
            } else if (item.isHtml && !isArchivePreview) {
                activeFile = File(item.path)
                currentScreen = Screen.HTML_VIEWER
            } else {
                if (!isArchivePreview) openFile(File(item.path))
            }
        }
    }

    private fun handleBack() {
        when (currentScreen) {
            Screen.SETTINGS, Screen.EDITOR, Screen.HTML_VIEWER -> currentScreen = Screen.EXPLORER
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

    private fun requestDataPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata")
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            }
            dataPermissionLauncher.launch(intent)
        }
    }

    private val dataPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                refreshFiles()
            }
        }
    }

    private fun refreshFiles() {
        refreshJob?.cancel()
        if (isSearchActive && searchQuery.isNotBlank()) {
            startRecursiveSearch(searchQuery)
        } else {
            refreshJob = lifecycleScope.launch {
                val items = withContext(Dispatchers.IO) {
                    if (isArchivePreview) loadArchiveFiles() else loadLocalFiles()
                }
                filesList = items
            }
        }
    }

    private fun startRecursiveSearch(query: String) {
        searchResults.clear()
        refreshJob = lifecycleScope.launch {
            loadingMessage = "Aranıyor..."
            withContext(Dispatchers.IO) {
                searchRecursive(File(currentPath), query)
            }
            loadingMessage = null
        }
    }

    private suspend fun searchRecursive(dir: File, query: String, depth: Int = 0) {
        if (depth > 15) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            kotlinx.coroutines.yield()
            if (file.name.contains(query, ignoreCase = true)) {
                val item = createFileItem(file)
                lifecycleScope.launch(Dispatchers.Main) {
                    searchResults.add(item)
                }
            }
            if (file.isDirectory) searchRecursive(file, query, depth + 1)
        }
    }

    private fun createFileItem(file: File): FileItem {
        val ext = file.extension.lowercase()
        val isArchive = ext in listOf("zip", "tar", "7z", "gz", "bz2", "xz", "lz4", "tgz", "tbz2", "iso", "img", "qcow2")
        val isText = ext in listOf("txt", "log", "conf", "xml", "json", "sh", "prop")
        val isAudio = ext in listOf("mp3", "wav", "ogg", "m4a", "flac")
        val isHtml = ext in listOf("html", "htm")
        val icon = if (file.isDirectory) Icons.Default.Folder else IconUtils.getIconForExtension(ext)

        return FileItem(
            file.name, file.absolutePath, file.isDirectory, file.length(), file.lastModified(),
            formattedSize = if (file.isDirectory) "" else formatSize(file.length()),
            formattedDate = sdf.format(Date(file.lastModified())),
            icon = icon, isArchive = isArchive, isText = isText, isAudio = isAudio, isHtml = isHtml
        )
    }

    private fun loadLocalFiles(): List<FileItem> {
        val file = File(currentPath)
        val items = mutableListOf<FileItem>()

        // Handle Android/data via SAF if needed
        if (currentPath.contains("/Android/data") && !isRootAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val treeUri = contentResolver.persistedUriPermissions.find { it.uri.toString().contains("Android%2Fdata") }?.uri
            if (treeUri != null) {
                val relativePath = currentPath.substringAfter("Android/data")
                var currentDoc = DocumentFile.fromTreeUri(this, treeUri)
                if (relativePath.isNotEmpty()) {
                    relativePath.split("/").filter { it.isNotEmpty() }.forEach { part ->
                        currentDoc = currentDoc?.findFile(part)
                    }
                }
                currentDoc?.listFiles()?.forEach { doc ->
                    val name = doc.name ?: ""
                    val ext = name.substringAfterLast(".", "").lowercase()
                    val isArchive = ext in listOf("zip", "tar", "7z", "gz", "bz2", "xz", "lz4", "tgz", "tbz2", "iso", "img", "qcow2")
                    val isText = ext in listOf("txt", "log", "conf", "xml", "json", "sh", "prop")
                    val isAudio = ext in listOf("mp3", "wav", "ogg", "m4a", "flac")
                    val isHtml = ext in listOf("html", "htm")
                    val newPath = if (currentPath.endsWith("/")) currentPath + name else "$currentPath/$name"

                    items.add(FileItem(
                        name, newPath, doc.isDirectory, doc.length(), doc.lastModified(),
                        formattedSize = if (doc.isDirectory) "" else formatSize(doc.length()),
                        formattedDate = sdf.format(Date(doc.lastModified())),
                        icon = if (doc.isDirectory) Icons.Default.Folder else IconUtils.getIconForExtension(ext),
                        isArchive = isArchive, isText = isText, isAudio = isAudio, isHtml = isHtml
                    ))
                }
                return items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }
        }

        val normalFiles = file.listFiles()
        if (normalFiles != null) {
            normalFiles.asSequence().forEach {
                if (showHiddenFiles || !it.name.startsWith(".")) {
                    val ext = it.extension.lowercase()
                    val isArchive = ext in listOf("zip", "tar", "7z", "gz", "bz2", "xz", "lz4", "tgz", "tbz2", "iso", "img", "qcow2")
                    val isText = ext in listOf("txt", "log", "conf", "xml", "json", "sh", "prop")
                    val isAudio = ext in listOf("mp3", "wav", "ogg", "m4a", "flac")
                    val isHtml = ext in listOf("html", "htm")
                    val icon = if (it.isDirectory) Icons.Default.Folder else IconUtils.getIconForExtension(ext)

                    items.add(FileItem(
                        it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified(),
                        formattedSize = if (it.isDirectory) "" else formatSize(it.length()),
                        formattedDate = sdf.format(Date(it.lastModified())),
                        icon = icon, isArchive = isArchive, isText = isText, isAudio = isAudio, isHtml = isHtml
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
        if (!ArchiveUtils.isCached(archiveFile)) {
            loadingMessage = "Dosya açılıyor..."
        }
        val entries = try { ArchiveUtils.listContents(archiveFile) } catch (e: Exception) { emptyList() }
        val items = mutableListOf<FileItem>()
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val seenFolders = mutableSetOf<String>()
        val prefix = if (archiveInternalPath.isEmpty()) "" else "$archiveInternalPath/"
        val prefixLen = prefix.length

        entries.forEach { entry ->
            val fullName = entry.name.removeSuffix("/")

            if (fullName.length > prefixLen && fullName.startsWith(prefix)) {
                val relative = fullName.substring(prefixLen)
                val firstSlash = relative.indexOf('/')
                val isDir = firstSlash != -1 || entry.isDirectory
                val name = if (firstSlash != -1) relative.substring(0, firstSlash) else relative

                if (isDir) {
                    if (seenFolders.add(name)) {
                        items.add(FileItem(name, "", true, 0, 0, icon = Icons.Default.Folder))
                    }
                } else {
                    items.add(FileItem(
                        name, entry.name, false, entry.size, entry.lastModified,
                        formattedSize = formatSize(entry.size),
                        formattedDate = sdf.format(Date(entry.lastModified)),
                        icon = IconUtils.getIconForExtension(name.substringAfterLast(".", ""))
                    ))
                }
            }
        }
        loadingMessage = null
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
        ArchiveUtils.clearCache()
        if (source.renameTo(destination)) refreshFiles()
        else if (isRootAvailable) { RootUtils.runCommand("mv \"${source.absolutePath}\" \"${destination.absolutePath}\""); refreshFiles() }
    }

    private fun deleteFiles(items: List<FileItem>) {
        lifecycleScope.launch {
            loadingMessage = "Siliniyor..."
            ArchiveUtils.clearCache()
            withContext(Dispatchers.IO) {
                items.forEach { item ->
                    val file = File(item.path)
                    if (!file.deleteRecursively() && isRootAvailable) RootUtils.runCommand("rm -rf \"${item.path}\"")
                }
            }
            loadingMessage = null
            refreshFiles()
        }
    }

    private fun createDiskImage(items: List<FileItem>, format: String, fs: String) {
        val outputName = (if (items.size == 1) items.first().name else "disk") + "." + format
        val outputFile = File(currentPath, outputName)
        lifecycleScope.launch {
            loadingMessage = "Disk kalıbı oluşturuluyor ($fs)..."
            withContext(Dispatchers.IO) {
                try { ArchiveUtils.compress(items.map { File(it.path) }, outputFile, format) } catch (e: Exception) {}
            }
            loadingMessage = null
            refreshFiles()
        }
    }

    private fun pasteFiles() {
        val targets = clipboardFiles.toList()
        if (targets.isEmpty()) return
        val isCut = isCutMode
        ArchiveUtils.clearCache()
        lifecycleScope.launch {
            loadingMessage = if (isCut) "Taşınıyor..." else "Yapıştırılıyor..."
            withContext(Dispatchers.IO) {
                targets.forEach { source ->
                    val dest = File(currentPath, source.name)
                    try {
                        if (isCut) {
                            if (!File(source.path).renameTo(dest)) {
                                if (source.isDirectory) File(source.path).copyRecursively(dest, overwrite = true)
                                else File(source.path).copyTo(dest, overwrite = true)
                                File(source.path).deleteRecursively()
                            }
                        } else {
                            if (source.isDirectory) File(source.path).copyRecursively(dest, overwrite = true)
                            else File(source.path).copyTo(dest, overwrite = true)
                        }
                    } catch (e: Exception) {
                        if (isRootAvailable) {
                            val cmd = if (isCut) "mv" else "cp -rf"
                            RootUtils.runCommand("$cmd \"${source.path}\" \"${dest.absolutePath}\"")
                        }
                    }
                }
            }
            clipboardFiles.clear()
            isCutMode = false
            loadingMessage = null
            refreshFiles()
        }
    }

    private fun createArchive(items: List<FileItem>, format: String) {
        val outputName = (if (items.size == 1) items.first().name else "arsiv") + "." + format
        val outputFile = File(currentPath, outputName)
        lifecycleScope.launch {
            loadingMessage = "Arşivleniyor..."
            withContext(Dispatchers.IO) {
                try { ArchiveUtils.compress(items.map { File(it.path) }, outputFile, format) } catch (e: Exception) {}
            }
            loadingMessage = null
            refreshFiles()
        }
    }

    private fun extractArchive(item: FileItem) {
        val archiveFile = File(item.path)
        val outputDir = File(archiveFile.parent, archiveFile.nameWithoutExtension)
        lifecycleScope.launch {
            loadingMessage = "Çıkartılıyor..."
            withContext(Dispatchers.IO) {
                try { ArchiveUtils.extract(archiveFile, outputDir) } catch (e: Exception) {}
            }
            loadingMessage = null
            refreshFiles()
        }
    }

    private fun extractSelective(item: FileItem) {
        val archiveFile = File(currentArchivePath)
        val outputDir = File(archiveFile.parent, "extracted_${archiveFile.nameWithoutExtension}")
        lifecycleScope.launch {
            loadingMessage = "Öğe çıkartılıyor..."
            withContext(Dispatchers.IO) {
                try { ArchiveUtils.extract(archiveFile, outputDir, item.path) } catch (e: Exception) {}
            }
            loadingMessage = null
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

    private fun stopAudio() { /* Audio is handled by SoundPlayerScreen's DisposableEffect */ }

    private fun openFile(file: File) {
        try {
            val uri = if (file.absolutePath.startsWith("content://")) {
                Uri.parse(file.absolutePath)
            } else {
                FileProvider.getUriForFile(this, "$packageName.provider", file)
            }
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
    isArchivePreview: Boolean, clipboardCount: Int, loadingMessage: String?,
    onFileClick: (MainActivity.FileItem) -> Unit, onFileLongClick: (MainActivity.FileItem) -> Unit,
    onBack: () -> Unit, onDelete: (MainActivity.FileItem) -> Unit,
    onRootToggle: () -> Unit, onCopy: (MainActivity.FileItem) -> Unit,
    onCut: (MainActivity.FileItem) -> Unit,
    onPaste: () -> Unit, onCreateFolder: (String) -> Unit,
    onCreateFile: (String) -> Unit, onRename: (MainActivity.FileItem, String) -> Unit,
    onOpenSettings: () -> Unit, onArchive: (String) -> Unit,
    onDiskImage: (String, String) -> Unit,
    onExtract: (MainActivity.FileItem) -> Unit,
    onRequestDataPermission: () -> Unit,
    clipboardFiles: List<MainActivity.FileItem>,
    isCutMode: Boolean,
    searchQuery: String,
    searchResults: List<MainActivity.FileItem>,
    isSearchActive: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit
) {
    var showDialog by remember { mutableStateOf<DialogType?>(null) }
    var renameTarget by remember { mutableStateOf<MainActivity.FileItem?>(null) }
    var inputName by remember { mutableStateOf("") }
    var showAddMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val displayedFiles = if (isSearchActive && searchQuery.isNotBlank()) searchResults else files

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
            title = { Text("Arşiv Formatı") },
            text = {
                Column {
                    listOf("zip", "7z", "tar", "tar.gz", "tar.xz", "tar.lz4", "gz", "bz2", "xz", "lz4", "iso").forEach { format ->
                        Text(format.uppercase(), modifier = Modifier.fillMaxWidth().clickable { onArchive(format); showDialog = null }.padding(12.dp))
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showDialog == DialogType.DISK_IMAGE_FORMAT) {
        var selectedFormat by remember { mutableStateOf("iso") }
        var selectedFS by remember { mutableStateOf("ISO9660") }
        var volumeLabel by remember { mutableStateOf("LIXOO_DISK") }

        AlertDialog(
            onDismissRequest = { showDialog = null },
            title = { Text("Yeni Disk Kalıbı") },
            text = {
                Column {
                    Text("Disk Etiketi:", fontWeight = FontWeight.Bold)
                    TextField(value = volumeLabel, onValueChange = { volumeLabel = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Format:", fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("iso").forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { selectedFormat = f }.padding(4.dp)) {
                                RadioButton(selected = selectedFormat == f, onClick = { selectedFormat = f })
                                Text(f.uppercase())
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Dosya Sistemi:", fontWeight = FontWeight.Bold)
                    Column {
                        val fsOptions = when (selectedFormat) {
                            "iso" -> listOf("ISO9660", "UDF", "Joliet")
                            else -> listOf("ISO9660")
                        }
                        if (selectedFS !in fsOptions) selectedFS = fsOptions[0]

                        fsOptions.chunked(2).forEach { row ->
                            Row {
                                row.forEach { fs ->
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { selectedFS = fs }.padding(8.dp).weight(1f)) {
                                        RadioButton(selected = selectedFS == fs, onClick = { selectedFS = fs })
                                        Text(fs)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onDiskImage(selectedFormat, selectedFS); showDialog = null }) { Text("Oluştur") }
            }
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
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text(if (loadingMessage == "Aranıyor...") "Aranıyor... (${searchResults.size})" else "Ara...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = TextFieldDefaults.textFieldColors(containerColor = Color.Transparent)
                        )
                    } else {
                        Text(currentPath, maxLines = 1, fontSize = 14.sp, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = if (isSearchActive) onSearchToggle else onBack) { Icon(if (isSelectionMode || isSearchActive) Icons.Default.Close else Icons.Default.ArrowBack, null) } },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = { showDialog = DialogType.ARCHIVE_FORMAT }) { Icon(Icons.Default.Inventory, null) }
                        IconButton(onClick = { onCut(selectedItems.first()) }) { Icon(Icons.Default.ContentCut, null) }
                        IconButton(onClick = { onCopy(selectedItems.first()) }) { Icon(Icons.Default.ContentCopy, null) }
                        IconButton(onClick = { onDelete(selectedItems.first()) }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                    } else if (!isArchivePreview) {
                        if (!isSearchActive) IconButton(onClick = onSearchToggle) { Icon(Icons.Default.Search, null) }
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val hasDataPermission = remember(currentPath) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                context.contentResolver.persistedUriPermissions.any { perm -> perm.uri.toString().contains("Android%2Fdata") }
                            } else true
                        }
                        if (currentPath.endsWith("/Android/data") && !hasDataPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            IconButton(onClick = onRequestDataPermission) { Icon(Icons.Default.VpnKey, "İzin Al") }
                        }
                        if (clipboardCount > 0) IconButton(onClick = onPaste) { BadgedBox(badge = { Badge { Text("$clipboardCount") } }) { Icon(Icons.Default.ContentPaste, null) } }

                        Box {
                            IconButton(onClick = { showAddMenu = true }) { Icon(Icons.Default.Add, null) }
                            DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                                DropdownMenuItem(text = { Text("Yeni Dosya") }, onClick = { showAddMenu = false; showDialog = DialogType.FILE; inputName = "yeni.txt" }, leadingIcon = { Icon(Icons.Default.NoteAdd, null) })
                                DropdownMenuItem(text = { Text("Yeni Klasör") }, onClick = { showAddMenu = false; showDialog = DialogType.FOLDER; inputName = "" }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) })
                                DropdownMenuItem(text = { Text("Disk Kalıbı Oluştur") }, onClick = { showAddMenu = false; showDialog = DialogType.DISK_IMAGE_FORMAT }, leadingIcon = { Icon(Icons.Default.DiscFull, null) })
                            }
                        }

                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, null) }
                        IconButton(onClick = onRootToggle) { Icon(Icons.Default.Shield, null) }
                    }
                }
            )
        },
        bottomBar = {
            loadingMessage?.let {
                Surface(tonalElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.padding(padding)) {
            items(displayedFiles, key = { it.path + it.name }) { file ->
                val isInClipboard = clipboardFiles.any { it.path == file.path }
                FileRow(
                    file = file,
                    isSelected = selectedItems.any { it.path == file.path && it.name == file.name },
                    isArchivePreview = isArchivePreview,
                    isCut = isCutMode && isInClipboard,
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
    isCut: Boolean,
    onFileClick: () -> Unit, onFileLongClick: () -> Unit,
    onRename: (MainActivity.FileItem) -> Unit, onExtract: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val tint = remember(file.isDirectory, file.isArchive, file.isAudio, primary) {
        if (file.isDirectory) primary else if (file.isArchive) Color(0xFFFF9800) else if (file.isAudio) Color(0xFF4CAF50) else Color.Gray
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .alpha(if (isCut) 0.5f else 1f)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = onFileClick, onLongClick = onFileLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
fun HtmlViewerScreen(file: File, onBack: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(file.name, maxLines = 1, fontSize = 14.sp) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }
    ) { padding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webViewClient = WebViewClient()
                    settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                    }
                    loadUrl("file://${file.absolutePath}")
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        )
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
        val context = androidx.compose.ui.platform.LocalContext.current
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
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lezgindurmaz/NewProject/releases"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Update, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Güncellemeleri Kontrol Et")
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
    var s = size.toDouble()
    var unitIndex = 0
    while (s >= 1024 && unitIndex < units.size - 1) {
        s /= 1024
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", s, units[unitIndex])
}

@Composable
fun LixooTheme(darkTheme: Boolean, primaryColor: Color, content: @Composable () -> Unit) {
    val colorScheme = remember(darkTheme, primaryColor) {
        if (darkTheme) darkColorScheme(primary = primaryColor) else lightColorScheme(primary = primaryColor)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
