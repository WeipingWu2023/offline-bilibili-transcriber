package com.example.captureprobe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaExtractor
import android.media.MediaPlayer
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import android.net.Uri
import org.json.JSONObject
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private var target by mutableStateOf("tv.danmaku.bili")
    private var pending by mutableStateOf(false)
    private var ready by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var selected by mutableStateOf("")
    private var files by mutableStateOf<List<File>>(emptyList())
    private var exportFile: File? = null

    private val projectionResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending = false
        if (result.resultCode != RESULT_OK || result.data == null) {
            ProbeState.log("MediaProjection 授权取消"); return@registerForActivityResult
        }
        try {
            val info = packageManager.getApplicationInfo(target, 0)
            val version = packageManager.getPackageInfo(target, 0).versionName
            ProbeState.status.update { it.copy(active = true, message = "启动前台服务") }
            startForegroundService(Intent(this, CaptureService::class.java)
                .putExtra("grant", result.data).putExtra("result", result.resultCode)
                .putExtra("uid", info.uid).putExtra("target", target).putExtra("version", version))
        } catch (e: Exception) {
            ProbeState.status.update { it.copy(active = false, message = "启动失败：${e.message}") }
            ProbeState.log("启动失败 ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestProjection() else { pending = false; ProbeState.log("RECORD_AUDIO 被拒绝") }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ProbeState.log("通知权限=$granted；拒绝时前台服务仍需系统显示活动记录")
        requestAudioPermission()
    }
    private val importResult = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        busy = true
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(filesDir, "imports").apply { check(mkdirs() || isDirectory) }
                    val output = File(dir, "import-${System.currentTimeMillis()}-${UUID.randomUUID()}.media")
                    try {
                        val mime = contentResolver.getType(uri) ?: "unknown"
                        var total = 0L
                        contentResolver.openInputStream(uri)!!.use { input ->
                            output.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    if (n == 0) continue
                                    total += n
                                    require(total <= 1024L * 1024 * 1024) { "Phase 0 导入上限 1 GiB" }
                                    check(dir.usableSpace > 16L * 1024 * 1024) { "空间不足" }
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                        val extractor = MediaExtractor()
                        try {
                            extractor.setDataSource(output.absolutePath)
                            require(extractor.drmInitData == null) { "不支持受 DRM 保护的文件" }
                            require((0 until extractor.trackCount).any {
                                extractor.getTrackFormat(it).getString("mime")?.startsWith("audio/") == true
                            }) { "无可识别的音轨；格式可能不受设备支持" }
                        } finally { extractor.release() }
                        File(dir, output.name + ".log").writeText("用户选择的本地文件：mime=$mime bytes=$total\n只导入及回听，未转写或解码导出。\n")
                        output
                    } catch (e: Exception) { output.delete(); throw e }
                }
                selected = file.absolutePath
                ProbeState.log("本地导入成功 ${file.name}；尚未实现 ASR")
                refreshFiles()
            } catch (e: Exception) { ProbeState.log("导入失败：${e.message}") }
            finally { busy = false }
        }
    }
    private val exportResult = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = exportFile
        exportFile = null
        if (uri == null || file == null) return@registerForActivityResult
        busy = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                ProbeState.log("已导出 ${file.name}")
            } catch (e: Exception) { ProbeState.log("导出失败：${e.message}") }
            finally { busy = false }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = savedInstanceState?.getString("target") ?: target
        pending = savedInstanceState?.getBoolean("pending") ?: false
        selected = savedInstanceState?.getString("selected") ?: ""
        exportFile = savedInstanceState?.getString("export")?.let(::File)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (!ProbeState.status.value.active) {
                    File(filesDir, "recordings").listFiles()?.filter { it.name.endsWith(".wav.part") }?.forEach { file ->
                        runCatching { WavFile.recover(file) }
                            .onSuccess { ProbeState.log("恢复中断文件 ${it.name}；可能缺少结束前的音频") }
                            .onFailure { ProbeState.log("恢复失败 ${file.name}: ${it.message}；原文件保留") }
                    }
                }
            }
            refreshFiles(); ready = true
        }
        setContent {
            MaterialTheme {
                val status by ProbeState.status.collectAsStateWithLifecycle()
                var confirmDelete by remember { mutableStateOf(false) }
                var showLogs by rememberSaveable { mutableStateOf(false) }
                var tab by rememberSaveable { mutableStateOf("录音") }
                val asr by AsrState.status.collectAsStateWithLifecycle()
                LaunchedEffect(status.active, status.file) {
                    if (!status.active) {
                        refreshFiles()
                        if (status.file.endsWith(".wav")) selected = status.file
                    }
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("小棋", style = MaterialTheme.typography.headlineSmall)
                        Text("内部音频录制 · 本地识别 · 本地保存", style = MaterialTheme.typography.bodyMedium)
                        Row { listOf("录音", "文稿", "模型").forEach { item -> TextButton(onClick = { tab = item }) { Text((if (tab == item) "● " else "") + item) } } }
                        if (tab == "文稿") LibraryPanel(selected, { play(it, capturable = false) }, ::releasePlayer)
                        if (tab == "模型") ModelPanel()
                        if (tab == "录音") {
                        Text("只录获准的内部播放声音，不使用麦克风。系统弹窗虽称屏幕共享，本应用不保存画面。")
                        OutlinedTextField(value = target, onValueChange = { target = it.trim() },
                            label = { Text("播放应用包名") }, enabled = !status.active && !pending,
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { target = "tv.danmaku.bili" }, enabled = !status.active && !pending) { Text("Bilibili") }
                            TextButton(onClick = { target = packageName }, enabled = !status.active && !pending) { Text("对照：本应用") }
                        }
                        Text(status.message)
                        Text(String.format(Locale.US, "墙钟 %.1f s · PCM %.1f s · RMS %.1f dBFS · 峰值 %d",
                            status.elapsedMs / 1000.0, status.audioMs / 1000.0, status.db, status.peak))
                        LinearProgressIndicator(progress = { ((status.db + 60) / 60).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        if (status.warning.isNotEmpty()) Text(status.warning, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { startCapture() }, enabled = ready && !busy && !pending && !status.active && !asr.active) { Text("授权并开始录音") }
                        Button(onClick = { startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.STOP)) }, enabled = status.active) { Text("停止并保存 WAV") }
                        Button(onClick = { playControl() }, enabled = ready && !busy && status.active && target == packageName) { Text("播放 15 秒可捕获对照音") }
                        Text("切换到 Bilibili 后继续播放，再从通知停止。连续静音只能提示疑似受限，需与对照音比较并回听。")
                        HorizontalDivider()
                        Button(onClick = { importResult.launch(arrayOf("audio/*", "video/*")) }, enabled = ready && !busy && !status.active && !pending) { Text("备选：导入本地音频/视频") }
                        Text("停止录音或导入后，前往“文稿”开始离线转写。视频回听仅播放音轨。")
                        if (busy) Text("正在处理本地文件…")
                        Text("本地文件（${files.size}）", style = MaterialTheme.typography.titleMedium)
                        files.forEach { file ->
                            TextButton(onClick = { selected = file.absolutePath }, enabled = !status.active && !busy) {
                                Text((if (selected == file.absolutePath) "✓ " else "") + file.name + " (${file.length() / 1024} KiB)")
                            }
                        }
                        val playable = selected.isNotBlank() && File(selected).isFile && !selected.endsWith(".part")
                        Button(onClick = { play(File(selected), capturable = false) }, enabled = playable && !busy && !status.active) { Text("回听所选文件") }
                        TextButton(onClick = { releasePlayer() }) { Text("停止回听") }
                        Button(onClick = { export(File(selected)) }, enabled = playable && !busy && !status.active) { Text("导出所选原始文件") }
                        Button(onClick = {
                            val audio = File(selected)
                            val log = if (audio.name.endsWith(".media")) File(audio.parentFile, audio.name + ".log")
                                else File(audio.parentFile, audio.name.removeSuffix("-interrupted.wav").removeSuffix(".wav") + ".log")
                            if (log.isFile) export(log) else ProbeState.log("所选文件无会话日志")
                        }, enabled = playable && !busy && !status.active) { Text("导出会话日志") }
                        TextButton(onClick = { confirmDelete = true }, enabled = ready && !busy && !status.active && !pending && !asr.active) { Text("删除全部音频、文稿与日志") }
                        TextButton(onClick = { showLogs = !showLogs }) { Text("${if (showLogs) "隐藏" else "显示"}运行日志") }
                        if (showLogs) status.logs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除所有测试数据？") },
                    text = { Text("所有音频、识别结果、编辑稿和日志都会删除，模型保留；已导出的副本需自行删除。") },
                    confirmButton = { TextButton(onClick = {
                        confirmDelete = false; releasePlayer(); busy = true
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    listOf("recordings", "imports", "control", "processing").forEach { name ->
                                        val dir = File(filesDir, name)
                                        check(!dir.exists() || dir.deleteRecursively()) { "删除失败：$name" }
                                    }
                                    offlineApp().database.clearAllTables()
                                }
                                selected = ""; ProbeState.status.value = ProbeStatus(message = "本地数据已删除")
                                refreshFiles()
                            } catch (e: Exception) { ProbeState.log(e.message ?: "删除失败") }
                            finally { busy = false }
                        }
                    }) { Text("删除") } },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
            }
        }
        if (BuildConfig.DEBUG && intent.action == "com.example.captureprobe.DEBUG_ASR") {
            // Debug-only file diagnostics; no projection grant, recording, or external path access.
            lifecycleScope.launch {
                try {
                    check(!AsrState.status.value.active && !ProbeState.status.value.active) { "已有任务，拒绝重复自检" }
                    busy = true
                    val sourceName = intent.getStringExtra("audioName") ?: "test-zh.wav"
                    require(sourceName == "test-zh.wav" || sourceName.matches(Regex("capture-[0-9]+-[a-f0-9]{8}(-interrupted)?\\.wav")))
                    val source = if (sourceName == "test-zh.wav") File(filesDir, sourceName) else File(filesDir, "recordings/$sourceName")
                    require(source.isFile) { "自检输入未准备好" }
                    val model = withContext(Dispatchers.IO) {
                        ModelStore.list(this@MainActivity).firstOrNull() ?: ModelStore.import(this@MainActivity,
                            Uri.fromFile(File(filesDir, "model-test.zip"))) { }
                    }
                    offlineApp().initialized.await()
                    val audio = withContext(Dispatchers.IO) { registerAudio(source, if (sourceName == "test-zh.wav") "官方中文自检音频" else source.name) }
                    val run = AsrRun(audioId = audio.id, modelId = model.id, modelName = model.name, language = "zh")
                    dao().insertRun(run)
                    startForegroundService(Intent(this@MainActivity, TranscriptionService::class.java).putExtra("runId", run.id))
                    ProbeState.log("DEBUG_ASR 启动：${run.id}")
                    val finished = withTimeout(300000) {
                        dao().runs(audio.id).first { rows -> rows.any { it.id == run.id && it.state in listOf("DONE", "ERROR", "PAUSED") } }.first { it.id == run.id }
                    }
                    val segments = dao().segmentList(run.id)
                    val json = JSONObject().put("runId", run.id).put("source", sourceName).put("state", finished.state)
                        .put("message", finished.message).put("model", model.id).put("totalSamples", finished.totalSamples)
                        .put("segments", segments.size).put("raw", segments.joinToString("") { it.raw })
                        .put("readable", TranscriptText.readable(segments))
                    if (sourceName == "test-zh.wav" && finished.state == "DONE")
                        json.put("reference", "开饭时间早上九点至下午五点").put("cer", Accuracy.evaluate("开饭时间早上九点至下午五点", segments.joinToString("") { it.raw }).cer)
                    withContext(Dispatchers.IO) { File(filesDir, "debug-last-run.json").writeText(json.toString(2)) }
                    ProbeState.log("DEBUG_ASR 完成：${finished.state}")
                } catch (e: Exception) { ProbeState.log("DEBUG_ASR 失败：${e.message}") }
                finally { busy = false }
            }
        }
    }

    private fun startCapture() {
        if (AsrState.status.value.active) { ProbeState.log("请先暂停转写再录制"); return }
        releasePlayer()
        try { packageManager.getApplicationInfo(target, 0) }
        catch (e: Exception) { ProbeState.log("包名不可见或未安装：$target。请使用同一用户配置中的官方应用；自定义包名可能需要加入 manifest queries。"); return }
        pending = true
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else requestAudioPermission()
    }

    private fun requestAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestProjection()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestProjection() {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent()
            projectionResult.launch(intent)
        } catch (e: Exception) { pending = false; ProbeState.log("无法请求投影：${e.message}") }
    }

    private suspend fun refreshFiles() {
        files = withContext(Dispatchers.IO) {
            val result = listOf("recordings", "imports").flatMap { File(filesDir, it).listFiles()?.toList() ?: emptyList() }
                .filter { it.name.endsWith(".wav") || it.name.endsWith(".media") }.sortedByDescending { it.lastModified() }
            result.forEach { registerAudio(it) }
            result
        }
    }

    private fun playControl() {
        busy = true
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(filesDir, "control").apply { check(mkdirs() || isDirectory) }
                    val f = File(dir, "control.wav")
                    WavFile(f, 48000).use { wav ->
                        val bytes = ByteArray(48000 * 2)
                        repeat(15) { second ->
                            for (i in 0 until 48000) {
                                val hz = if (second % 2 == 0) 440 else 880
                                val amplitude = if (i < 38400) 8192 else 0
                                val sample = (sin(2 * PI * hz * i / 48000) * amplitude).toInt()
                                bytes[2 * i] = sample.toByte(); bytes[2 * i + 1] = (sample shr 8).toByte()
                            }
                            wav.append(bytes, bytes.size)
                        }
                    }; f
                }
                play(file, capturable = true)
            } catch (e: Exception) { ProbeState.log("对照音失败：${e.message}") }
            finally { busy = false }
        }
    }

    private fun play(file: File, capturable: Boolean) {
        releasePlayer()
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(if (capturable) AudioAttributes.ALLOW_CAPTURE_BY_ALL else AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { it.start(); ProbeState.log("播放 ${file.name}，允许第三方捕获=$capturable") }
            mp.setOnCompletionListener { releasePlayer() }
            mp.setOnErrorListener { _, what, extra -> ProbeState.log("回听失败 what=$what extra=$extra"); releasePlayer(); true }
            mp.prepareAsync()
        } catch (e: Exception) { releasePlayer(); ProbeState.log("回听失败：${e.message}") }
    }

    private fun export(file: File) { exportFile = file; exportResult.launch(file.name) }
    private fun releasePlayer() { player?.release(); player = null }
    override fun onStop() { releasePlayer(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("target", target); outState.putBoolean("pending", pending)
        outState.putString("selected", selected); outState.putString("export", exportFile?.absolutePath)
        super.onSaveInstanceState(outState)
    }
}
