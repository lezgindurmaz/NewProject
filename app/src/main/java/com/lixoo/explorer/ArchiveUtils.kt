package com.lixoo.explorer

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.ArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.utils.IOUtils
import java.io.*

object ArchiveUtils {

    private val archiveCache = mutableMapOf<String, List<ArchiveEntryInfo>>()

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
            "iso", "img", "qcow2" -> compressDiskImage(files, outputFile, format.lowercase())
            "gz", "gzip" -> if (files.size == 1) compressStandalone(files[0], outputFile, CompressorStreamFactory.GZIP)
            "bz2", "bzip2" -> if (files.size == 1) compressStandalone(files[0], outputFile, CompressorStreamFactory.BZIP2)
            "xz" -> if (files.size == 1) compressStandalone(files[0], outputFile, CompressorStreamFactory.XZ)
            "lz4" -> if (files.size == 1) compressStandalone(files[0], outputFile, CompressorStreamFactory.LZ4_BLOCK)
            "tar.gz" -> compressTarCompressed(files, outputFile, CompressorStreamFactory.GZIP)
            "tar.xz" -> compressTarCompressed(files, outputFile, CompressorStreamFactory.XZ)
            "tar.lz4" -> compressTarCompressed(files, outputFile, CompressorStreamFactory.LZ4_BLOCK)
            else -> throw IllegalArgumentException("Unsupported format: $format")
        }
    }

    private fun compressZip(files: List<File>, outputFile: File) {
        ZipArchiveOutputStream(FileOutputStream(outputFile)).use { out ->
            files.forEach { file -> addFileToArchive(out, file, "") }
        }
    }

    private fun compressTar(files: List<File>, outputFile: File) {
        TarArchiveOutputStream(FileOutputStream(outputFile)).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            files.forEach { file -> addFileToArchive(out, file, "") }
        }
    }

    private fun compressTarCompressed(files: List<File>, outputFile: File, compressor: String) {
        val fos = FileOutputStream(outputFile)
        val cos = CompressorStreamFactory().createCompressorOutputStream(compressor, fos)
        TarArchiveOutputStream(cos).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            files.forEach { file -> addFileToArchive(out, file, "") }
        }
    }

    private fun compressStandalone(file: File, outputFile: File, compressor: String) {
        val fos = FileOutputStream(outputFile)
        CompressorStreamFactory().createCompressorOutputStream(compressor, fos).use { cos ->
            FileInputStream(file).use { fis ->
                IOUtils.copy(fis, cos)
            }
        }
    }

    private fun compressDiskImage(files: List<File>, outputFile: File, format: String) {
        // High-performance "Disk-like" creation using 7z logic as a carrier
        // This is the most compatible way on Android without native mkisofs
        compress7z(files, outputFile)
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
            files.forEach { file -> addFileTo7z(out, file, "") }
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
        val path = archiveFile.absolutePath
        if (archiveCache.containsKey(path)) {
            return archiveCache[path]!!
        }

        val name = archiveFile.name.lowercase()
        val result = when {
            name.endsWith(".7z") || name.endsWith(".iso") || name.endsWith(".img") || name.endsWith(".qcow2") -> {
                val contents = list7zContents(archiveFile)
                if (contents.isEmpty()) listGenericDiskContents(archiveFile) else contents
            }
            name.endsWith(".zip") -> listStandardArchiveContents(archiveFile, "zip")
            name.endsWith(".tar") -> listStandardArchiveContents(archiveFile, "tar")
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> listTarCompressedContents(archiveFile, CompressorStreamFactory.GZIP)
            name.endsWith(".tar.xz") -> listTarCompressedContents(archiveFile, CompressorStreamFactory.XZ)
            name.endsWith(".tar.lz4") -> listTarCompressedContents(archiveFile, CompressorStreamFactory.LZ4_BLOCK)
            // Standalone compressors don't have "entries", we treat them as a single file entry
            name.endsWith(".gz") || name.endsWith(".bz2") || name.endsWith(".xz") || name.endsWith(".lz4") -> {
                listOf(ArchiveEntryInfo(archiveFile.nameWithoutExtension, false, archiveFile.length(), archiveFile.lastModified()))
            }
            else -> emptyList()
        }

        if (result.isNotEmpty()) {
            archiveCache[path] = result
        }
        return result
    }

    fun clearCache() {
        archiveCache.clear()
    }

    fun isCached(file: File): Boolean {
        return archiveCache.containsKey(file.absolutePath)
    }

    private fun listStandardArchiveContents(file: File, format: String): List<ArchiveEntryInfo> {
        val contents = mutableListOf<ArchiveEntryInfo>()
        val fis = FileInputStream(file)
        val bis = BufferedInputStream(fis)
        val inputStream = when (format) {
            "zip" -> ZipArchiveInputStream(bis)
            "tar" -> TarArchiveInputStream(bis)
            else -> { bis.close(); return emptyList() }
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

    private fun listTarCompressedContents(file: File, compressor: String): List<ArchiveEntryInfo> {
        val contents = mutableListOf<ArchiveEntryInfo>()
        val fis = FileInputStream(file)
        val bis = BufferedInputStream(fis)
        val cos = CompressorStreamFactory().createCompressorInputStream(compressor, bis)
        TarArchiveInputStream(cos).use { ais ->
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
        try {
            SevenZFile(file).use { szf ->
                var entry = szf.nextEntry
                while (entry != null) {
                    contents.add(ArchiveEntryInfo(entry.name, entry.isDirectory, entry.size, 0L))
                    entry = szf.nextEntry
                }
            }
        } catch (e: Exception) { /* Fallback to other parsers */ }
        return contents
    }

    private fun listGenericDiskContents(file: File): List<ArchiveEntryInfo> {
        val contents = mutableListOf<ArchiveEntryInfo>()
        try {
            val fis = FileInputStream(file)
            val bis = BufferedInputStream(fis)
            val ais = ArchiveStreamFactory().createArchiveInputStream(bis) as ArchiveInputStream<ArchiveEntry>
            ais.use { stream ->
                var entry = stream.nextEntry
                while (entry != null) {
                    contents.add(ArchiveEntryInfo(entry.name, entry.isDirectory, entry.size, entry.lastModifiedDate?.time ?: 0L))
                    entry = stream.nextEntry
                }
            }
        } catch (e: Exception) {
            // Final fallback: Treat as a single raw volume
            contents.add(ArchiveEntryInfo("[RAW VOLUME] " + file.name, false, file.length(), file.lastModified()))
        }
        return contents
    }

    fun extract(archiveFile: File, outputDir: File, entryName: String? = null) {
        if (!outputDir.exists()) outputDir.mkdirs()
        val name = archiveFile.name.lowercase()
        when {
            name.endsWith(".7z") || name.endsWith(".iso") || name.endsWith(".img") || name.endsWith(".qcow2") -> {
                try { extract7z(archiveFile, outputDir, entryName) }
                catch (e: Exception) { extractGeneric(archiveFile, outputDir, entryName) }
            }
            name.endsWith(".zip") -> extractStandard(archiveFile, outputDir, "zip", entryName)
            name.endsWith(".tar") -> extractStandard(archiveFile, outputDir, "tar", entryName)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> extractTarCompressed(archiveFile, outputDir, CompressorStreamFactory.GZIP, entryName)
            name.endsWith(".tar.xz") -> extractTarCompressed(archiveFile, outputDir, CompressorStreamFactory.XZ, entryName)
            name.endsWith(".tar.lz4") -> extractTarCompressed(archiveFile, outputDir, CompressorStreamFactory.LZ4_BLOCK, entryName)
            name.endsWith(".gz") -> extractStandalone(archiveFile, outputDir, CompressorStreamFactory.GZIP)
            name.endsWith(".bz2") -> extractStandalone(archiveFile, outputDir, CompressorStreamFactory.BZIP2)
            name.endsWith(".xz") -> extractStandalone(archiveFile, outputDir, CompressorStreamFactory.XZ)
            name.endsWith(".lz4") -> extractStandalone(archiveFile, outputDir, CompressorStreamFactory.LZ4_BLOCK)
        }
    }

    private fun extractStandard(file: File, outputDir: File, format: String, targetEntry: String?) {
        val fis = FileInputStream(file)
        val bis = BufferedInputStream(fis)
        val inputStream: ArchiveInputStream<*> = when (format) {
            "zip" -> ZipArchiveInputStream(bis)
            "tar" -> TarArchiveInputStream(bis)
            else -> { bis.close(); return }
        }
        processArchiveInputStream(inputStream, outputDir, targetEntry)
    }

    private fun extractTarCompressed(file: File, outputDir: File, compressor: String, targetEntry: String?) {
        val fis = FileInputStream(file)
        val bis = BufferedInputStream(fis)
        val cos = CompressorStreamFactory().createCompressorInputStream(compressor, bis)
        val ais = TarArchiveInputStream(cos)
        processArchiveInputStream(ais, outputDir, targetEntry)
    }

    private fun processArchiveInputStream(ais: ArchiveInputStream<*>, outputDir: File, targetEntry: String?) {
        ais.use { stream ->
            var entry: ArchiveEntry? = stream.nextEntry
            while (entry != null) {
                if (targetEntry == null || entry.name == targetEntry || entry.name.startsWith("$targetEntry/")) {
                    val relName = if (targetEntry != null && entry.name.startsWith("$targetEntry/")) {
                        entry.name.substringAfter("$targetEntry/")
                    } else if (targetEntry != null) {
                        File(entry.name).name
                    } else {
                        entry.name
                    }
                    val outFile = File(outputDir, relName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(outFile)).use { out -> IOUtils.copy(stream, out) }
                    }
                }
                entry = stream.nextEntry
            }
        }
    }

    private fun extractStandalone(file: File, outputDir: File, compressor: String) {
        val fis = FileInputStream(file)
        val bis = BufferedInputStream(fis)
        CompressorStreamFactory().createCompressorInputStream(compressor, bis).use { cis ->
            val outFile = File(outputDir, file.nameWithoutExtension)
            FileOutputStream(outFile).use { fos ->
                IOUtils.copy(cis, fos)
            }
        }
    }

    private fun extract7z(file: File, outputDir: File, targetEntry: String?) {
        SevenZFile(file).use { szf ->
            var entry = szf.nextEntry
            while (entry != null) {
                if (targetEntry == null || entry.name == targetEntry || entry.name.startsWith("$targetEntry/")) {
                    val relName = if (targetEntry != null && entry.name.startsWith("$targetEntry/")) {
                        entry.name.substringAfter("$targetEntry/")
                    } else if (targetEntry != null) {
                        File(entry.name).name
                    } else {
                        entry.name
                    }
                    val outFile = File(outputDir, relName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (szf.read(buffer).also { len = it } != -1) {
                                out.write(buffer, 0, len)
                            }
                        }
                    }
                }
                entry = szf.nextEntry
            }
        }
    }

    private fun extractGeneric(file: File, outputDir: File, targetEntry: String?) {
        try {
            val fis = FileInputStream(file)
            val bis = BufferedInputStream(fis)
            val ais = ArchiveStreamFactory().createArchiveInputStream(bis) as ArchiveInputStream<ArchiveEntry>
            ais.use { stream ->
                processArchiveInputStream(stream, outputDir, targetEntry)
            }
        } catch (e: Exception) {
            // Raw copy fallback
            val outFile = File(outputDir, file.name)
            file.copyTo(outFile, overwrite = true)
        }
    }
}
