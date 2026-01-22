package com.lixoo.explorer

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

object DiskImageUtils {

    interface VirtualDisk {
        fun seek(pos: Long)
        fun read(b: ByteArray, off: Int, len: Int): Int
        fun read(): Int
        fun length(): Long
        fun close()
    }

    class RawDisk(private val raf: RandomAccessFile) : VirtualDisk {
        override fun seek(pos: Long) = raf.seek(pos)
        override fun read(b: ByteArray, off: Int, len: Int) = raf.read(b, off, len)
        override fun read() = raf.read()
        override fun length() = raf.length()
        override fun close() = raf.close()
    }

    class Qcow2Disk(private val raf: RandomAccessFile) : VirtualDisk {
        private val clusterBits: Int
        private val clusterSize: Int
        private val l1Size: Int
        private val l1TableOffset: Long
        private val l1Table: LongArray
        private var virtualPos: Long = 0
        private val l2Cache = mutableMapOf<Long, LongArray>()

        private var cachedClusterPos: Long = -1
        private val clusterBuffer: ByteArray

        init {
            raf.seek(0)
            val header = ByteArray(104)
            raf.read(header)
            clusterBits = getIntBE(header, 20)
            clusterSize = 1 shl clusterBits
            clusterBuffer = ByteArray(clusterSize)
            l1Size = getIntBE(header, 36)
            l1TableOffset = getLongBE(header, 40)

            l1Table = LongArray(l1Size)
            raf.seek(l1TableOffset)
            val l1Buf = ByteArray(Math.min(l1Size * 8, 1024 * 1024)) // Limit bulk read to 1MB
            raf.read(l1Buf)
            for (i in 0 until Math.min(l1Size, l1Buf.size / 8)) {
                l1Table[i] = getLongBE(l1Buf, i * 8) and 0x00fffffffffff000L
            }
            // If L1 is larger than 1MB, read the rest individually or in chunks (unlikely for most mobile use cases)
            if (l1Size * 8 > l1Buf.size) {
                raf.seek(l1TableOffset + l1Buf.size)
                for (i in (l1Buf.size / 8) until l1Size) {
                    l1Table[i] = raf.readLong() and 0x00fffffffffff000L
                }
            }
        }

        override fun seek(pos: Long) { virtualPos = pos }
        override fun length(): Long {
            raf.seek(24)
            val b = ByteArray(8)
            raf.read(b)
            return getLongBE(b, 0)
        }

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) > 0) b[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var totalRead = 0
            while (totalRead < len) {
                val clusterBase = (virtualPos / clusterSize) * clusterSize
                val clusterOffset = (virtualPos % clusterSize).toInt()
                val toRead = Math.min(len - totalRead, clusterSize - clusterOffset)

                if (cachedClusterPos != clusterBase) {
                    val phys = getPhysicalOffset(virtualPos)
                    if (phys == 0L) {
                        for (i in clusterBuffer.indices) clusterBuffer[i] = 0
                    } else if (phys == -1L) { // All zeros bit
                        for (i in clusterBuffer.indices) clusterBuffer[i] = 0
                    } else {
                        raf.seek(phys)
                        raf.read(clusterBuffer)
                    }
                    cachedClusterPos = clusterBase
                }

                System.arraycopy(clusterBuffer, clusterOffset, b, off + totalRead, toRead)

                virtualPos += toRead
                totalRead += toRead
            }
            return totalRead
        }

        private fun getPhysicalOffset(vPos: Long): Long {
            val l2Entries = clusterSize / 8
            val clusterIdx = vPos / clusterSize
            val l2Index = (clusterIdx % l2Entries).toInt()
            val l1Index = (clusterIdx / l2Entries).toInt()

            if (l1Index >= l1Table.size || l1Table[l1Index] == 0L) return 0

            val l2TablePos = l1Table[l1Index]
            val l2Table = if (l2Cache.containsKey(l2TablePos)) l2Cache[l2TablePos]!! else {
                if (l2Cache.size > 64) l2Cache.clear() // Simple cache eviction
                val table = LongArray(l2Entries)
                val buf = ByteArray(clusterSize)
                raf.seek(l2TablePos)
                raf.read(buf)
                for (i in 0 until l2Entries) {
                    table[i] = getLongBE(buf, i * 8)
                }
                l2Cache[l2TablePos] = table
                table
            }
            val entry = l2Table[l2Index]

            if ((entry and 1L) != 0L) return -1L // All zeros (v3)
            if ((entry and (1L shl 62)) != 0L) return -2L // Compressed (TODO: handle)

            return entry and 0x00fffffffffff000L
        }

        override fun close() = raf.close()

        private fun getIntBE(b: ByteArray, off: Int) =
            (b[off].toInt() and 0xFF shl 24) or (b[off+1].toInt() and 0xFF shl 16) or
            (b[off+2].toInt() and 0xFF shl 8) or (b[off+3].toInt() and 0xFF)

        private fun getLongBE(b: ByteArray, off: Int) =
            (b[off].toLong() and 0xFF shl 56) or (b[off+1].toLong() and 0xFF shl 48) or
            (b[off+2].toLong() and 0xFF shl 40) or (b[off+3].toLong() and 0xFF shl 32) or
            (b[off+4].toLong() and 0xFF shl 24) or (b[off+5].toLong() and 0xFF shl 16) or
            (b[off+6].toLong() and 0xFF shl 8) or (b[off+7].toLong() and 0xFF)
    }

    fun isDiskImage(file: File): Boolean {
        if (!file.exists() || file.isDirectory) return false
        val ext = file.name.lowercase()
        if (!ext.endsWith(".iso") && !ext.endsWith(".img") && !ext.endsWith(".qcow2")) return false

        val raf = RandomAccessFile(file, "r")
        try {
            if (ext.endsWith(".iso")) {
                raf.seek(0x8001)
                val id = ByteArray(5)
                raf.read(id)
                if (id.contentEquals("CD001".toByteArray())) return true
            } else if (ext.endsWith(".qcow2")) {
                raf.seek(0)
                return raf.read() == 0x51 && raf.read() == 0x46 && raf.read() == 0x49 && raf.read() == 0xFB
            } else {
                if (file.length() < 512) return false
                raf.seek(510)
                return raf.read() == 0x55 && raf.read() == 0xAA
            }
        } catch (e: Exception) {
        } finally { raf.close() }
        return false
    }

    fun listDiskContents(file: File): List<ArchiveUtils.ArchiveEntryInfo> {
        val ext = file.name.lowercase()
        if (ext.endsWith(".iso")) return parseIso9660(file)

        val raf = RandomAccessFile(file, "r")
        val disk = if (ext.endsWith(".qcow2")) Qcow2Disk(raf) else RawDisk(raf)
        try {
            return parseFatContents(disk)
        } catch (e: Exception) {
            return listOf(ArchiveUtils.ArchiveEntryInfo("Disk Content", false, file.length(), file.lastModified()))
        } finally { disk.close() }
    }

    private fun parseFatContents(disk: VirtualDisk): List<ArchiveUtils.ArchiveEntryInfo> {
        val items = mutableListOf<ArchiveUtils.ArchiveEntryInfo>()
        try {
            val bpb = ByteArray(512)
            disk.read(bpb, 0, 512)

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

            if (rootEntries > 0) {
                parseFatDirectory(disk, rootDirStart, 0, rootEntries, "", items, fatStart, dataStart, sectorsPerCluster, bytesPerSector, fatType)
            } else {
                val rootCluster = getIntLE(bpb, 44)
                parseFat32Directory(disk, rootCluster, fatStart, dataStart, sectorsPerCluster, bytesPerSector, "", items, fatType)
            }
        } catch (e: Exception) {}
        return items
    }

    private fun parseFatDirectory(disk: VirtualDisk, offset: Long, startCluster: Int, entries: Int, path: String, items: MutableList<ArchiveUtils.ArchiveEntryInfo>,
                                  fatStart: Long, dataStart: Long, spc: Int, bps: Int, fatType: Int) {
        if (items.size > 10000) return

        if (entries > 0) {
            // Fixed size directory (FAT12/16 root)
            disk.seek(offset)
            for (i in 0 until entries) {
                val entry = ByteArray(32)
                if (disk.read(entry, 0, 32) < 32) break
                processFatEntry(disk, entry, path, items, fatStart, dataStart, spc, bps, fatType)
            }
        } else {
            // Cluster-chain directory
            var currentCluster = startCluster
            val clusterSize = spc * bps
            val buf = ByteArray(32)
            while (currentCluster >= 2) {
                val clusterOffset = dataStart + (currentCluster - 2).toLong() * clusterSize
                disk.seek(clusterOffset)
                for (i in 0 until (clusterSize / 32)) {
                    if (disk.read(buf, 0, 32) < 32) break
                    if (!processFatEntry(disk, buf, path, items, fatStart, dataStart, spc, bps, fatType)) return
                }
                currentCluster = getNextCluster(disk, currentCluster, fatStart, fatType)
                if (isEndOfChain(currentCluster, fatType)) break
            }
        }
    }

    private fun processFatEntry(disk: VirtualDisk, entry: ByteArray, path: String, items: MutableList<ArchiveUtils.ArchiveEntryInfo>,
                                fatStart: Long, dataStart: Long, spc: Int, bps: Int, fatType: Int): Boolean {
        if (entry[0] == 0x00.toByte()) return false
        if (entry[0] == 0xE5.toByte()) return true

        val attr = entry[11].toInt() and 0xFF
        if (attr == 0x0F || (attr and 0x08) != 0) return true

        val name = getFatName(entry)
        val isDir = (attr and 0x10) != 0
        val cluster = if (fatType == 32) (getShortLE(entry, 26).toInt() and 0xFFFF) or ((getShortLE(entry, 20).toInt() and 0xFFFF) shl 16) else getShortLE(entry, 26).toInt() and 0xFFFF
        val size = if (isDir) 0L else getIntLE(entry, 28).toLong()

        if (name != "." && name != "..") {
            val fullPath = if (path.isEmpty()) name else "$path/$name"
            items.add(ArchiveUtils.ArchiveEntryInfo(fullPath, isDir, size, 0))
            if (isDir && cluster >= 2) {
                val savedPos = disk.length() // We don't really use this, but we need to restore seek
                // To avoid deep recursion issues and since listDiskContents is flat anyway:
                parseFatDirectory(disk, 0, cluster, 0, fullPath, items, fatStart, dataStart, spc, bps, fatType)
            }
        }
        return true
    }

    private fun getNextCluster(disk: VirtualDisk, cluster: Int, fatStart: Long, fatType: Int): Int {
        val oldPos = disk.length() // Dummy
        disk.seek(fatStart + when(fatType) {
            12 -> (cluster * 3) / 2L
            32 -> cluster * 4L
            else -> cluster * 2L
        })
        val next = when(fatType) {
            12 -> {
                val b1 = disk.read() and 0xFF
                val b2 = disk.read() and 0xFF
                if (cluster % 2 == 0) (b1 or ((b2 and 0x0F) shl 8)) else ((b1 shr 4) or (b2 shl 4))
            }
            32 -> {
                val b = ByteArray(4)
                disk.read(b, 0, 4)
                getIntLE(b, 0) and 0x0FFFFFFF
            }
            else -> (disk.read() and 0xFF) or ((disk.read() and 0xFF) shl 8)
        }
        return next
    }

    private fun isEndOfChain(cluster: Int, fatType: Int): Boolean {
        return when(fatType) {
            12 -> cluster >= 0x0FF8
            16 -> cluster >= 0xFFF8
            else -> cluster >= 0x0FFFFFF8
        }
    }

    private fun parseFat32Directory(disk: VirtualDisk, cluster: Int, fatStart: Long, dataStart: Long, spc: Int, bps: Int, path: String, items: MutableList<ArchiveUtils.ArchiveEntryInfo>, fatType: Int) {
        parseFatDirectory(disk, 0, cluster, 0, path, items, fatStart, dataStart, spc, bps, fatType)
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
        } else {
            extractFromFat(file, outputDir, entryName)
        }
    }

    private fun extractFromFat(file: File, outputDir: File, entryName: String?) {
        val ext = file.name.lowercase()
        val raf = RandomAccessFile(file, "r")
        val disk = if (ext.endsWith(".qcow2")) Qcow2Disk(raf) else RawDisk(raf)
        try {
            val bpb = ByteArray(512)
            disk.read(bpb, 0, 512)
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
                if (rootEntries > 0) extractFatDirectory(disk, rootDirStart, 0, rootEntries, fatStart, dataStart, sectorsPerCluster, bytesPerSector, outputDir, fatType)
                else {
                    val rootCluster = getIntLE(bpb, 44)
                    extractFatDirectory(disk, 0, rootCluster, 0, fatStart, dataStart, sectorsPerCluster, bytesPerSector, outputDir, fatType)
                }
            } else {
                if (rootEntries > 0) findAndExtractFatEntry(disk, rootDirStart, 0, rootEntries, fatStart, dataStart, sectorsPerCluster, bytesPerSector, entryName, outputDir, fatType)
                else {
                    val rootCluster = getIntLE(bpb, 44)
                    findAndExtractFatEntry(disk, 0, rootCluster, 0, fatStart, dataStart, sectorsPerCluster, bytesPerSector, entryName, outputDir, fatType)
                }
            }
        } finally { disk.close() }
    }

    private fun extractFatDirectory(disk: VirtualDisk, offset: Long, startCluster: Int, entries: Int, fatStart: Long, dataStart: Long, spc: Int, bps: Int, outDir: File, fatType: Int) {
        if (entries > 0) {
            disk.seek(offset)
            for (i in 0 until entries) {
                val entry = ByteArray(32)
                if (disk.read(entry, 0, 32) < 32) break
                processExtractFatEntry(disk, entry, fatStart, dataStart, spc, bps, outDir, fatType)
            }
        } else {
            var currentCluster = startCluster
            val clusterSize = spc * bps
            val buf = ByteArray(32)
            while (currentCluster >= 2) {
                disk.seek(dataStart + (currentCluster - 2).toLong() * clusterSize)
                for (i in 0 until (clusterSize / 32)) {
                    if (disk.read(buf, 0, 32) < 32) break
                    if (!processExtractFatEntry(disk, buf, fatStart, dataStart, spc, bps, outDir, fatType)) return
                }
                currentCluster = getNextCluster(disk, currentCluster, fatStart, fatType)
                if (isEndOfChain(currentCluster, fatType)) break
            }
        }
    }

    private fun processExtractFatEntry(disk: VirtualDisk, entry: ByteArray, fatStart: Long, dataStart: Long, spc: Int, bps: Int, outDir: File, fatType: Int): Boolean {
        if (entry[0] == 0x00.toByte()) return false
        if (entry[0] == 0xE5.toByte()) return true
        val attr = entry[11].toInt() and 0xFF
        if (attr == 0x0F || (attr and 0x08) != 0) return true

        val name = getFatName(entry)
        val isDir = (attr and 0x10) != 0
        val cluster = if (fatType == 32) (getShortLE(entry, 26).toInt() and 0xFFFF) or ((getShortLE(entry, 20).toInt() and 0xFFFF) shl 16) else getShortLE(entry, 26).toInt() and 0xFFFF

        if (name != "." && name != "..") {
            if (isDir) {
                val subDir = File(outDir, name)
                subDir.mkdirs()
                if (cluster >= 2) extractFatDirectory(disk, 0, cluster, 0, fatStart, dataStart, spc, bps, subDir, fatType)
            } else {
                val size = getIntLE(entry, 28).toLong()
                extractFatFile(disk, cluster, size, fatStart, dataStart, spc, bps, File(outDir, name), fatType)
            }
        }
        return true
    }

    private fun findAndExtractFatEntry(disk: VirtualDisk, offset: Long, startCluster: Int, entries: Int, fatStart: Long, dataStart: Long, spc: Int, bps: Int, target: String, outDir: File, fatType: Int) {
        if (entries > 0) {
            disk.seek(offset)
            for (i in 0 until entries) {
                val entry = ByteArray(32)
                if (disk.read(entry, 0, 32) < 32) break
                if (processFindExtractEntry(disk, entry, fatStart, dataStart, spc, bps, target, outDir, fatType)) return
            }
        } else {
            var currentCluster = startCluster
            val clusterSize = spc * bps
            val buf = ByteArray(32)
            while (currentCluster >= 2) {
                disk.seek(dataStart + (currentCluster - 2).toLong() * clusterSize)
                for (i in 0 until (clusterSize / 32)) {
                    if (disk.read(buf, 0, 32) < 32) break
                    if (processFindExtractEntry(disk, buf, fatStart, dataStart, spc, bps, target, outDir, fatType)) return
                }
                currentCluster = getNextCluster(disk, currentCluster, fatStart, fatType)
                if (isEndOfChain(currentCluster, fatType)) break
            }
        }
    }

    private fun processFindExtractEntry(disk: VirtualDisk, entry: ByteArray, fatStart: Long, dataStart: Long, spc: Int, bps: Int, target: String, outDir: File, fatType: Int): Boolean {
        if (entry[0] == 0x00.toByte()) return true // Stop searching in this cluster
        if (entry[0] == 0xE5.toByte()) return false

        val name = getFatName(entry)
        val attr = entry[11].toInt() and 0xFF
        val isDir = (attr and 0x10) != 0
        val cluster = if (fatType == 32) (getShortLE(entry, 26).toInt() and 0xFFFF) or ((getShortLE(entry, 20).toInt() and 0xFFFF) shl 16) else getShortLE(entry, 26).toInt() and 0xFFFF

        val currentPath = if (target.contains("/")) target.substringBefore("/") else target
        if (name == currentPath) {
            if (isDir) {
                val remainingPath = target.substringAfter("/", "")
                if (remainingPath.isEmpty()) {
                    extractFatDirectory(disk, 0, cluster, 0, fatStart, dataStart, spc, bps, File(outDir, name), fatType)
                } else {
                    findAndExtractFatEntry(disk, 0, cluster, 0, fatStart, dataStart, spc, bps, remainingPath, File(outDir, name), fatType)
                }
            } else {
                val size = getIntLE(entry, 28).toLong()
                extractFatFile(disk, cluster, size, fatStart, dataStart, spc, bps, File(outDir, name), fatType)
            }
            return true
        }
        return false
    }

    private fun extractFatFile(disk: VirtualDisk, firstCluster: Int, size: Long, fatStart: Long, dataStart: Long, spc: Int, bps: Int, outFile: File, fatType: Int) {
        java.io.BufferedOutputStream(outFile.outputStream(), 128 * 1024).use { out ->
            var currentCluster = firstCluster
            var remaining = size
            val buffer = ByteArray(spc * bps)

            while (remaining > 0) {
                if (currentCluster < 2) break
                val offset = dataStart + (currentCluster - 2).toLong() * spc * bps
                disk.seek(offset)
                val toRead = Math.min(buffer.size.toLong(), remaining).toInt()
                disk.read(buffer, 0, toRead)
                out.write(buffer, 0, toRead)
                remaining -= toRead

                // Get next cluster
                disk.seek(fatStart + when(fatType) {
                    12 -> (currentCluster * 3) / 2L
                    32 -> currentCluster * 4L
                    else -> currentCluster * 2L
                })

                currentCluster = when(fatType) {
                    12 -> {
                        val b1 = disk.read() and 0xFF
                        val b2 = disk.read() and 0xFF
                        if (currentCluster % 2 == 0) (b1 or ((b2 and 0x0F) shl 8)) else ((b1 shr 4) or (b2 shl 4))
                    }
                    32 -> {
                        val b = ByteArray(4)
                        disk.read(b, 0, 4)
                        getIntLE(b, 0) and 0x0FFFFFFF
                    }
                    else -> (disk.read() and 0xFF) or ((disk.read() and 0xFF) shl 8)
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
                            java.io.BufferedOutputStream(outFile.outputStream(), 128 * 1024).use { out ->
                                val buffer = ByteArray(65536)
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
                                    java.io.BufferedOutputStream(outFile.outputStream(), 128 * 1024).use { out ->
                                        val buffer = ByteArray(65536)
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

    private fun putLongBE(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value shr 56) and 0xFF).toByte()
        data[offset + 1] = ((value shr 48) and 0xFF).toByte()
        data[offset + 2] = ((value shr 40) and 0xFF).toByte()
        data[offset + 3] = ((value shr 32) and 0xFF).toByte()
        data[offset + 4] = ((value shr 24) and 0xFF).toByte()
        data[offset + 5] = ((value shr 16) and 0xFF).toByte()
        data[offset + 6] = ((value shr 8) and 0xFF).toByte()
        data[offset + 7] = (value and 0xFF).toByte()
    }

    fun createQcow2(outputFile: File, sizeMB: Double, label: String) {
        val raf = RandomAccessFile(outputFile, "rw")
        try {
            raf.setLength(0)
            val clusterBits = 16
            val clusterSize = 1 shl clusterBits
            val diskSize = (sizeMB * 1024 * 1024).toLong()

            // Strictly cluster-aligned layout:
            // Cluster 0: Header
            // Cluster 1: Refcount Table
            // Cluster 2: Refcount Block 0
            // Cluster 3: L1 Table
            // Cluster 4: L2 Table 0
            // Cluster 5: Data Cluster 0 (Boot Sector)

            val l2Entries = clusterSize / 8
            val l1Size = ((diskSize / clusterSize) + l2Entries - 1) / l2Entries

            val reftableOffset = clusterSize.toLong()
            val refblockOffset = 2L * clusterSize
            val l1Offset = 3L * clusterSize
            val l2Offset = 4L * clusterSize
            val dataOffset = 5L * clusterSize

            // 1. Header (v3)
            val header = ByteArray(clusterSize)
            header[0] = 0x51; header[1] = 0x46; header[2] = 0x49; header[3] = 0xFB.toByte()
            putIntBE(header, 4, 3) // Version 3
            header[23] = clusterBits.toByte()
            putLongBE(header, 24, diskSize)
            putIntBE(header, 32, 0) // Crypt
            putIntBE(header, 36, l1Size.toInt())
            putLongBE(header, 40, l1Offset)
            putLongBE(header, 48, reftableOffset)
            putIntBE(header, 56, 1) // 1 cluster for reftable
            header[99] = 4.toByte() // Refcount order = 4 (16 bits)
            putIntBE(header, 100, 104) // Header length

            raf.seek(0)
            raf.write(header)

            // 2. Refcount Table (pointing to Refcount Block 0)
            val refTable = ByteArray(clusterSize)
            putLongBE(refTable, 0, refblockOffset)
            raf.seek(reftableOffset)
            raf.write(refTable)

            // 3. Refcount Block (Mark clusters 0-5 as used)
            val refBlock = ByteArray(clusterSize)
            for (i in 0..5) {
                refBlock[i * 2] = 0
                refBlock[i * 2 + 1] = 1
            }
            raf.seek(refblockOffset)
            raf.write(refBlock)

            // 4. L1 Table (pointing to L2 Table 0)
            val l1Table = ByteArray(clusterSize)
            putLongBE(l1Table, 0, l2Offset or (1L shl 63))
            raf.seek(l1Offset)
            raf.write(l1Table)

            // 5. L2 Table (pointing to data cluster 5)
            val l2Table = ByteArray(clusterSize)
            putLongBE(l2Table, 0, dataOffset or (1L shl 63))
            raf.seek(l2Offset)
            raf.write(l2Table)

            // 6. FAT Formatting in Cluster 5
            val boot = createFatBootSector(diskSize / 512, label)
            raf.seek(dataOffset)
            raf.write(boot)

            val isFat32 = (diskSize / 512) > 1048576
            val fatInit = ByteArray(512)
            if (isFat32) {
                fatInit[0] = 0xF8.toByte(); fatInit[1] = 0xFF.toByte(); fatInit[2] = 0xFF.toByte(); fatInit[3] = 0x0F.toByte()
                fatInit[4] = 0xFF.toByte(); fatInit[5] = 0xFF.toByte(); fatInit[6] = 0xFF.toByte(); fatInit[7] = 0x0F.toByte()
                fatInit[8] = 0xFF.toByte(); fatInit[9] = 0xFF.toByte(); fatInit[10] = 0xFF.toByte(); fatInit[11] = 0x0F.toByte()
            } else {
                fatInit[0] = 0xF8.toByte(); fatInit[1] = 0xFF.toByte(); fatInit[2] = 0xFF.toByte(); fatInit[3] = 0xFF.toByte()
            }

            val reservedSectors = if (isFat32) 32 else 1
            raf.seek(dataOffset + reservedSectors.toLong() * 512)
            raf.write(fatInit) // FAT1

            // Ensure the file covers the last metadata/initial data cluster
            raf.seek(dataOffset + clusterSize - 1)
            raf.write(0)

        } finally { raf.close() }
    }

    private fun createFatBootSector(totalSectors: Long, label: String): ByteArray {
        val boot = ByteArray(512)
        boot[0] = 0xEB.toByte(); boot[1] = 0x3C.toByte(); boot[2] = 0x90.toByte()
        System.arraycopy("MSDOS5.0".toByteArray(), 0, boot, 3, 8)
        putShortLE(boot, 11, 512)

        val spc = when {
            totalSectors < 2048 -> 1
            totalSectors < 8192 -> 2
            totalSectors < 32768 -> 4
            totalSectors < 262144 -> 8
            totalSectors < 1048576 -> 16
            else -> 32
        }
        boot[13] = spc.toByte()

        val isFat32 = totalSectors > 1048576
        if (!isFat32) {
            putShortLE(boot, 14, 1)
            boot[16] = 2
            putShortLE(boot, 17, 512)
            if (totalSectors < 65536) putShortLE(boot, 19, totalSectors.toInt())
            else putIntLE(boot, 32, totalSectors.toInt())
            boot[21] = 0xF8.toByte()
            val sectorsPerFat = ((totalSectors / spc) * 2 / 512 + 1).toInt()
            putShortLE(boot, 22, sectorsPerFat)
            boot[38] = 0x29.toByte()
            System.arraycopy(label.uppercase().padEnd(11).toByteArray(), 0, boot, 43, 11)
            System.arraycopy("FAT16   ".toByteArray(), 0, boot, 54, 8)
        } else {
            putShortLE(boot, 14, 32)
            boot[16] = 2
            putShortLE(boot, 17, 0)
            putShortLE(boot, 19, 0)
            boot[21] = 0xF8.toByte()
            putShortLE(boot, 22, 0)
            putIntLE(boot, 32, totalSectors.toInt())
            val sectorsPerFat = ((totalSectors / spc) * 4 / 512 + 1).toInt()
            putIntLE(boot, 36, sectorsPerFat)
            putIntLE(boot, 44, 2)
            boot[66] = 0x29.toByte()
            System.arraycopy(label.uppercase().padEnd(11).toByteArray(), 0, boot, 71, 11)
            System.arraycopy("FAT32   ".toByteArray(), 0, boot, 82, 8)
        }
        boot[510] = 0x55.toByte(); boot[511] = 0xAA.toByte()
        return boot
    }

    fun createImg(outputFile: File, sizeMB: Double, label: String) {
        val raf = RandomAccessFile(outputFile, "rw")
        try {
            val totalSectors = (sizeMB * 1024 * 1024 / 512).toLong()
            raf.setLength(totalSectors * 512)

            val boot = createFatBootSector(totalSectors, label)
            raf.seek(0)
            raf.write(boot)

            val isFat32 = totalSectors > 1048576
            val reservedSectors = if (isFat32) 32 else 1
            val fatInit = ByteArray(512)
            if (isFat32) {
                fatInit[0] = 0xF8.toByte(); fatInit[1] = 0xFF.toByte(); fatInit[2] = 0xFF.toByte(); fatInit[3] = 0x0F.toByte()
                fatInit[4] = 0xFF.toByte(); fatInit[5] = 0xFF.toByte(); fatInit[6] = 0xFF.toByte(); fatInit[7] = 0x0F.toByte()
                fatInit[8] = 0xFF.toByte(); fatInit[9] = 0xFF.toByte(); fatInit[10] = 0xFF.toByte(); fatInit[11] = 0x0F.toByte()
            } else {
                fatInit[0] = 0xF8.toByte(); fatInit[1] = 0xFF.toByte(); fatInit[2] = 0xFF.toByte(); fatInit[3] = 0xFF.toByte()
            }

            raf.seek(reservedSectors.toLong() * 512)
            raf.write(fatInit)

        } finally { raf.close() }
    }
}
