package com.example.captureprobe

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/** PCM16 mono; .part marks a session that did not finish cleanly. */
class WavFile(val file: File, val sampleRate: Int) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    var dataBytes = 0L
        private set

    init { output.setLength(0); output.write(header(0, sampleRate)) }

    fun append(pcm: ByteArray, count: Int) {
        require(count in 0..pcm.size && count % 2 == 0)
        require(dataBytes + count <= 0xffff_ffffL - 36)
        output.write(pcm, 0, count)
        dataBytes += count
    }

    fun checkpoint() {
        val position = output.filePointer
        output.seek(0)
        output.write(header(dataBytes, sampleRate))
        output.seek(position)
        output.fd.sync()
    }

    override fun close() {
        try { checkpoint() } finally { output.close() }
    }

    companion object {
        fun header(bytes: Long, rate: Int): ByteArray = ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((bytes + 36).toInt())
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
                putShort(1.toShort()); putShort(1.toShort()); putInt(rate); putInt(rate * 2)
                putShort(2.toShort()); putShort(16.toShort())
                put("data".toByteArray(Charsets.US_ASCII)); putInt(bytes.toInt())
            }.array()

        fun recover(file: File): File {
            RandomAccessFile(file, "rw").use { raf ->
                require(raf.length() >= 44) { "WAV header incomplete" }
                val old = ByteArray(44); raf.readFully(old)
                require(String(old, 0, 4, Charsets.US_ASCII) == "RIFF")
                val rate = ByteBuffer.wrap(old).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
                require(rate == 48000 || rate == 44100 || rate == 16000)
                val bytes = (raf.length() - 44) / 2 * 2
                raf.setLength(bytes + 44); raf.seek(0); raf.write(header(bytes, rate)); raf.fd.sync()
            }
            val recovered = File(file.parentFile, file.name.removeSuffix(".wav.part") + "-interrupted.wav")
            check(file.renameTo(recovered)) { "Cannot rename recovered WAV" }
            return recovered
        }
    }
}

data class Level(val db: Double, val peak: Int, val allZero: Boolean)

fun pcmLevel(bytes: ByteArray, count: Int): Level {
    require(count > 0 && count <= bytes.size && count % 2 == 0)
    var sum = 0.0
    var peak = 0
    for (i in 0 until count step 2) {
        val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
        sum += sample.toDouble() * sample
        peak = maxOf(peak, kotlin.math.abs(sample))
    }
    val rms = sqrt(sum / (count / 2)) / 32768.0
    return Level(if (rms == 0.0) -120.0 else 20 * log10(rms), peak, peak == 0)
}
