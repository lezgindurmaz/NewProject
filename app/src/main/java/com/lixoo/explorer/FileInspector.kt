package com.lixoo.explorer

import java.io.File
import java.io.RandomAccessFile

object FileInspector {
    enum class FileType { DIRECTORY, IMAGE, ARCHIVE, DISK_IMAGE, AUDIO, VIDEO, TEXT, PDF, HTML, UNKNOWN }

    fun getType(file: File): FileType {
        if (file.isDirectory) return FileType.DIRECTORY
        if (!file.exists() || file.length() == 0L) return FileType.UNKNOWN

        val bytes = ByteArray(16)
        val raf = try { RandomAccessFile(file, "r") } catch (e: Exception) { return FileType.UNKNOWN }
        try {
            raf.read(bytes)

            if (bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))) return FileType.IMAGE
            if (bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))) return FileType.IMAGE
            if (bytes.startsWith(byteArrayOf(0x47, 0x49, 0x46, 0x38))) return FileType.IMAGE

            if (bytes.startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04))) return FileType.ARCHIVE
            if (bytes.startsWith(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte()))) return FileType.ARCHIVE
            if (bytes.startsWith(byteArrayOf(0x1F, 0x8B.toByte()))) return FileType.ARCHIVE
            if (bytes.startsWith(byteArrayOf(0x42, 0x5A, 0x68))) return FileType.ARCHIVE
            if (bytes.startsWith(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00))) return FileType.ARCHIVE
            if (bytes.startsWith(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07))) return FileType.ARCHIVE // RAR

            if (bytes.startsWith(byteArrayOf(0x51, 0x46, 0x49, 0xFB.toByte()))) return FileType.DISK_IMAGE
            if (file.length() >= 512) {
                raf.seek(510)
                if (raf.read() == 0x55 && raf.read() == 0xAA) return FileType.DISK_IMAGE
            }
            if (file.length() >= 32768 + 2048) {
                raf.seek(32768 + 1)
                val id = ByteArray(5)
                raf.read(id)
                if (id.contentEquals("CD001".toByteArray())) return FileType.DISK_IMAGE
            }

            if (bytes.startsWith(byteArrayOf(0x49, 0x44, 0x33))) return FileType.AUDIO
            if (bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFB.toByte()))) return FileType.AUDIO
            if (bytes.startsWith(byteArrayOf(0x4F, 0x67, 0x67, 0x53))) return FileType.AUDIO
            if (bytes.startsWith(byteArrayOf(0x66, 0x4C, 0x61, 0x43))) return FileType.AUDIO

            if (bytes.startsWith(byteArrayOf(0x25, 0x50, 0x44, 0x46))) return FileType.PDF

            val head = String(bytes, Charsets.US_ASCII).lowercase()
            if (head.contains("<!doctype html") || head.contains("<html")) return FileType.HTML

            if (isProbablyText(bytes)) return FileType.TEXT

        } catch (e: Exception) {
        } finally {
            raf.close()
        }
        return FileType.UNKNOWN
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    private fun isProbablyText(bytes: ByteArray): Boolean {
        for (i in 0 until Math.min(bytes.size, 16)) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 32 && b != 9 && b != 10 && b != 13) return false
        }
        return true
    }
}
