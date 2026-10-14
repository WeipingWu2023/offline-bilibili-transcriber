package com.example.captureprobe

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/** One worker owns AudioRecord and WAV. Stop never releases a recorder during read. */
class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopRequested = false
    @Volatile private var stopReason = "用户停止"
    private var worker: Thread? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            requestStop("用户停止")
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY
        stopRequested = false
        stopReason = "用户停止"
        val command = intent ?: run { stopSelf(); return START_NOT_STICKY }
        val grant = if (Build.VERSION.SDK_INT >= 33) command.getParcelableExtra("grant", Intent::class.java)
            else @Suppress("DEPRECATION") (command.getParcelableExtra<Intent>("grant"))
        if (grant == null || command.getIntExtra("result", 0) != Activity.RESULT_OK) {
            ProbeState.log("拒绝启动：没有新授权令牌")
            stopSelf(); return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "内部音频录制", NotificationManager.IMPORTANCE_LOW)
            )
            startForeground(1, notification("正在初始化内部音频"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            ProbeState.status.update { it.copy(active = true, message = "初始化录音", elapsedMs = 0,
                audioMs = 0, db = -120.0, peak = 0, warning = "", file = "") }
            val uid = command.getIntExtra("uid", -1)
            require(uid >= 0) { "必须指定播放方 UID" }
            val target = command.getStringExtra("target") ?: "unknown"
            val version = command.getStringExtra("version") ?: "unknown"
            worker = thread(name = "playback-capture") { capture(grant, uid, target, version) }
        } catch (e: Exception) {
            ProbeState.log("前台服务启动失败 ${e.javaClass.simpleName}: ${e.message}")
            ProbeState.status.update { it.copy(active = false, message = "启动失败：${e.message}") }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY // Never replay a MediaProjection grant after process death.
    }

    private fun capture(grant: Intent, uid: Int, target: String, version: String) {
        var projection: MediaProjection? = null
        var recorder: AudioRecord? = null
        var wav: WavFile? = null
        var log: SessionLog? = null
        var savedFile: File? = null
        var finalMessage = "已停止"
        val callback = object : MediaProjection.Callback() {
            override fun onStop() { requestStop("系统撤销投影：锁屏、另一投影或用户停止；需重新授权") }
        }
        val start = SystemClock.elapsedRealtime()
        try {
            val dir = File(filesDir, "recordings").apply { check(mkdirs() || isDirectory) }
            val id = "capture-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
            log = SessionLog(File(dir, "$id.log"))
            log.write("device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} target=$target version=$version uid=$uid")
            log.write("仅 AudioPlaybackCapture，USAGE_MEDIA/GAME/UNKNOWN；无麦克风源，无网络权限")
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("RECORD_AUDIO 未授权或已撤销")
            check(dir.usableSpace > 32L * 1024 * 1024) { "存储空间不足 32 MiB" }
            val mp = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, grant)
            projection = mp
            mp.registerCallback(callback, main)
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUid(uid).build()
            // Native playback commonly uses 48 kHz. Keep raw capture; resampling belongs to Phase 1.
            val rate = 48000
            val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBytes > 0) { "getMinBufferSize=$minBytes" }
            val ar = AudioRecord.Builder().setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBytes * 4, rate / 5 * 2))
                .setAudioPlaybackCaptureConfig(config).build()
            recorder = ar
            check(ar.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 未初始化" }
            val pending = File(dir, "$id.wav.part")
            val output = WavFile(pending, rate)
            wav = output
            ProbeState.status.update { it.copy(file = pending.absolutePath) }
            if (!stopRequested) ar.startRecording()
            if (!stopRequested) check(ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord 未进入录制" }
            log.write("START rate=$rate channels=1 pcm=16 buffer=$minBytes session=${ar.audioSessionId}")
            val bytes = ByteArray(rate / 10 * 2)
            var lastRead = SystemClock.elapsedRealtime()
            var lastSignal = lastRead
            var lastUpdate = 0L
            var lastCheckpoint = lastRead
            var lastNotification = lastRead
            var silentSamples = 0L
            var zeroSamples = 0L
            var peak = 0
            var currentLevel = Level(-120.0, 0, true)
            var priorWarning = ""
            while (!stopRequested) {
                val n = ar.read(bytes, 0, bytes.size, AudioRecord.READ_NON_BLOCKING)
                val now = SystemClock.elapsedRealtime()
                if (n < 0) error("AudioRecord.read=$n (${readError(n)})")
                if (n > 0) {
                    output.append(bytes, n)
                    lastRead = now
                    currentLevel = pcmLevel(bytes, n)
                    peak = maxOf(peak, currentLevel.peak)
                    if (currentLevel.db >= -60) lastSignal = now else silentSamples += n / 2
                    if (currentLevel.allZero) zeroSamples += n / 2
                } else {
                    if (now - lastRead > 10000) error("10 秒未读到 PCM，录音中断；非静音帧")
                    Thread.sleep(20)
                }
                if (now - lastCheckpoint >= 1000) {
                    output.checkpoint()
                    check(dir.usableSpace > 16L * 1024 * 1024) { "空间低于 16 MiB，停止以保护文件" }
                    lastCheckpoint = now
                }
                if (now - lastUpdate >= 250) {
                    val warning = when {
                        now - lastRead > 2000 -> "读取停滞：未收到 PCM"
                        now - lastSignal >= 8000 -> "连续低电平 ≥8 秒：暂停、静音或捕获受限；不能据此确定屏蔽"
                        else -> ""
                    }
                    if (warning != priorWarning) {
                        log.write(if (warning.isBlank()) "音频恢复" else warning)
                        priorWarning = warning
                    }
                    val elapsed = now - start
                    val audioMs = output.dataBytes * 1000 / (rate * 2)
                    ProbeState.status.update { it.copy(message = "录制中 · $target", elapsedMs = elapsed,
                        audioMs = audioMs, db = currentLevel.db, peak = currentLevel.peak, warning = warning) }
                    lastUpdate = now
                    if (now - lastNotification >= 1000) {
                        getSystemService(NotificationManager::class.java).notify(1,
                            notification("${elapsed / 1000}s · ${currentLevel.db.toInt()} dBFS" +
                                if (warning.isBlank()) " · $target" else " · $warning"))
                        lastNotification = now
                    }
                }
                if (now - start >= 6 * 3600 * 1000L) requestStop("单次录音 6 小时上限")
            }
            log.write("STOP reason=$stopReason bytes=${output.dataBytes} audioMs=${output.dataBytes * 1000 / (rate * 2)} wallMs=${SystemClock.elapsedRealtime() - start} peak=$peak silentSamples=$silentSamples zeroSamples=$zeroSamples")
            finalMessage = "已停止：$stopReason（捕获兼容性需回听人工确认）"
        } catch (e: Exception) {
            finalMessage = "录制异常：${e.javaClass.simpleName}: ${e.message}"
            runCatching { if (log != null) log.write(finalMessage) else ProbeState.log(finalMessage) }
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            projection?.let { mp ->
                runCatching { mp.unregisterCallback(callback) }
                runCatching { mp.stop() }
            }
            wav?.let { output ->
                val result = runCatching {
                    output.close()
                    val targetFile = File(output.file.parentFile, output.file.name.removeSuffix(".part"))
                    check(output.file.renameTo(targetFile)) { "WAV 重命名失败" }
                    savedFile = targetFile
                    runBlocking { offlineApp().initialized.await(); registerAudio(targetFile) }
                }
                if (result.isFailure) {
                    finalMessage += "；WAV 完成失败，保留 .part 待恢复"
                    runCatching { log?.write(result.exceptionOrNull().toString()) }
                }
            }
            runCatching { log?.close() }
            val message = finalMessage
            val path = savedFile?.absolutePath
            main.post {
                worker = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                ProbeState.status.update { it.copy(active = false, message = message,
                    file = path ?: it.file, db = -120.0, peak = 0,
                    elapsedMs = SystemClock.elapsedRealtime() - start,
                    audioMs = wav?.let { output -> output.dataBytes * 1000 / (output.sampleRate * 2) } ?: 0) }
            }
        }
    }

    private fun requestStop(reason: String) {
        if (!stopRequested) { stopReason = reason; stopRequested = true; ProbeState.log(reason) }
    }

    override fun onDestroy() {
        requestStop("服务被销毁，录音中断")
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("离线听记 · 录制中").setContentText(text).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }

    companion object {
        const val STOP = "com.example.captureprobe.STOP"
        private const val CHANNEL = "capture"
        private fun readError(code: Int) = when (code) {
            AudioRecord.ERROR_DEAD_OBJECT -> "DEAD_OBJECT：音频系统失效"
            AudioRecord.ERROR_INVALID_OPERATION -> "INVALID_OPERATION"
            AudioRecord.ERROR_BAD_VALUE -> "BAD_VALUE"
            else -> "ERROR"
        }
    }
}
