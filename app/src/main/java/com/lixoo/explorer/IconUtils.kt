package com.lixoo.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

object IconUtils {
    fun getIconForType(type: FileInspector.FileType, extension: String): ImageVector {
        return when (type) {
            FileInspector.FileType.IMAGE -> Icons.Default.Image
            FileInspector.FileType.VIDEO -> Icons.Default.Movie
            FileInspector.FileType.AUDIO -> Icons.Default.AudioFile
            FileInspector.FileType.ARCHIVE -> Icons.Default.Inventory
            FileInspector.FileType.DISK_IMAGE -> Icons.Default.Album
            FileInspector.FileType.PDF -> Icons.Default.PictureAsPdf
            FileInspector.FileType.TEXT -> Icons.Default.Description
            FileInspector.FileType.HTML -> Icons.Default.Html
            else -> getIconForExtension(extension)
        }
    }

    fun getIconForExtension(extension: String): ImageVector {
        return when (extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> Icons.Default.Image
            "mp4", "mkv", "avi", "3gp", "mov", "wmv", "flv" -> Icons.Default.Movie
            "mp3", "wav", "ogg", "m4a", "flac" -> Icons.Default.AudioFile
            "apk" -> Icons.Default.InstallMobile
            "zip", "7z", "tar", "gz", "bz2", "xz", "lz4", "tgz", "tbz2", "rar" -> Icons.Default.Inventory
            "iso", "img", "qcow2" -> Icons.Default.Album
            "pdf" -> Icons.Default.PictureAsPdf
            "doc", "docx", "txt", "log", "rtf", "odt", "sh", "prop", "conf" -> Icons.Default.Description
            "xls", "xlsx", "csv" -> Icons.Default.TableChart
            "ppt", "pptx" -> Icons.Default.Slideshow
            "html", "htm" -> Icons.Default.Html
            else -> Icons.Default.FilePresent
        }
    }
}
