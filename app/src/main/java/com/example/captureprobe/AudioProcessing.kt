package com.example.captureprobe

import android.media.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Bounded, windowed-sinc resampler. Preserve duration; low-pass before downsampling. */
class PcmResampler(private val rate: Int, private val output: (Float) -> Unit) {
    private val ring = FloatArray(64)
    private var input = 0L
    private var produced = 0L
    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    private val factor = gcd(rate, 16000)
    private val phases = 16000 / factor
    private val weights = Array(phases) { phase ->
        val fraction = phase.toDouble() / phases
        val cutoff = min(1.0, 16000.0 / rate) * 0.94
        val taps = DoubleArray(48) { i ->
            val delta = i - 23 - fraction
            val x = PI * cutoff * delta
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(x) / x
            cutoff * sinc * (if (abs(delta) < 24) 0.5 * (1 + cos(PI * delta / 24)) else 0.0)
        }
        val sum = taps.sum()
        FloatArray(48) { (taps[it] / sum).toFloat() }
    }
    init { require(rate in 8000..192000) }
    fun accept(sample: Float) {
        if (rate == 16000) { output(sample); input++; produced++; return }
        ring[(input % 64).toInt()] = sample
        input++
        while ((produced * rate / 16000) + 24 < input) emit()
    }
    private fun emit() {
        val numerator = produced * rate
        val center = numerator / 16000
        val phase = ((numerator % 16000) / factor).toInt()
        var value = 0f
        for (i in 0..47) {
            val index = center + i - 23
            if (index >= 0 && index < input) value += ring[(index % 64).toInt()] * weights[phase][i]
        }
        output(value.coerceIn(-1f, 1f)); produced++
    }
    fun finish() {
        if (rate == 16000) return
        val realInput = input
        val count = realInput * 16000 / rate
        while (produced < count) {
            ring[(input % 64).toInt()] = 0f; input++
            while (produced < count && (produced * rate / 16000) + 24 < input) emit()
        }
    }
}

data class WavInfo(val rate: Int, val channels: Int, val bits: Int, val format: Int, val offset: Long, val bytes: Long)

fun inspectWav(file: File): WavInfo? = RandomAccessFile(file, "r").use { input ->
    if (input.length() < 12) return@use null
    fun tag(): String { val bytes = ByteArray(4); input.readFully(bytes); return String(bytes, Charsets.US_ASCII) }
    fun uint(): Long = java.lang.Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
    if (tag() != "RIFF") return@use null
    uint(); require(tag() == "WAVE") { "不是 WAVE 音频" }
    var rate = 0; var channels = 0; var bits = 0; var format = 0
    while (input.filePointer + 8 <= input.length()) {
        val name = tag(); val size = uint(); val begin = input.filePointer
        require(begin + size <= input.length()) { "WAV 文件截断，请先恢复录音" }
        if (name == "fmt ") {
            require(size >= 16)
            format = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
            channels = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
            rate = uint().toInt(); uint(); input.skipBytes(2)
            bits = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
        } else if (name == "data") {
            require(rate in 8000..192000 && channels in 1..8)
            require((format == 1 && bits == 16) || (format == 3 && bits == 32)) { "WAV 仅支持 PCM16 / float32，可改用设备支持的 M4A/MP4" }
            require(size % (channels * bits / 8) == 0L) { "WAV 尾部样本不完整" }
            return@use WavInfo(rate, channels, bits, format, begin, size)
        }
        input.seek(begin + size + size % 2)
    }
    error("WAV 缺少 data 块")
}

/** Decode imported audio/video with Android codecs; no network or microphone input. */
object AudioNormalizer {
    fun normalize(source: File, destination: File, stop: () -> Boolean, progress: (Long) -> Unit): Long {
        destination.parentFile!!.mkdirs()
        val pending = File(destination.parentFile, destination.name + ".part")
        var count = 0L
        try {
            WavFile(pending, 16000).use { wav ->
                val pcm = ByteArray(8192)
                var position = 0
                fun flush() { if (position > 0) { wav.append(pcm, position); position = 0 } }
                fun emit(value: Float) {
                    val sample = (value * 32768).roundToInt().coerceIn(-32768, 32767)
                    pcm[position++] = sample.toByte(); pcm[position++] = (sample shr 8).toByte()
                    count++
                    if (position == pcm.size) {
                        if (stop()) throw InterruptedException("处理已暂停")
                        check(pending.parentFile!!.usableSpace > 32L * 1024 * 1024) { "解码存储空间不足" }
                        require(count <= 16000L * 24 * 3600) { "单文件上限 24 小时" }
                        flush(); progress(count)
                    }
                }
                val info = inspectWav(source)
                if (info != null) {
                    val resample = PcmResampler(info.rate, ::emit)
                    RandomAccessFile(source, "r").use { input ->
                        input.seek(info.offset)
                        val frameBytes = info.channels * info.bits / 8
                        val bytes = ByteArray(frameBytes * 1024)
                        var remaining = info.bytes
                        while (remaining > 0) {
                            val n = minOf(remaining, bytes.size.toLong()).toInt()
                            input.readFully(bytes, 0, n); remaining -= n
                            val buffer = ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                            while (buffer.remaining() >= frameBytes) {
                                var sum = 0f
                                repeat(info.channels) { sum += if (info.bits == 16) buffer.short / 32768f else buffer.float }
                                resample.accept(if (sum.isFinite()) (sum / info.channels).coerceIn(-1f, 1f) else 0f)
                            }
                        }
                    }
                    resample.finish()
                } else decodeMedia(source, stop, ::emit)
                flush()
            }
            check(pending.renameTo(destination)) { "保存解码文件失败" }
            return count
        } catch (e: Throwable) { pending.delete(); throw e }
    }

    private fun decodeMedia(file: File, stop: () -> Boolean, emit: (Float) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            require(extractor.drmInitData == null) { "不处理 DRM 媒体" }
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("没有音轨")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            require(mime != "audio/raw") { "此原始 PCM 格式未支持，请导入标准 PCM16 WAV" }
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resample: PcmResampler? = null
            var inputEnded = false
            var outputEnded = false
            var lastOutput = System.nanoTime()
            var sampleFrames = 0L
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                if (stop()) throw InterruptedException("处理已暂停")
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                        } else {
                            require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "不处理加密音轨" }
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val changed = decoder.outputFormat
                    val newRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val newChannels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(sampleFrames == 0L || (newRate == rate && newChannels == channels)) { "音轨格式中途变化，无法保证时间戳" }
                    rate = newRate; channels = newChannels
                    encoding = if (changed.containsKey(MediaFormat.KEY_PCM_ENCODING)) changed.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                } else if (index >= 0) {
                    try {
                        val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        if (resample == null) resample = PcmResampler(rate, emit)
                        require(channels in 1..8 && encoding in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) { "解码器返回不支持的 PCM 格式" }
                        val frameBytes = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        require(info.size % frameBytes == 0)
                        if (info.size > 0) {
                            lastOutput = System.nanoTime()
                            // Preserve significant timeline gaps, including delayed audio in a video.
                            val expectedFrame = maxOf(0L, info.presentationTimeUs * rate / 1_000_000)
                            val gap = expectedFrame - sampleFrames
                            if (gap > rate / 20) {
                                require(gap < rate * 3600L) { "音轨时间戳间隙过大" }
                                var remaining = gap
                                while (remaining-- > 0) { resample.accept(0f); sampleFrames++ }
                            }
                            while (buffer.remaining() >= frameBytes) {
                                var sum = 0f
                                repeat(channels) { sum += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float else buffer.short / 32768f }
                                resample.accept(if (sum.isFinite()) (sum / channels).coerceIn(-1f, 1f) else 0f); sampleFrames++
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(index, false) }
                }
                check(System.nanoTime() - lastOutput < 30_000_000_000L) { "解码器 30 秒未输出 PCM" }
            }
            resample?.finish()
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}
