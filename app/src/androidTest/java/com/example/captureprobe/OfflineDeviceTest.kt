package com.example.captureprobe

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real-device, file-only engine integration test. Does not request projection or record. */
@RunWith(AndroidJUnit4::class)
class OfflineDeviceTest {
    @Test fun trustedModelImportAndChineseSpeech() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val zip = File(context.filesDir, "model-test.zip")
        assertTrue("Provision model-test.zip with push-private-file.ps1", zip.isFile)
        val model = ModelStore.import(context, Uri.fromFile(zip)) { }
        val source = File(context.filesDir, "test-zh.wav")
        assertTrue("Provision official test-zh.wav", source.isFile)
        val normalized = File(context.cacheDir, "test-normalized.wav")
        normalized.delete()
        val total = AudioNormalizer.normalize(source, normalized, { false }) { }
        val info = inspectWav(normalized)!!
        assertEquals(16000, info.rate)
        assertTrue(total > 16000)
        val asr = OfflineRecognizer(config = OfflineRecognizerConfig(featConfig = FeatureConfig(16000, 80),
            modelConfig = OfflineModelConfig(senseVoice = OfflineSenseVoiceModelConfig(
                model = model.file("asr/model.int8.onnx"), language = "zh", useInverseTextNormalization = false),
                tokens = model.file("asr/tokens.txt"), numThreads = 2, provider = "cpu")))
        val punct = OfflinePunctuation(config = OfflinePunctuationConfig(OfflinePunctuationModelConfig(
            ctTransformer = model.file("punct/model.int8.onnx"), numThreads = 1)))
        val vad = Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
            model = model.file("vad/silero_vad.onnx"), threshold = 0.35f, minSilenceDuration = 0.5f,
            minSpeechDuration = 0.1f, maxSpeechDuration = 20f)))
        val resultSegments = JSONArray()
        val hypothesis = StringBuilder()
        val started = System.nanoTime()
        try {
            RandomAccessFile(normalized, "r").use { file ->
                file.seek(info.offset)
                val bytes = ByteArray(1024)
                var read = 0L
                fun drain() {
                    while (!vad.empty()) {
                        val speech = vad.front()
                        val stream = asr.createStream()
                        try {
                            stream.acceptWaveform(speech.samples, 16000); asr.decode(stream)
                            val raw = asr.getResult(stream).text
                            val readable = TranscriptText.conservative(raw, punct.addPunctuation(raw))
                            hypothesis.append(raw)
                            resultSegments.put(JSONObject().put("startSample", speech.start).put("samples", speech.samples.size)
                                .put("raw", raw).put("readable", readable))
                        } finally { stream.release() }
                        vad.pop()
                    }
                }
                while (read < total) {
                    val n = minOf(512L, total - read).toInt()
                    file.readFully(bytes, 0, n * 2)
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    vad.acceptWaveform(FloatArray(512) { if (it < n) buffer.short / 32768f else 0f })
                    read += n; drain()
                }
                vad.flush(); drain()
            }
            val reference = "开饭时间早上九点至下午五点"
            val cer = Accuracy.evaluate(reference, hypothesis.toString())
            File(context.filesDir, "device-engine-result.json").writeText(JSONObject()
                .put("model", model.id).put("hypothesis", hypothesis.toString()).put("reference", reference)
                .put("cer", cer.cer).put("durationSeconds", total / 16000.0)
                .put("inferenceSeconds", (System.nanoTime() - started) / 1e9).put("segments", resultSegments).toString(2))
            assertTrue("No speech was transcribed", hypothesis.isNotBlank())
            assertTrue("Official Chinese sample CER too high: ${cer.cer}", cer.cer <= 0.15)
        } finally { vad.release(); punct.release(); asr.release(); normalized.delete() }
    }
}
