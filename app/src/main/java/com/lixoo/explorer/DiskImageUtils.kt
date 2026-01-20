package com.lixoo.explorer

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

object DiskImageUtils {

    fun isDiskImage(file: File): Boolean {
        if (!file.exists() || file.isDirectory) return false
        if (!file.name.lowercase().endsWith(".iso")) return false
        val raf = RandomAccessFile(file, "r")
        try {
            // ISO 9660: CD001 at 0x8001
            raf.seek(0x8001)
            val isoId = ByteArray(5)
            raf.read(isoId)
            if (isoId.contentEquals("CD001".toByteArray())) return true
        } catch (e: Exception) {
        } finally {
            raf.close()
        }
        return false
    }

    fun listDiskContents(file: File): List<ArchiveUtils.ArchiveEntryInfo> {
        if (file.name.lowercase().endsWith(".iso")) {
            return parseIso9660(file)
        }
        return emptyList()
    }

    private fun parseIso9660(file: File): List<ArchiveUtils.ArchiveEntryInfo> {
        val items = mutableListOf<ArchiveUtils.ArchiveEntryInfo>()
        val raf = RandomAccessFile(file, "r")
        try {
            // Find Root Directory Record in PVD (offset 32768 + 156)
            raf.seek(32768 + 156)
            val rootRecord = ByteArray(34)
            raf.read(rootRecord)

            val extentLocation = getIntLE(rootRecord, 2)
            val dataLength = getIntLE(rootRecord, 10)

            parseIsoDirectory(raf, extentLocation.toLong() * 2048, dataLength.toLong(), "", items)
        } catch (e: Exception) {
            items.add(ArchiveUtils.ArchiveEntryInfo("ISO9660 Content", false, file.length(), file.lastModified()))
        } finally { raf.close() }
        return items
    }

    private fun parseIsoDirectory(raf: RandomAccessFile, offset: Long, length: Long, path: String, items: MutableList<ArchiveUtils.ArchiveEntryInfo>) {
        if (items.size > 1000) return // Limit for performance

        raf.seek(offset)
        var current = 0L
        while (current < length) {
            val recordLength = raf.read()
            if (recordLength <= 0) break

            val record = ByteArray(recordLength - 1)
            raf.read(record)

            val flags = record[24].toInt()
            val isDir = (flags and 0x02) != 0
            val nameLen = record[31].toInt()
            var name = String(record, 32, nameLen, Charset.forName("ASCII")).split(";")[0]

            if (name != "\u0000" && name != "\u0001") {
                val fullPath = if (path.isEmpty()) name else "$path/$name"
                val extent = getIntLE(record, 1)
                val dataSize = getIntLE(record, 9)

                items.add(ArchiveUtils.ArchiveEntryInfo(fullPath, isDir, dataSize.toLong(), 0))

                if (isDir) {
                    val savedPos = raf.filePointer
                    parseIsoDirectory(raf, extent.toLong() * 2048, dataSize.toLong(), fullPath, items)
                    raf.seek(savedPos)
                }
            }

            current += recordLength
        }
    }

    private fun getIntLE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8) or
               ((data[offset + 2].toInt() and 0xFF) shl 16) or
               ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun extractDiskContent(file: File, outputDir: File, entryName: String? = null) {
        if (file.name.lowercase().endsWith(".iso")) {
            extractFromIso(file, outputDir, entryName)
        }
    }

    private fun extractFromIso(file: File, outputDir: File, entryName: String?) {
        val raf = RandomAccessFile(file, "r")
        try {
            raf.seek(32768 + 156)
            val rootRecord = ByteArray(34)
            raf.read(rootRecord)
            val extentLocation = getIntLE(rootRecord, 2)
            val dataLength = getIntLE(rootRecord, 10)

            if (entryName == null) {
                extractIsoDirectoryRecursive(raf, extentLocation.toLong() * 2048, dataLength.toLong(), outputDir)
            } else {
                findAndExtractIsoEntry(raf, extentLocation.toLong() * 2048, dataLength.toLong(), "", entryName, outputDir)
            }
        } finally { raf.close() }
    }

    private fun extractIsoDirectoryRecursive(raf: RandomAccessFile, offset: Long, length: Long, currentOutputDir: File) {
        raf.seek(offset)
        var current = 0L
        val entries = mutableListOf<Triple<Long, Long, String>>() // extent, length, name

        while (current < length) {
            val recordLength = raf.read()
            if (recordLength <= 0) break
            val record = ByteArray(recordLength - 1)
            raf.read(record)
            val flags = record[24].toInt()
            val isDir = (flags and 0x02) != 0
            val nameLen = record[31].toInt()
            var name = String(record, 32, nameLen, Charset.forName("ASCII")).split(";")[0]

            if (name != "\u0000" && name != "\u0001") {
                val extent = getIntLE(record, 1)
                val dataSize = getIntLE(record, 9)
                val outFile = File(currentOutputDir, name)

                if (isDir) {
                    outFile.mkdirs()
                    entries.add(Triple(extent.toLong() * 2048, dataSize.toLong(), outFile.absolutePath))
                } else {
                    val savedPos = raf.filePointer
                    raf.seek(extent.toLong() * 2048)
                    outFile.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var remaining = dataSize.toLong()
                        while (remaining > 0) {
                            val read = raf.read(buffer, 0, Math.min(buffer.size.toLong(), remaining).toInt())
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    raf.seek(savedPos)
                }
            }
            current += recordLength
        }

        // Recurse after reading the current directory to avoid seek conflicts
        for (e in entries) {
            extractIsoDirectoryRecursive(raf, e.first, e.second, File(e.third))
        }
    }

    private fun findAndExtractIsoEntry(raf: RandomAccessFile, offset: Long, length: Long, currentPath: String, targetName: String, outputDir: File) {
        raf.seek(offset)
        var current = 0L
        while (current < length) {
            val recordLength = raf.read()
            if (recordLength <= 0) break
            val record = ByteArray(recordLength - 1)
            raf.read(record)
            val flags = record[24].toInt()
            val isDir = (flags and 0x02) != 0
            val nameLen = record[31].toInt()
            var name = String(record, 32, nameLen, Charset.forName("ASCII")).split(";")[0]

            if (name != "\u0000" && name != "\u0001") {
                val fullPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                if (fullPath == targetName || targetName.startsWith("$fullPath/")) {
                    val extent = getIntLE(record, 1)
                    val dataSize = getIntLE(record, 9)

                    if (fullPath == targetName) {
                        if (isDir) {
                            val newOutDir = File(outputDir, name)
                            newOutDir.mkdirs()
                            extractIsoDirectoryRecursive(raf, extent.toLong() * 2048, dataSize.toLong(), newOutDir)
                        } else {
                            val outFile = File(outputDir, name)
                            val savedPos = raf.filePointer
                            raf.seek(extent.toLong() * 2048)
                            outFile.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var remaining = dataSize.toLong()
                                while (remaining > 0) {
                                    val read = raf.read(buffer, 0, Math.min(buffer.size.toLong(), remaining).toInt())
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                    remaining -= read
                                }
                            }
                            raf.seek(savedPos)
                        }
                        return
                    } else if (isDir) {
                        findAndExtractIsoEntry(raf, extent.toLong() * 2048, dataSize.toLong(), fullPath, targetName, outputDir)
                        return
                    }
                }
            }
            current += recordLength
        }
    }
}
