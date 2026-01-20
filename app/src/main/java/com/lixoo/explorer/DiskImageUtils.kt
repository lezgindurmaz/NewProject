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

    private fun parseIsoDirectory(raf: RandomAccessFile, baseOffset: Long, totalLength: Long, path: String, items: MutableList<ArchiveUtils.ArchiveEntryInfo>) {
        if (items.size > 10000) return // Safety limit

        var currentOffset = 0L
        while (currentOffset < totalLength) {
            val sectorStart = baseOffset + currentOffset

            var sectorPos = 0
            while (sectorPos < 2048 && (currentOffset + sectorPos) < totalLength) {
                raf.seek(sectorStart + sectorPos)
                val recordLength = raf.read()
                if (recordLength <= 0) break // End of records in this sector

                val record = ByteArray(recordLength - 1)
                raf.read(record)

                val flags = record[24].toInt()
                val isDir = (flags and 0x02) != 0
                val nameLen = record[31].toInt() and 0xFF

                if (nameLen > 0 && 32 + nameLen <= recordLength) {
                    val name = String(record, 32, nameLen, Charset.forName("ASCII")).split(";")[0]

                    if (name != "\u0000" && name != "\u0001") {
                        val fullPath = if (path.isEmpty()) name else "$path/$name"
                        val extent = getIntLE(record, 1)
                        val dataSize = getIntLE(record, 9)

                        items.add(ArchiveUtils.ArchiveEntryInfo(fullPath, isDir, dataSize.toLong(), 0))

                        if (isDir) {
                            val savedPos = sectorStart + sectorPos + recordLength
                            parseIsoDirectory(raf, extent.toLong() * 2048, dataSize.toLong(), fullPath, items)
                            raf.seek(savedPos)
                        }
                    }
                }
                sectorPos += recordLength
            }
            currentOffset += 2048
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

    private fun extractIsoDirectoryRecursive(raf: RandomAccessFile, baseOffset: Long, totalLength: Long, currentOutputDir: File) {
        var currentOffset = 0L
        val subDirs = mutableListOf<Triple<Long, Long, File>>()

        while (currentOffset < totalLength) {
            val sectorStart = baseOffset + currentOffset

            var sectorPos = 0
            while (sectorPos < 2048 && (currentOffset + sectorPos) < totalLength) {
                raf.seek(sectorStart + sectorPos)
                val recordLength = raf.read()
                if (recordLength <= 0) break

                val record = ByteArray(recordLength - 1)
                raf.read(record)

                val flags = record[24].toInt()
                val isDir = (flags and 0x02) != 0
                val nameLen = record[31].toInt() and 0xFF

                if (nameLen > 0 && 32 + nameLen <= recordLength) {
                    val name = String(record, 32, nameLen, Charset.forName("ASCII")).split(";")[0]

                    if (name != "\u0000" && name != "\u0001") {
                        val extent = getIntLE(record, 1)
                        val dataSize = getIntLE(record, 9)
                        val outFile = File(currentOutputDir, name)

                        if (isDir) {
                            outFile.mkdirs()
                            subDirs.add(Triple(extent.toLong() * 2048, dataSize.toLong(), outFile))
                        } else {
                            raf.seek(extent.toLong() * 2048)
                            outFile.outputStream().use { out ->
                                val buffer = ByteArray(32768)
                                var remaining = dataSize.toLong()
                                while (remaining > 0) {
                                    val read = raf.read(buffer, 0, Math.min(buffer.size.toLong(), remaining).toInt())
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                    remaining -= read
                                }
                            }
                        }
                    }
                }
                sectorPos += recordLength
            }
            currentOffset += 2048
        }

        for (dir in subDirs) {
            extractIsoDirectoryRecursive(raf, dir.first, dir.second, dir.third)
        }
    }

    private fun findAndExtractIsoEntry(raf: RandomAccessFile, baseOffset: Long, totalLength: Long, currentPath: String, targetName: String, outputDir: File) {
        var currentOffset = 0L
        while (currentOffset < totalLength) {
            val sectorStart = baseOffset + currentOffset

            var sectorPos = 0
            while (sectorPos < 2048 && (currentOffset + sectorPos) < totalLength) {
                raf.seek(sectorStart + sectorPos)
                val recordLength = raf.read()
                if (recordLength <= 0) break

                val record = ByteArray(recordLength - 1)
                raf.read(record)

                val flags = record[24].toInt()
                val isDir = (flags and 0x02) != 0
                val nameLen = record[31].toInt() and 0xFF

                if (nameLen > 0 && 32 + nameLen <= recordLength) {
                    val name = String(record, 32, nameLen, Charset.forName("ASCII")).split(";")[0]

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
                                    raf.seek(extent.toLong() * 2048)
                                    outFile.outputStream().use { out ->
                                        val buffer = ByteArray(32768)
                                        var remaining = dataSize.toLong()
                                        while (remaining > 0) {
                                            val read = raf.read(buffer, 0, Math.min(buffer.size.toLong(), remaining).toInt())
                                            if (read <= 0) break
                                            out.write(buffer, 0, read)
                                            remaining -= read
                                        }
                                    }
                                }
                                return
                            } else if (isDir) {
                                findAndExtractIsoEntry(raf, extent.toLong() * 2048, dataSize.toLong(), fullPath, targetName, outputDir)
                                return
                            }
                        }
                    }
                }
                sectorPos += recordLength
            }
            currentOffset += 2048
        }
    }

    fun createIso(files: List<File>, outputFile: File, volumeLabel: String) {
        val raf = RandomAccessFile(outputFile, "rw")
        try {
            raf.setLength(0)
            // 1. System Area (32KB)
            raf.write(ByteArray(32768))

            // 2. Primary Volume Descriptor (PVD) - Sector 16
            val pvd = ByteArray(2048)
            pvd[0] = 0x01
            System.arraycopy("CD001".toByteArray(), 0, pvd, 1, 5)
            pvd[6] = 0x01

            val label = volumeLabel.uppercase().padEnd(32)
            System.arraycopy(label.toByteArray(), 0, pvd, 40, 32)

            // Temporary values for Extents - we will update these later
            // Root Directory at sector 19, Path Table at sector 18
            putIntBoth(pvd, 80, 0) // Volume Space Size (updated later)
            putIntBoth(pvd, 132, 1) // Volume Set Size
            putIntBoth(pvd, 136, 1) // Volume Sequence Number
            putIntBoth(pvd, 140, 2048) // Logical Block Size

            // Root Directory Record in PVD
            val rootRecord = createDirectoryRecord("", 19, 2048, true)
            System.arraycopy(rootRecord, 0, pvd, 156, 34)

            raf.write(pvd)

            // 3. Volume Descriptor Set Terminator - Sector 17
            val terminator = ByteArray(2048)
            terminator[0] = 0xFF.toByte()
            System.arraycopy("CD001".toByteArray(), 0, terminator, 1, 5)
            terminator[6] = 0x01
            raf.write(terminator)

            // 4. Path Table Placeholder - Sector 18 (Simplified: just root)
            val pathTable = ByteArray(2048)
            pathTable[0] = 0x01 // Root name length
            putIntLE(pathTable, 2, 19) // Extent of Root
            raf.write(pathTable)

            // 5. Root Directory Contents - Sector 19
            // First two entries are . and ..
            val currentSector = ByteArray(2048)
            var pos = 0

            val dot = createDirectoryRecord("\u0000", 19, 2048, true)
            System.arraycopy(dot, 0, currentSector, pos, dot.size)
            pos += dot.size

            val dotdot = createDirectoryRecord("\u0001", 19, 2048, true)
            System.arraycopy(dotdot, 0, currentSector, pos, dotdot.size)
            pos += dotdot.size

            // 6. Add Files
            var nextDataSector = 20L // Starting after Root Directory
            val fileRecords = mutableListOf<Triple<File, Long, Int>>() // File, StartSector, RecordPos

            for (file in files) {
                if (file.isDirectory) continue // Simplified: flat files only for now
                val name = file.name.uppercase().replace("[^A-Z0-0_]".toRegex(), "_").take(12)
                val size = file.length().toInt()
                val extent = nextDataSector.toInt()

                val record = createDirectoryRecord(name, extent, size, false)
                if (pos + record.size > 2048) {
                    // Start next sector for directory (rare for few files)
                    break
                }
                System.arraycopy(record, 0, currentSector, pos, record.size)
                fileRecords.add(Triple(file, nextDataSector, size))
                pos += record.size
                nextDataSector += (size + 2047) / 2048
            }
            raf.write(currentSector)

            // 7. Write File Data
            raf.seek(20 * 2048)
            for (record in fileRecords) {
                val file = record.first
                java.io.FileInputStream(file).use { fis ->
                    java.io.BufferedInputStream(fis).use { input ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            raf.write(buffer, 0, read)
                        }
                    }
                }
                // Pad to next sector
                val padding = (2048 - (file.length() % 2048)) % 2048
                if (padding > 0) raf.write(ByteArray(padding.toInt()))
            }

            // 8. Final Updates
            val totalSectors = (raf.length() + 2047) / 2048
            raf.seek(32768 + 80)
            putIntBothRAF(raf, totalSectors.toInt())

        } finally { raf.close() }
    }

    private fun createDirectoryRecord(name: String, extent: Int, size: Int, isDir: Boolean): ByteArray {
        val nameBytes = name.toByteArray(Charset.forName("ASCII"))
        val nameLen = nameBytes.size
        val recordLen = 33 + nameLen + (if (nameLen % 2 == 0) 1 else 0)
        val record = ByteArray(recordLen)

        record[0] = recordLen.toByte()
        putIntBoth(record, 2, extent)
        putIntBoth(record, 10, size)
        // Date (simplfied: zeroes)
        if (isDir) record[25] = 0x02
        record[32] = nameLen.toByte()
        System.arraycopy(nameBytes, 0, record, 33, nameLen)

        return record
    }

    private fun putIntBoth(data: ByteArray, offset: Int, value: Int) {
        // Little Endian
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
        // Big Endian
        data[offset + 4] = ((value shr 24) and 0xFF).toByte()
        data[offset + 5] = ((value shr 16) and 0xFF).toByte()
        data[offset + 6] = ((value shr 8) and 0xFF).toByte()
        data[offset + 7] = (value and 0xFF).toByte()
    }

    private fun putIntBothRAF(raf: RandomAccessFile, value: Int) {
        val b = ByteArray(8)
        putIntBoth(b, 0, value)
        raf.write(b)
    }

    private fun putIntLE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
