package com.lixoo.explorer

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

object DiskImageUtils {

    fun isDiskImage(file: File): Boolean {
        if (!file.exists() || file.isDirectory) return false
        val ext = file.name.lowercase()
        if (!ext.endsWith(".iso") && !ext.endsWith(".img")) return false

        val raf = RandomAccessFile(file, "r")
        try {
            if (ext.endsWith(".iso")) {
                // ISO 9660: CD001 at 0x8001
                raf.seek(0x8001)
                val isoId = ByteArray(5)
                raf.read(isoId)
                if (isoId.contentEquals("CD001".toByteArray())) return true
            } else if (ext.endsWith(".img")) {
                // Check for FAT boot sector signature
                if (file.length() < 512) return false
                raf.seek(510)
                return raf.read() == 0x55 && raf.read() == 0xAA
            }
        } catch (e: Exception) {
        } finally {
            raf.close()
        }
        return false
    }

    fun listDiskContents(file: File): List<ArchiveUtils.ArchiveEntryInfo> {
        val ext = file.name.lowercase()
        if (ext.endsWith(".iso")) {
            return parseIso9660(file)
        } else if (ext.endsWith(".img")) {
            return parseFatContents(file)
        }
        return emptyList()
    }

    private fun parseFatContents(file: File): List<ArchiveUtils.ArchiveEntryInfo> {
        val items = mutableListOf<ArchiveUtils.ArchiveEntryInfo>()
        val raf = RandomAccessFile(file, "r")
        try {
            val bpb = ByteArray(512)
            raf.read(bpb)

            val bytesPerSector = getShortLE(bpb, 11).toInt()
            val sectorsPerCluster = bpb[13].toInt() and 0xFF
            val reservedSectors = getShortLE(bpb, 14).toInt()
            val numFats = bpb[16].toInt() and 0xFF
            val rootEntries = getShortLE(bpb, 17).toInt()
            val sectorsPerFat16 = getShortLE(bpb, 22).toInt()

            val sectorsPerFat = if (sectorsPerFat16 != 0) sectorsPerFat16 else getIntLE(bpb, 36)

            val rootDirStart = (reservedSectors + (numFats * sectorsPerFat)).toLong() * bytesPerSector
            val rootDirSectors = ((rootEntries * 32) + (bytesPerSector - 1)) / bytesPerSector

            if (rootEntries > 0) {
                // FAT12/16
                parseFatDirectory(raf, rootDirStart, rootEntries, "", items)
            } else {
                // FAT32
                val rootCluster = getIntLE(bpb, 44)
                val fatStart = reservedSectors.toLong() * bytesPerSector
                val dataStart = (reservedSectors + (numFats * sectorsPerFat) + rootDirSectors).toLong() * bytesPerSector
                parseFat32Directory(raf, rootCluster, fatStart, dataStart, sectorsPerCluster, bytesPerSector, "", items)
            }
        } catch (e: Exception) {
            items.add(ArchiveUtils.ArchiveEntryInfo("IMG Content", false, file.length(), file.lastModified()))
        } finally { raf.close() }
        return items
    }

    private fun parseFatDirectory(raf: RandomAccessFile, offset: Long, entries: Int, path: String, items: MutableList<ArchiveUtils.ArchiveEntryInfo>) {
        raf.seek(offset)
        for (i in 0 until entries) {
            val entry = ByteArray(32)
            raf.read(entry)
            if (entry[0] == 0x00.toByte()) break
            if (entry[0] == 0xE5.toByte().toByte()) continue

            val attr = entry[11].toInt() and 0xFF
            if (attr == 0x0F) continue // LFN entry - skipped for simplicity in listing, will use short name

            val name = getFatName(entry)
            val isDir = (attr and 0x10) != 0
            val size = getIntLE(entry, 28).toLong()

            if (name != "." && name != "..") {
                val fullPath = if (path.isEmpty()) name else "$path/$name"
                items.add(ArchiveUtils.ArchiveEntryInfo(fullPath, isDir, size, 0))
                // For simplicity, we only list root for now. Recursive listing would require cluster chain following.
            }
        }
    }

    private fun parseFat32Directory(raf: RandomAccessFile, cluster: Int, fatStart: Long, dataStart: Long, spc: Int, bps: Int, path: String, items: MutableList<ArchiveUtils.ArchiveEntryInfo>) {
        // Very basic FAT32 root listing
        val offset = dataStart + (cluster - 2).toLong() * spc * bps
        parseFatDirectory(raf, offset, (spc * bps) / 32, path, items)
    }

    private fun getFatName(entry: ByteArray): String {
        val name = String(entry, 0, 8, Charset.forName("ASCII")).trim()
        val ext = String(entry, 8, 3, Charset.forName("ASCII")).trim()
        return if (ext.isEmpty()) name else "$name.$ext"
    }

    private fun getShortLE(data: ByteArray, offset: Int): Short {
        return ((data[offset + 1].toInt() and 0xFF shl 8) or (data[offset].toInt() and 0xFF)).toShort()
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
        val ext = file.name.lowercase()
        if (ext.endsWith(".iso")) {
            extractFromIso(file, outputDir, entryName)
        } else if (ext.endsWith(".img")) {
            extractFromFat(file, outputDir, entryName)
        }
    }

    private fun extractFromFat(file: File, outputDir: File, entryName: String?) {
        val raf = RandomAccessFile(file, "r")
        try {
            val bpb = ByteArray(512)
            raf.read(bpb)
            val bytesPerSector = getShortLE(bpb, 11).toInt()
            val sectorsPerCluster = bpb[13].toInt() and 0xFF
            val reservedSectors = getShortLE(bpb, 14).toInt()
            val numFats = bpb[16].toInt() and 0xFF
            val rootEntries = getShortLE(bpb, 17).toInt()
            val sectorsPerFat16 = getShortLE(bpb, 22).toInt()
            val sectorsPerFat = if (sectorsPerFat16 != 0) sectorsPerFat16 else getIntLE(bpb, 36)

            val rootDirStart = (reservedSectors + (numFats * sectorsPerFat)).toLong() * bytesPerSector
            val rootDirSectors = ((rootEntries * 32) + (bytesPerSector - 1)) / bytesPerSector
            val dataStart = rootDirStart + (rootDirSectors * bytesPerSector)
            val fatStart = reservedSectors.toLong() * bytesPerSector

            val totalSectors = if (getShortLE(bpb, 19).toInt() != 0) getShortLE(bpb, 19).toInt() and 0xFFFF else getIntLE(bpb, 32)
            val dataSectors = totalSectors - (reservedSectors + (numFats * sectorsPerFat) + rootDirSectors)
            val totalClusters = dataSectors / sectorsPerCluster
            val fatType = if (totalClusters < 4085) 12 else if (totalClusters < 65525) 16 else 32

            if (entryName == null) {
                extractFatDirectory(raf, rootDirStart, rootEntries, fatStart, dataStart, sectorsPerCluster, bytesPerSector, outputDir, fatType)
            } else {
                findAndExtractFatEntry(raf, rootDirStart, rootEntries, fatStart, dataStart, sectorsPerCluster, bytesPerSector, entryName, outputDir, fatType)
            }
        } finally { raf.close() }
    }

    private fun extractFatDirectory(raf: RandomAccessFile, offset: Long, entries: Int, fatStart: Long, dataStart: Long, spc: Int, bps: Int, outDir: File, fatType: Int) {
        raf.seek(offset)
        for (i in 0 until entries) {
            val entry = ByteArray(32)
            raf.read(entry)
            if (entry[0] == 0x00.toByte()) break
            if (entry[0] == 0xE5.toByte().toByte()) continue
            val attr = entry[11].toInt() and 0xFF
            if (attr == 0x0F || (attr and 0x08) != 0) continue

            val name = getFatName(entry)
            val isDir = (attr and 0x10) != 0
            if (name != "." && name != "..") {
                if (isDir) {
                    File(outDir, name).mkdirs()
                } else {
                    val cluster = getShortLE(entry, 26).toInt() and 0xFFFF
                    val size = getIntLE(entry, 28).toLong()
                    val outFile = File(outDir, name)
                    val savedPos = raf.filePointer
                    extractFatFile(raf, cluster, size, fatStart, dataStart, spc, bps, outFile, fatType)
                    raf.seek(savedPos)
                }
            }
        }
    }

    private fun findAndExtractFatEntry(raf: RandomAccessFile, offset: Long, entries: Int, fatStart: Long, dataStart: Long, spc: Int, bps: Int, target: String, outDir: File, fatType: Int) {
        raf.seek(offset)
        for (i in 0 until entries) {
            val entry = ByteArray(32)
            raf.read(entry)
            if (entry[0] == 0x00.toByte()) break
            val name = getFatName(entry)
            if (name == target) {
                val cluster = if (fatType == 32) (getShortLE(entry, 26).toInt() and 0xFFFF) or ((getShortLE(entry, 20).toInt() and 0xFFFF) shl 16) else getShortLE(entry, 26).toInt() and 0xFFFF
                val size = getIntLE(entry, 28).toLong()
                extractFatFile(raf, cluster, size, fatStart, dataStart, spc, bps, File(outDir, name), fatType)
                return
            }
        }
    }

    private fun extractFatFile(raf: RandomAccessFile, firstCluster: Int, size: Long, fatStart: Long, dataStart: Long, spc: Int, bps: Int, outFile: File, fatType: Int) {
        outFile.outputStream().use { out ->
            var currentCluster = firstCluster
            var remaining = size
            val buffer = ByteArray(spc * bps)

            while (remaining > 0) {
                if (currentCluster < 2) break
                val offset = dataStart + (currentCluster - 2).toLong() * spc * bps
                raf.seek(offset)
                val toRead = Math.min(buffer.size.toLong(), remaining).toInt()
                raf.read(buffer, 0, toRead)
                out.write(buffer, 0, toRead)
                remaining -= toRead

                // Get next cluster
                raf.seek(fatStart + when(fatType) {
                    12 -> (currentCluster * 3) / 2L
                    32 -> currentCluster * 4L
                    else -> currentCluster * 2L
                })

                currentCluster = when(fatType) {
                    12 -> {
                        val b1 = raf.read() and 0xFF
                        val b2 = raf.read() and 0xFF
                        if (currentCluster % 2 == 0) (b1 or ((b2 and 0x0F) shl 8)) else ((b1 shr 4) or (b2 shl 4))
                    }
                    32 -> getIntLE(byteArrayOf(raf.read().toByte(), raf.read().toByte(), raf.read().toByte(), raf.read().toByte()), 0) and 0x0FFFFFFF
                    else -> (raf.read() and 0xFF) or ((raf.read() and 0xFF) shl 8)
                }

                if (fatType == 12 && currentCluster >= 0x0FF8) break
                if (fatType == 16 && currentCluster >= 0xFFF8) break
                if (fatType == 32 && currentCluster >= 0x0FFFFFF8) break
            }
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

    private class IsoNode(val file: File?, val name: String, val isDirectory: Boolean) {
        val children = mutableListOf<IsoNode>()
        var extent: Int = 0
        var size: Int = 0
        var parent: IsoNode? = null
        var pathTableIndex: Int = 0
    }

    fun createIso(files: List<File>, outputFile: File, volumeLabel: String) {
        val root = IsoNode(null, "", true)
        fun buildTree(current: IsoNode, sourceFiles: List<File>) {
            for (f in sourceFiles) {
                val node = IsoNode(f, f.name.uppercase().replace("[^A-Z0-9_]".toRegex(), "_").take(31), f.isDirectory)
                node.parent = current
                current.children.add(node)
                if (f.isDirectory) {
                    val children = f.listFiles()?.toList() ?: emptyList()
                    buildTree(node, children)
                }
            }
        }
        buildTree(root, files)

        val raf = RandomAccessFile(outputFile, "rw")
        try {
            raf.setLength(0)
            raf.write(ByteArray(32768)) // System Area

            val allDirs = mutableListOf<IsoNode>()
            fun collectDirs(node: IsoNode) {
                if (node.isDirectory) {
                    allDirs.add(node)
                    node.pathTableIndex = allDirs.size
                    for (c in node.children) collectDirs(c)
                }
            }
            collectDirs(root)

            var currentSector = 16 + 2 + 2 // PVD + VDT + PathTables

            // Calculate Dir Sizes and Extents
            for (dir in allDirs) {
                var dirSize = 68 // . and ..
                for (child in dir.children) {
                    val name = if (child.isDirectory) child.name else "${child.name};1"
                    dirSize += (33 + name.length + 1) and 0xFE.toInt()
                }
                dir.size = (dirSize + 2047) / 2048 * 2048
                dir.extent = currentSector
                currentSector += dir.size / 2048
            }

            // Path Table Size
            var pathTableSize = 0
            for (dir in allDirs) {
                val nameLen = if (dir == root) 1 else dir.name.length
                pathTableSize += (8 + nameLen + 1) and 0xFE.toInt()
            }

            // Assign Files
            fun assignFiles(node: IsoNode) {
                for (c in node.children) {
                    if (!c.isDirectory) {
                        c.size = c.file!!.length().toInt()
                        c.extent = currentSector
                        currentSector += (c.size + 2047) / 2048
                    } else assignFiles(c)
                }
            }
            assignFiles(root)

            // Write PVD
            val pvd = ByteArray(2048)
            pvd[0] = 1
            System.arraycopy("CD001".toByteArray(), 0, pvd, 1, 5)
            pvd[6] = 1
            System.arraycopy(volumeLabel.uppercase().padEnd(32).toByteArray(), 0, pvd, 40, 32)
            putIntBoth(pvd, 80, currentSector)
            putShortBoth(pvd, 120, 1)
            putShortBoth(pvd, 124, 1)
            putShortBoth(pvd, 128, 2048)
            putIntBoth(pvd, 132, pathTableSize)
            putIntLE(pvd, 140, 18)
            putIntBE(pvd, 148, 19)
            val rootRec = createDirectoryRecord(root, "", true)
            System.arraycopy(rootRec, 0, pvd, 156, rootRec.size)
            raf.seek(16L * 2048); raf.write(pvd)

            // VDT
            val vdt = ByteArray(2048)
            vdt[0] = 255.toByte()
            System.arraycopy("CD001".toByteArray(), 0, vdt, 1, 5)
            vdt[6] = 1
            raf.seek(17L * 2048); raf.write(vdt)

            // Path Tables
            val lPath = ByteArray(2048)
            val mPath = ByteArray(2048)
            var lpOff = 0; var mpOff = 0
            for (dir in allDirs) {
                val name = if (dir == root) "\u0000" else dir.name
                lPath[lpOff] = name.length.toByte()
                putIntLE(lPath, lpOff + 2, dir.extent)
                putShortLE(lPath, lpOff + 6, dir.parent?.pathTableIndex ?: 1)
                System.arraycopy(name.toByteArray(), 0, lPath, lpOff + 8, name.length)
                lpOff += (8 + name.length + 1) and 0xFE.toInt()

                mPath[mpOff] = name.length.toByte()
                putIntBE(mPath, mpOff + 2, dir.extent)
                putShortBE(mPath, mpOff + 6, dir.parent?.pathTableIndex ?: 1)
                System.arraycopy(name.toByteArray(), 0, mPath, mpOff + 8, name.length)
                mpOff += (8 + name.length + 1) and 0xFE.toInt()
            }
            raf.seek(18L * 2048); raf.write(lPath)
            raf.seek(19L * 2048); raf.write(mPath)

            // Write Directories
            for (dir in allDirs) {
                raf.seek(dir.extent.toLong() * 2048)
                raf.write(createDirectoryRecord(dir, "\u0000", false)) // .
                raf.write(createDirectoryRecord(dir.parent ?: dir, "\u0001", false)) // ..
                for (child in dir.children) {
                    val name = if (child.isDirectory) child.name else "${child.name};1"
                    raf.write(createDirectoryRecord(child, name, false))
                }
            }

            // Write Files
            fun writeFiles(node: IsoNode) {
                for (c in node.children) {
                    if (!c.isDirectory) {
                        raf.seek(c.extent.toLong() * 2048)
                        c.file!!.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) { raf.write(buffer, 0, read) }
                        }
                    } else writeFiles(c)
                }
            }
            writeFiles(root)
            raf.setLength(currentSector.toLong() * 2048)

        } finally { raf.close() }
    }

    private fun createDirectoryRecord(node: IsoNode, name: String, isRootInPvd: Boolean): ByteArray {
        val nameBytes = when (name) {
            "\u0000" -> byteArrayOf(0)
            "\u0001" -> byteArrayOf(1)
            "" -> byteArrayOf(0)
            else -> name.toByteArray(Charset.forName("ASCII"))
        }
        val recordLen = ((33 + nameBytes.size + 1) / 2) * 2
        val record = ByteArray(recordLen)
        record[0] = recordLen.toByte()
        putIntBoth(record, 2, node.extent)
        putIntBoth(record, 10, node.size)
        record[18] = 124.toByte() // 2024
        record[19] = 1; record[20] = 1
        if (node.isDirectory) record[25] = 0x02
        putShortBoth(record, 28, 1)
        record[32] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, record, 33, nameBytes.size)
        return record
    }

    private fun putShortBoth(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
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

    private fun putShortLE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putShortBE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putIntBE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    fun createImg(outputFile: File, sizeMB: Double, label: String) {
        val raf = RandomAccessFile(outputFile, "rw")
        try {
            val totalSectors = (sizeMB * 1024 * 1024 / 512).toLong()
            raf.setLength(totalSectors * 512)

            // 1. Boot Sector (BPB)
            val boot = ByteArray(512)
            boot[0] = 0xEB.toByte(); boot[1] = 0x3C.toByte(); boot[2] = 0x90.toByte() // JMP
            System.arraycopy("MSDOS5.0".toByteArray(), 0, boot, 3, 8)

            putShortLE(boot, 11, 512) // Bytes per sector
            boot[13] = 4 // Sectors per cluster
            putShortLE(boot, 14, 1) // Reserved sectors
            boot[16] = 2 // Number of FATs
            putShortLE(boot, 17, 512) // Root entries

            if (totalSectors < 65536) {
                putShortLE(boot, 19, totalSectors.toInt())
            } else {
                putShortLE(boot, 19, 0)
                putIntLE(boot, 32, totalSectors.toInt())
            }

            boot[21] = 0xF8.toByte() // Media descriptor

            // Calculate FAT size
            val sectorsPerFat = ((totalSectors / 4) * 2 / 512 + 1).toInt()
            putShortLE(boot, 22, sectorsPerFat)

            boot[38] = 0x29.toByte() // Signature
            System.arraycopy(label.uppercase().padEnd(11).toByteArray(), 0, boot, 43, 11)
            System.arraycopy("FAT16   ".toByteArray(), 0, boot, 54, 8)

            boot[510] = 0x55.toByte(); boot[511] = 0xAA.toByte()
            raf.seek(0)
            raf.write(boot)

            // 2. Initialize FATs
            val fatInit = ByteArray(512)
            fatInit[0] = 0xF8.toByte(); fatInit[1] = 0xFF.toByte(); fatInit[2] = 0xFF.toByte(); fatInit[3] = 0xFF.toByte()

            raf.seek(512) // FAT1 start
            raf.write(fatInit)

            raf.seek((1 + sectorsPerFat).toLong() * 512) // FAT2 start
            raf.write(fatInit)

        } finally { raf.close() }
    }
}
