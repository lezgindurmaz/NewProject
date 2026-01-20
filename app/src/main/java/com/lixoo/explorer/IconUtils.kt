package com.lixoo.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

object IconUtils {
    fun getIconForExtension(extension: String): ImageVector {
        return when (extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> Icons.Default.Image
            "mp4", "mkv", "avi", "3gp", "mov", "wmv", "flv" -> Icons.Default.Movie
            "mp3", "wav", "ogg", "m4a", "flac" -> Icons.Default.AudioFile
            "apk" -> Icons.Default.InstallMobile
            "zip", "7z", "tar", "gz", "bz2", "xz", "lz4", "tgz", "tbz2" -> Icons.Default.Inventory
            "pdf" -> Icons.Default.PictureAsPdf
            "doc", "docx", "txt", "log", "rtf", "odt" -> Icons.Default.Description
            "xls", "xlsx", "csv" -> Icons.Default.TableChart
            "ppt", "pptx" -> Icons.Default.Slideshow
            else -> Icons.Default.FilePresent
        }
    }
}
