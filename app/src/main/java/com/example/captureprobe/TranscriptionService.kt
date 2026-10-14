package com.example.captureprobe

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AsrStatus(val active: Boolean = false, val runId: String = "", val progress: Int = 0, val message: String = "")
object AsrState { val status = MutableStateFlow(AsrStatus()) }

class TranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var task: Job? = null
    @Volatile private var pauseRequested = false
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) { pauseRequested = true; return START_NOT_STICKY }
        if (task != null) return START_NOT_STICKY
        val id = intent?.getStringExtra("runId") ?: run { stopSelf(); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "离线转写进度", NotificationManager.IMPORTANCE_LOW))
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(2, notification("正在准备本地识别", 0), type)
            pauseRequested = false
            AsrState.status.value = AsrStatus(true, id, 0, "准备离线转写")
            task = scope.launch { process(id) }
        } catch (e: Exception) {
            AsrState.status.value = AsrStatus(message = "启动转写失败：${e.message}")
            ProbeState.log("转写服务启动失败：${e.message}"); stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun process(id: String) {
        var recognizer: OfflineRecognizer? = null
        var punctuation: OfflinePunctuation? = null
        var vad: Vad? = null
        var wake: PowerManager.WakeLock? = null
        var completed = false
        try {
            offlineApp().initialized.await()
            val run = dao().run(id) ?: error("找不到转写任务")
            val audio = dao().audio(run.audioId) ?: error("找不到音频记录")
            check(File(audio.path).isFile) { "音频已删除" }
            val model = ModelStore.get(this, run.modelId)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OfflineNotes:asr")
            wake.acquire(3 * 3600 * 1000L)
            val started = SystemClock.elapsedRealtime()
            val normalized = File(filesDir, "processing/${audio.id}.wav")
            update(id, "DECODING", 0, "本地解码和重采样")
            if (!normalized.exists()) {
                var last = 0L
                AudioNormalizer.normalize(File(audio.path), normalized, { pauseRequested }) { frames ->
                    if (frames - last >= 16000 * 2) {
                        AsrState.status.value = AsrStatus(true, id, 0, "已解码 ${frames / 16000} 秒")
                        last = frames
                    }
                }
            }
            val info = inspectWav(normalized) ?: error("解码输出不是 WAV")
            require(info.rate == 16000 && info.channels == 1 && info.bits == 16)
            val total = info.bytes / 2
            require(total > 0) { "音频为空" }
            dao().duration(audio.id, total * 1000 / 16000); dao().total(id, total)
            if (pauseRequested) throw InterruptedException("已暂停，解码结果已保存")
            val memory = ActivityManager.MemoryInfo()
            getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            check(!memory.lowMemory && memory.availMem >= 350L * 1024 * 1024) { "可用内存过低，请关闭其他大型应用后重试" }
            update(id, "RUNNING", 0, "加载本地 ${model.name}")
            val config = OfflineModelConfig(numThreads = 2, provider = "cpu")
            when (model.engine) {
                "sensevoice" -> {
                    config.senseVoice = OfflineSenseVoiceModelConfig(model = model.file("asr/model.int8.onnx"),
                        language = run.language, useInverseTextNormalization = false)
                    config.tokens = model.file("asr/tokens.txt")
                }
                "paraformer" -> {
                    config.paraformer = OfflineParaformerModelConfig(model = model.file("asr/model.int8.onnx"))
                    config.tokens = model.file("asr/tokens.txt"); config.modelType = "paraformer"
                }
                "qwen3" -> config.qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = model.file("asr/conv_frontend.int8.onnx"), encoder = model.file("asr/encoder.int8.onnx"),
                    decoder = model.file("asr/decoder.int8.onnx"), tokenizer = File(model.dir, "asr/tokenizer").absolutePath)
            }
            val asr = OfflineRecognizer(config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80), modelConfig = config))
            recognizer = asr
            val punct = OfflinePunctuation(config = OfflinePunctuationConfig(model = OfflinePunctuationModelConfig(
                ctTransformer = model.file("punct/model.int8.onnx"), numThreads = 1, provider = "cpu")))
            punctuation = punct
            val detector = Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                model = model.file("vad/silero_vad.onnx"), threshold = 0.35f, minSilenceDuration = 0.5f,
                minSpeechDuration = 0.1f, maxSpeechDuration = 20f), sampleRate = 16000, numThreads = 1, provider = "cpu"))
            vad = detector
            var decodedSegments = dao().segmentList(id).size
            val power = getSystemService(PowerManager::class.java)
            suspend fun drain() {
                while (!detector.empty()) {
                    if (pauseRequested) throw InterruptedException("已暂停；恢复时跳过已保存语音段")
                    if (SystemClock.elapsedRealtime() - started >= 3 * 3600 * 1000L) throw InterruptedException("单次处理 3 小时上限，已保存进度，请恢复")
                    val speech = detector.front()
                    val start = maxOf(0L, speech.start.toLong())
                    val end = minOf(total, start + speech.samples.size)
                    if (end > start && !dao().hasSegment(id, start)) {
                        var waited = 0
                        while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                            update(id, "RUNNING", (start * 100 / total).toInt(), "设备较热，等待降温")
                            if (pauseRequested || waited++ >= 300) throw InterruptedException("设备温度较高，任务已暂停，可稍后恢复")
                            delay(1000)
                        }
                        update(id, "RUNNING", (start * 100 / total).toInt(), "识别第 ${decodedSegments + 1} 段 · ${TranscriptText.stamp(start)}")
                        val stream = asr.createStream()
                        try {
                            val actualSamples = speech.samples.copyOf((end - start).toInt())
                            stream.acceptWaveform(actualSamples, 16000)
                            asr.decode(stream)
                            val result = asr.getResult(stream)
                            val raw = result.text.trim()
                            var note = ""
                            val punctuated = if (raw.isBlank()) "" else runCatching { punct.addPunctuation(raw) }.getOrElse {
                                note = "标点处理失败，保留原文：${it.message}"; raw
                            }
                            val readable = TranscriptText.conservative(raw, punctuated)
                            if (readable == raw && punctuated.trim() != raw)
                                note = "标点模型改变了词句或数字，已拒绝并保留原文"
                            if (raw.isBlank()) note = "此语音段识别为空，可能漏词；请回听"
                            val times = JSONArray()
                            result.timestamps.forEach { times.put(start / 16000.0 + it) }
                            dao().insertSegment(TextSegment(id, start, end, raw, readable,
                                JSONArray(result.tokens.toList()).toString(), times.toString(), result.lang, note))
                            dao().checkpoint(id, end)
                            decodedSegments++
                        } finally { stream.release() }
                    }
                    detector.pop()
                }
            }
            RandomAccessFile(normalized, "r").use { file ->
                file.seek(info.offset)
                val buffer = ByteArray(1024)
                var consumed = 0L
                var lastProgress = -1
                while (consumed < total) {
                    if (pauseRequested) throw InterruptedException("已暂停；语音段已保存")
                    val n = minOf(512L, total - consumed).toInt()
                    file.readFully(buffer, 0, n * 2)
                    val view = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                    val samples = FloatArray(512) { if (it < n) view.short / 32768f else 0f }
                    detector.acceptWaveform(samples); consumed += n
                    drain()
                    val p = (consumed * 100 / total).toInt().coerceAtMost(99)
                    if (p != lastProgress) { update(id, "RUNNING", p, "本地处理 $p% · 已保存 $decodedSegments 段"); lastProgress = p }
                }
                detector.flush(); drain()
            }
            val segments = dao().segmentList(id)
            val message = if (segments.none { it.raw.isNotBlank() }) "未识别出讲话；检查源文件、VAD 或捕获是否受限，不补写文字"
                else "完成，共 ${segments.size} 段；识别可能有错漏，请回听核对"
            update(id, "DONE", 100, message)
            completed = true
        } catch (e: InterruptedException) {
            val progress = AsrState.status.value.progress
            runCatching { update(id, "PAUSED", progress, e.message ?: "已暂停") }
        } catch (e: Throwable) {
            runCatching { update(id, "ERROR", AsrState.status.value.progress, "${e.javaClass.simpleName}: ${e.message}") }
            ProbeState.log("离线转写失败：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { vad?.release() }; runCatching { punctuation?.release() }; runCatching { recognizer?.release() }
            wake?.let { if (it.isHeld) it.release() }
            val final = AsrState.status.value
            main.post {
                task = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                AsrState.status.value = final.copy(active = false)
                if (completed) ProbeState.log("离线转写已完成：$id")
            }
        }
    }

    private suspend fun update(id: String, state: String, progress: Int, message: String) {
        dao().status(id, state, progress, message)
        AsrState.status.value = AsrStatus(true, id, progress, message)
        getSystemService(NotificationManager::class.java).notify(2, notification(message, progress))
    }
    private fun notification(message: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 3, Intent(this, TranscriptionService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("小棋听记 · 转写 $progress%").setContentText(message).setContentIntent(open)
            .setProgress(100, progress, progress == 0).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "暂停", pause).build()).build()
    }
    override fun onDestroy() { pauseRequested = true; super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { pauseRequested = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    companion object { const val PAUSE = "com.example.captureprobe.PAUSE"; private const val CHANNEL = "asr" }
}
