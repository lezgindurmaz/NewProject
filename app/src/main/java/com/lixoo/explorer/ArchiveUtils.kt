package com.lixoo.explorer

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.utils.IOUtils
import java.io.*

object ArchiveUtils {

    data class ArchiveEntryInfo(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )

    fun compress(files: List<File>, outputFile: File, format: String) {
        when (format.lowercase()) {
            "zip" -> compressZip(files, outputFile)
            "tar" -> compressTar(files, outputFile)
            "7z" -> compress7z(files, outputFile)
            else -> throw IllegalArgumentException("Unsupported format: $format")
        }
    }

    private fun compressZip(files: List<File>, outputFile: File) {
        ZipArchiveOutputStream(FileOutputStream(outputFile)).use { out ->
            files.forEach { file ->
                addFileToArchive(out, file, "")
            }
        }
    }

    private fun compressTar(files: List<File>, outputFile: File) {
        TarArchiveOutputStream(FileOutputStream(outputFile)).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            files.forEach { file ->
                addFileToArchive(out, file, "")
            }
        }
    }

    private fun <E : ArchiveEntry> addFileToArchive(out: ArchiveOutputStream<E>, file: File, base: String) {
        val entryName = base + file.name + (if (file.isDirectory) "/" else "")
        val entry = out.createArchiveEntry(file, entryName)
        out.putArchiveEntry(entry)
        if (file.isFile) {
            FileInputStream(file).use { input ->
                IOUtils.copy(input, out)
            }
        }
        out.closeArchiveEntry()
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                addFileToArchive(out, child, entryName)
            }
        }
    }

    private fun compress7z(files: List<File>, outputFile: File) {
        SevenZOutputFile(outputFile).use { out ->
            files.forEach { file ->
                addFileTo7z(out, file, "")
            }
        }
    }

    private fun addFileTo7z(out: SevenZOutputFile, file: File, base: String) {
        val entryName = base + file.name + (if (file.isDirectory) "/" else "")
        val entry = out.createArchiveEntry(file, entryName)
        out.putArchiveEntry(entry)
        if (file.isFile) {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    out.write(buffer, 0, len)
                }
            }
        }
        out.closeArchiveEntry()
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                addFileTo7z(out, child, entryName)
            }
        }
    }

    fun listContents(archiveFile: File): List<ArchiveEntryInfo> {
        val extension = archiveFile.extension.lowercase()
        return if (extension == "7z") {
            list7zContents(archiveFile)
        } else {
            listStandardArchiveContents(archiveFile, extension)
        }
    }

    private fun listStandardArchiveContents(file: File, format: String): List<ArchiveEntryInfo> {
        val contents = mutableListOf<ArchiveEntryInfo>()
        val inputStream = when (format) {
            "zip" -> ZipArchiveInputStream(FileInputStream(file))
            "tar" -> TarArchiveInputStream(FileInputStream(file))
            else -> return emptyList()
        }
        inputStream.use { ais ->
            var entry: ArchiveEntry? = ais.nextEntry
            while (entry != null) {
                contents.add(ArchiveEntryInfo(entry.name, entry.isDirectory, entry.size, entry.lastModifiedDate?.time ?: 0))
                entry = ais.nextEntry
            }
        }
        return contents
    }

    private fun list7zContents(file: File): List<ArchiveEntryInfo> {
        val contents = mutableListOf<ArchiveEntryInfo>()
        SevenZFile(file).use { szf ->
            var entry = szf.nextEntry
            while (entry != null) {
                contents.add(ArchiveEntryInfo(entry.name, entry.isDirectory, entry.size, 0L)) // Simplification for timestamp
                entry = szf.nextEntry
            }
        }
        return contents
    }

    fun extract(archiveFile: File, outputDir: File, entryName: String? = null) {
        if (!outputDir.exists()) outputDir.mkdirs()
        val extension = archiveFile.extension.lowercase()
        if (extension == "7z") {
            extract7z(archiveFile, outputDir, entryName)
        } else {
            extractStandard(archiveFile, outputDir, extension, entryName)
        }
    }

    private fun extractStandard(file: File, outputDir: File, format: String, targetEntry: String?) {
        val inputStream: ArchiveInputStream<*> = when (format) {
            "zip" -> ZipArchiveInputStream(FileInputStream(file))
            "tar" -> TarArchiveInputStream(FileInputStream(file))
            else -> return
        }
        inputStream.use { ais ->
            var entry = ais.nextEntry
            while (entry != null) {
                if (targetEntry == null || entry.name == targetEntry || entry.name.startsWith("$targetEntry/")) {
                    val entryRelativeName = if (targetEntry != null && entry.name.startsWith("$targetEntry/")) {
                        entry.name.substringAfter("$targetEntry/")
                    } else if (targetEntry != null) {
                        File(entry.name).name
                    } else {
                        entry.name
                    }

                    val outFile = File(outputDir, entryRelativeName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            IOUtils.copy(ais, out)
                        }
                    }
                    if (targetEntry != null && entry.name == targetEntry && !entry.isDirectory) break
                }
                entry = ais.nextEntry
            }
        }
    }

    private fun extract7z(file: File, outputDir: File, targetEntry: String?) {
        SevenZFile(file).use { szf ->
            var entry = szf.nextEntry
            while (entry != null) {
                if (targetEntry == null || entry.name == targetEntry || entry.name.startsWith("$targetEntry/")) {
                    val entryRelativeName = if (targetEntry != null && entry.name.startsWith("$targetEntry/")) {
                        entry.name.substringAfter("$targetEntry/")
                    } else if (targetEntry != null) {
                        File(entry.name).name
                    } else {
                        entry.name
                    }

                    val outFile = File(outputDir, entryRelativeName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (szf.read(buffer).also { len = it } != -1) {
                                out.write(buffer, 0, len)
                            }
                        }
                    }
                    if (targetEntry != null && entry.name == targetEntry && !entry.isDirectory) break
                }
                entry = szf.nextEntry
            }
        }
    }
}
