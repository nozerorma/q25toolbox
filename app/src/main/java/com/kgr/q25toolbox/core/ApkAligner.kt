package com.kgr.q25toolbox.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reimplementation of `zipalign`'s core behavior: pads the local-header
 * extra field of every STORED (uncompressed) entry so its data starts on a
 * 4-byte boundary, and rewrites the Central Directory / EOCD offsets to match.
 *
 * Built specifically to fix PackageManager's install-time check ("Targeting R+
 * ... requires the resources.arsc of installed APKs to be stored uncompressed
 * and aligned on a 4-byte boundary") on whatever com.android.launcher3 build is
 * actually on the device - see RecentsTweaksController.repairRecentsProvider.
 * No native `zipalign` binary is bundled (would need a separate arm64 build),
 * so this exists to avoid that dependency entirely.
 *
 * Compressed entries and non-alignment-relevant bytes (existing extra field
 * content, comments) are copied through unchanged - this only ever *adds*
 * zero-fill padding, never rewrites entry data. Padding is written as raw zero
 * bytes rather than a structured extra-field TLV sub-record: nothing here (or
 * in PackageManager's own check) parses the extra field's inner structure,
 * only its declared length, which is exactly what real zipalign relies on too.
 */
object ApkAligner {

    private const val LOCAL_HEADER_SIG = 0x04034b50
    private const val CENTRAL_HEADER_SIG = 0x02014b50
    private const val EOCD_SIG = 0x06054b50
    private const val LOCAL_HEADER_FIXED_SIZE = 30
    private const val CENTRAL_HEADER_FIXED_SIZE = 46
    private const val EOCD_FIXED_SIZE = 22
    private const val ALIGNMENT = 4
    private const val GPBF_DATA_DESCRIPTOR = 0x0008

    class UnsupportedZipLayoutException(message: String) : Exception(message)

    /** Reads [input] fully into memory (APKs here are ~30MB, fine on-device) and
     * writes a 4-byte-aligned copy to [output]. Throws [UnsupportedZipLayoutException]
     * on anything this minimal implementation can't safely handle (streamed
     * entries via data descriptors, ZIP64) rather than silently producing a
     * corrupt APK - callers should treat that as "can't repair this one" and
     * fall back rather than retry.
     */
    fun align(input: File, output: File) {
        val bytes = RandomAccessFile(input, "r").use { raf ->
            val b = ByteArray(raf.length().toInt())
            raf.readFully(b)
            b
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val eocdOffset = findEocd(bytes)
        val cdOffset = buf.getInt(eocdOffset + 16).toUInt().toLong()
        val cdSize = buf.getInt(eocdOffset + 12).toUInt().toLong()
        if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
            throw UnsupportedZipLayoutException("ZIP64 central directory not supported")
        }
        val entryCount = buf.getShort(eocdOffset + 10).toInt() and 0xFFFF

        // Pass 1: walk local file headers, copy each entry (padding STORED ones),
        // and remember old->new local-header-offset so the Central Directory can
        // be repointed in pass 2.
        //
        // Bounded by entryCount (from EOCD), not by "until we reach cdOffset": a
        // signed APK has an APK Signing Block (v2/v3) sitting in the gap between
        // the last local entry and the Central Directory, which isn't a local
        // header and isn't part of any entry's declared size, so a
        // contiguity assumption breaks on every already-signed input. That gap is
        // simply skipped/discarded here - fine, since re-signing afterward writes
        // a brand new one anyway.
        val offsetMap = HashMap<Long, Long>()
        val outBytes = java.io.ByteArrayOutputStream(bytes.size + 4096)
        var readPos = 0L
        repeat(entryCount) {
            val sig = buf.getInt(readPos.toInt())
            if (sig != LOCAL_HEADER_SIG) {
                throw UnsupportedZipLayoutException("Expected local file header at $readPos, found ${Integer.toHexString(sig)}")
            }
            val gpbf = buf.getShort((readPos + 6).toInt()).toInt() and 0xFFFF
            if (gpbf and GPBF_DATA_DESCRIPTOR != 0) {
                throw UnsupportedZipLayoutException("Entry at $readPos uses a streaming data descriptor")
            }
            val method = buf.getShort((readPos + 8).toInt()).toInt() and 0xFFFF
            val compressedSize = buf.getInt((readPos + 18).toInt()).toUInt().toLong()
            val nameLen = buf.getShort((readPos + 26).toInt()).toInt() and 0xFFFF
            val extraLen = buf.getShort((readPos + 28).toInt()).toInt() and 0xFFFF

            val headerEnd = readPos + LOCAL_HEADER_FIXED_SIZE
            val nameEnd = headerEnd + nameLen
            val extraEnd = nameEnd + extraLen
            val dataEnd = extraEnd + compressedSize

            val newLocalOffset = outBytes.size().toLong()
            if (method == 0) {
                val dataStart = newLocalOffset + LOCAL_HEADER_FIXED_SIZE + nameLen + extraLen
                val pad = ((ALIGNMENT - (dataStart % ALIGNMENT)) % ALIGNMENT).toInt()
                val newExtraLen = extraLen + pad

                // Fixed header with the extra-length field (offset 28, 2 bytes) patched.
                val header = bytes.copyOfRange(readPos.toInt(), headerEnd.toInt())
                header[28] = (newExtraLen and 0xFF).toByte()
                header[29] = ((newExtraLen shr 8) and 0xFF).toByte()
                outBytes.write(header)
                outBytes.write(bytes, headerEnd.toInt(), nameLen) // filename
                outBytes.write(bytes, nameEnd.toInt(), extraLen)  // original extra field
                outBytes.write(ByteArray(pad))                    // alignment padding
                outBytes.write(bytes, extraEnd.toInt(), compressedSize.toInt()) // stored data
            } else {
                outBytes.write(bytes, readPos.toInt(), (dataEnd - readPos).toInt())
            }
            offsetMap[readPos] = newLocalOffset
            readPos = dataEnd
        }

        val newCdOffset = outBytes.size().toLong()

        // Pass 2: copy the Central Directory, repointing each record's local-header
        // offset field (at +42) via the map built in pass 1.
        var cdPos = cdOffset
        val cdEnd = cdOffset + cdSize
        while (cdPos < cdEnd) {
            val sig = buf.getInt(cdPos.toInt())
            if (sig != CENTRAL_HEADER_SIG) {
                throw UnsupportedZipLayoutException("Expected central directory header at $cdPos, found ${Integer.toHexString(sig)}")
            }
            val nameLen = buf.getShort((cdPos + 28).toInt()).toInt() and 0xFFFF
            val extraLen = buf.getShort((cdPos + 30).toInt()).toInt() and 0xFFFF
            val commentLen = buf.getShort((cdPos + 32).toInt()).toInt() and 0xFFFF
            val origLocalOffset = buf.getInt((cdPos + 42).toInt()).toUInt().toLong()
            val recordLen = CENTRAL_HEADER_FIXED_SIZE + nameLen + extraLen + commentLen

            val newLocalOffset = offsetMap[origLocalOffset]
                ?: throw UnsupportedZipLayoutException("No local entry found for central directory offset $origLocalOffset")

            val record = bytes.copyOfRange(cdPos.toInt(), (cdPos + recordLen).toInt())
            record[42] = (newLocalOffset and 0xFF).toByte()
            record[43] = ((newLocalOffset shr 8) and 0xFF).toByte()
            record[44] = ((newLocalOffset shr 16) and 0xFF).toByte()
            record[45] = ((newLocalOffset shr 24) and 0xFF).toByte()
            outBytes.write(record)

            cdPos += recordLen
        }

        // EOCD: identical except the central-directory-offset field (+16); size (+12)
        // is unchanged since record byte lengths never change, only the offset value
        // stored inside them.
        val eocdLen = bytes.size - eocdOffset
        val eocd = bytes.copyOfRange(eocdOffset, eocdOffset + eocdLen)
        eocd[16] = (newCdOffset and 0xFF).toByte()
        eocd[17] = ((newCdOffset shr 8) and 0xFF).toByte()
        eocd[18] = ((newCdOffset shr 16) and 0xFF).toByte()
        eocd[19] = ((newCdOffset shr 24) and 0xFF).toByte()
        outBytes.write(eocd)

        output.outputStream().use { it.write(outBytes.toByteArray()) }
    }

    private fun findEocd(bytes: ByteArray): Int {
        // No zip comment expected on a release APK, but scan back up to the max
        // comment length (64KB) plus the fixed EOCD size to be safe either way.
        val searchStart = maxOf(0, bytes.size - EOCD_FIXED_SIZE - 0xFFFF)
        for (i in bytes.size - EOCD_FIXED_SIZE downTo searchStart) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x05.toByte() && bytes[i + 3] == 0x06.toByte()
            ) {
                return i
            }
        }
        throw UnsupportedZipLayoutException("End Of Central Directory record not found")
    }
}
