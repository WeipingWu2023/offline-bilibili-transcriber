package com.example.captureprobe

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileTest {
    @Test fun pcmAndCrashRecovery() {
        val dir = kotlin.io.path.createTempDirectory("wav-test").toFile()
        try {
            val file = File(dir, "capture.wav.part")
            WavFile(file, 48000).use { wav ->
                val pcm = byteArrayOf(0, -128, -1, 127, 0, 0)
                wav.append(pcm, pcm.size)
                val level = pcmLevel(pcm, pcm.size)
                assertEquals(32768, level.peak)
                assertFalse(level.allZero)
                assertTrue(level.db > -3)
                assertTrue(pcmLevel(ByteArray(20), 20).allZero)
                assertEquals(-120.0, pcmLevel(ByteArray(20), 20).db, 0.0)
            }
            // Simulate PCM written after the last header checkpoint, including a torn final sample.
            RandomAccessFile(file, "rw").use { it.seek(it.length()); it.write(byteArrayOf(1, 0, 9)) }
            val recovered = WavFile.recover(file)
            val bytes = recovered.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(48000, header.getInt(24))
            assertEquals(bytes.size - 8, header.getInt(4))
            assertEquals(8, header.getInt(40))
            assertEquals(52, bytes.size)
            assertFalse(file.exists())
        } finally { dir.deleteRecursively() }
    }
}
