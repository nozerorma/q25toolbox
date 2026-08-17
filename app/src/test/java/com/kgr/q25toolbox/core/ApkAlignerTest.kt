package com.kgr.q25toolbox.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Hand-builds a minimal ZIP (not via ZipOutputStream, which doesn't allow crafting
 * deliberately misaligned STORED entries) with two STORED entries whose natural
 * offsets land on non-4-byte boundaries, runs [ApkAligner] on it, and checks both
 * that every STORED entry's data now starts on a 4-byte boundary and that every
 * entry's bytes are byte-for-byte unchanged - i.e. this only ever inserts padding,
 * never touches content.
 */
class ApkAlignerTest {

    private data class Entry(val name: String, val data: ByteArray, val stored: Boolean)

    private fun buildZip(entries: List<Entry>, gapBeforeCentralDirectory: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val localOffsets = mutableListOf<Int>()

        for (entry in entries) {
            localOffsets += out.size()
            val nameBytes = entry.name.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(entry.data) }.value
            val method = if (entry.stored) 0 else 8
            val compressed = if (entry.stored) entry.data else deflate(entry.data)

            val header = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(0x04034b50)
            header.putShort(20)              // version needed
            header.putShort(0)               // gpbf
            header.putShort(method.toShort())
            header.putShort(0)               // mod time
            header.putShort(0)               // mod date
            header.putInt(crc.toInt())
            header.putInt(compressed.size)
            header.putInt(entry.data.size)
            header.putShort(nameBytes.size.toShort())
            header.putShort(0)               // extra length
            out.write(header.array())
            out.write(nameBytes)
            out.write(compressed)
        }

        if (gapBeforeCentralDirectory > 0) out.write(ByteArray(gapBeforeCentralDirectory))
        val cdStart = out.size()
        for ((i, entry) in entries.withIndex()) {
            val nameBytes = entry.name.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(entry.data) }.value
            val method = if (entry.stored) 0 else 8
            val compressed = if (entry.stored) entry.data else deflate(entry.data)

            val cd = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
            cd.putInt(0x02014b50)
            cd.putShort(20); cd.putShort(20)
            cd.putShort(0)
            cd.putShort(method.toShort())
            cd.putShort(0); cd.putShort(0)
            cd.putInt(crc.toInt())
            cd.putInt(compressed.size)
            cd.putInt(entry.data.size)
            cd.putShort(nameBytes.size.toShort())
            cd.putShort(0); cd.putShort(0)   // extraLen, commentLen
            cd.putShort(0)                   // disk number start
            cd.putShort(0)                   // internal attributes
            cd.putInt(0)                     // external attributes
            cd.putInt(localOffsets[i])
            out.write(cd.array())
            out.write(nameBytes)
        }
        val cdSize = out.size() - cdStart

        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        eocd.putInt(0x06054b50)
        eocd.putShort(0); eocd.putShort(0)
        eocd.putShort(entries.size.toShort())
        eocd.putShort(entries.size.toShort())
        eocd.putInt(cdSize)
        eocd.putInt(cdStart)
        eocd.putShort(0)
        out.write(eocd.array())

        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        // nowrap=true: ZIP's "deflated" method is raw DEFLATE, not zlib-wrapped.
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val buf = ByteArray(data.size + 64)
        val n = deflater.deflate(buf)
        return buf.copyOf(n)
    }

    @Test
    fun `aligns misaligned STORED entries and preserves content`() {
        // "a" (1-byte name) forces the next entry's natural offset off any 4-byte
        // boundary; "resources_arsc" stands in for the real file that PackageManager
        // actually checks.
        val entries = listOf(
            Entry("a", "X".toByteArray(), stored = true),
            Entry("resources_arsc", "0123456789".repeat(50).toByteArray(), stored = true),
            Entry("classes.dex", "compressme".repeat(200).toByteArray(), stored = false),
        )
        val input = buildZip(entries)
        assertTrue(
            "test fixture should start out misaligned (otherwise this test proves nothing)",
            findStoredOffsets(input).any { it % 4 != 0 }
        )

        val inFile = File.createTempFile("align_in", ".zip")
        val outFile = File.createTempFile("align_out", ".zip")
        inFile.writeBytes(input)

        ApkAligner.align(inFile, outFile)

        val alignedOffsets = findStoredOffsets(outFile.readBytes())
        assertTrue("every STORED entry must start on a 4-byte boundary after aligning",
            alignedOffsets.all { it % 4 == 0 })

        ZipFile(outFile).use { zip ->
            for (entry in entries) {
                val zipEntry = zip.getEntry(entry.name)
                assertEquals(entry.data.size.toLong(), zipEntry.size)
                val actual = zip.getInputStream(zipEntry).readBytes()
                assertArrayEquals("content for ${entry.name} must be byte-for-byte unchanged", entry.data, actual)
            }
        }

        inFile.delete()
        outFile.delete()
    }

    @Test
    fun `handles a signing block gap between the last entry and the central directory`() {
        // Regression test: an already-signed input APK has an APK Signing Block
        // (v2/v3) sitting between the last local entry's data and the Central
        // Directory - it isn't a local header and isn't part of any entry's
        // declared size, so walking "until we reach cdOffset" misparses it as a
        // corrupt local header. Caught against a real device-pulled apk; this
        // reproduces the same shape without needing that 27MB fixture in the repo.
        val entries = listOf(
            Entry("resources.arsc", "0123456789".repeat(50).toByteArray(), stored = true),
        )
        val input = buildZip(entries, gapBeforeCentralDirectory = 4096)

        val inFile = File.createTempFile("align_gap_in", ".zip")
        val outFile = File.createTempFile("align_gap_out", ".zip")
        inFile.writeBytes(input)

        ApkAligner.align(inFile, outFile)

        ZipFile(outFile).use { zip ->
            val entry = zip.getEntry("resources.arsc")
            assertArrayEquals(entries[0].data, zip.getInputStream(entry).readBytes())
        }

        inFile.delete()
        outFile.delete()
    }

    /** Returns, for every STORED entry, the byte offset its data starts at. */
    private fun findStoredOffsets(bytes: ByteArray): List<Int> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val offsets = mutableListOf<Int>()
        var pos = 0
        while (pos < bytes.size && buf.getInt(pos) == 0x04034b50) {
            val method = buf.getShort(pos + 8).toInt() and 0xFFFF
            val compressedSize = buf.getInt(pos + 18)
            val nameLen = buf.getShort(pos + 26).toInt() and 0xFFFF
            val extraLen = buf.getShort(pos + 28).toInt() and 0xFFFF
            val dataStart = pos + 30 + nameLen + extraLen
            if (method == 0) offsets += dataStart
            pos = dataStart + compressedSize
        }
        return offsets
    }
}
