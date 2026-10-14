package com.example.captureprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

@Composable
fun ModelPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<LocalModel>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { models = withContext(Dispatchers.IO) { ModelStore.list(context) } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val model = withContext(Dispatchers.IO) {
                        var last = 0L
                        ModelStore.import(context, uri) { bytes ->
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - last > 500) { message = "已解压 ${bytes / 1024 / 1024} MiB，随后校验文件"; last = now }
                        }
                    }
                    message = "已导入 ${model.name}，可重复离线使用"
                    models = withContext(Dispatchers.IO) { ModelStore.list(context) }
                } catch (e: Exception) { message = "导入失败：${e.message}" }
                finally { busy = false }
            }
        }
    }
    Text("离线模型", style = MaterialTheme.typography.titleLarge)
    Text("一次导入，之后识别不联网。模型包包含 ASR、CT-Transformer 标点和 Silero VAD。请只导入可信来源的模型。")
    Button(onClick = { launcher.launch(arrayOf("application/zip", "application/octet-stream")) },
        enabled = !busy && !AsrState.status.collectAsStateWithLifecycle().value.active) { Text("导入模型 ZIP") }
    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("请保持当前页面，正在导入并检查…") }
    if (message.isNotBlank()) Text(message)
    models.forEach { model ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleMedium)
            Text("${model.engine} · 本地模型已就绪", style = MaterialTheme.typography.bodySmall)
        } }
    }
    Text("首选 SenseVoice Small INT8，语言可选普通话、粤语或自动。也可导入受支持的 Paraformer / Qwen3-ASR 模型包进行比较；模型适配和实际效果需分别确认。")
    Text("标点版只加标点和分段，不做 LLM 改写。识别模型本身仍可能漏词或识别错误。")
}

@Composable
fun LibraryPanel(preselectPath: String, onPlay: (File) -> Unit, onStopPlayback: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { context.dao() }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var historyPage by rememberSaveable { mutableStateOf(0) }
    var segmentPage by rememberSaveable { mutableStateOf(0) }
    val historyFlow = remember(query) { dao.history(query) }
    val history by historyFlow.collectAsStateWithLifecycle(emptyList())
    var audioId by rememberSaveable { mutableStateOf("") }
    var runId by rememberSaveable { mutableStateOf("") }
    val audio = history.firstOrNull { it.id == audioId }
    val runsFlow = remember(audioId) { dao.runs(audioId) }
    val runs by runsFlow.collectAsStateWithLifecycle(emptyList())
    val run = runs.firstOrNull { it.id == runId } ?: runs.firstOrNull()
    val segmentsFlow = remember(run?.id) { dao.segments(run?.id ?: "") }
    val segments by segmentsFlow.collectAsStateWithLifecycle(emptyList())
    val asr by AsrState.status.collectAsStateWithLifecycle()
    val capture by ProbeState.status.collectAsStateWithLifecycle()
    var models by remember { mutableStateOf<List<LocalModel>>(emptyList()) }
    var modelId by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("zh") }
    var mode by rememberSaveable { mutableStateOf("原文") }
    var draft by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var cer by remember { mutableStateOf<CerResult?>(null) }
    var showEvaluation by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var deleteAudio by remember { mutableStateOf(false) }
    var renameTitle by remember { mutableStateOf("") }
    var exportPath by rememberSaveable { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val path = exportPath
        if (uri != null && path.isNotEmpty()) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> File(path).inputStream().use { it.copyTo(out) } }
                }
                message = "导出成功"
            } catch (e: Exception) { message = "导出失败：${e.message}" }
            finally { withContext(Dispatchers.IO) { File(path).delete() }; exportPath = "" }
        }
    }
    fun exportText(text: String, filename: String) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                File(context.cacheDir, "export-${UUID.randomUUID()}.txt").apply { writeText(text, Charsets.UTF_8) }
            }
            exportPath = file.absolutePath
            export.launch(filename.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100))
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { context.scanAudio() }
        models = withContext(Dispatchers.IO) { ModelStore.list(context) }
        if (modelId.isEmpty()) modelId = models.firstOrNull()?.id.orEmpty()
    }
    LaunchedEffect(preselectPath, history.size) {
        if (audioId.isEmpty()) audioId = history.firstOrNull { it.path == preselectPath }?.id ?: history.firstOrNull()?.id.orEmpty()
    }
    LaunchedEffect(run?.id) {
        draft = run?.edited.orEmpty(); reference = run?.reference.orEmpty(); notes = run?.evaluationNotes.orEmpty(); cer = null; segmentPage = 0
    }
    LaunchedEffect(query) { historyPage = 0 }
    LaunchedEffect(audioId) { renameTitle = audio?.title.orEmpty() }
    fun start(runToStart: AsrRun) {
        try {
            if (asr.active || capture.active || busy) return
            onStopPlayback()
            AsrState.status.value = AsrStatus(true, runToStart.id, runToStart.progress, "启动离线转写")
            context.startForegroundService(Intent(context, TranscriptionService::class.java).putExtra("runId", runToStart.id))
        } catch (e: Exception) { AsrState.status.value = AsrStatus(message = "启动失败：${e.message}"); message = e.message.orEmpty() }
    }
    Text("音频与文稿", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(query, { query = it }, label = { Text("搜索标题、原文、标点版或编辑稿") }, modifier = Modifier.fillMaxWidth())
    if (history.isEmpty()) Text("没有匹配记录。先录制内部音频或导入本地文件。")
    val safeHistoryPage = historyPage.coerceAtMost((maxOf(1, history.size) - 1) / 25)
    history.drop(safeHistoryPage * 25).take(25).forEach { entry ->
        TextButton(onClick = { audioId = entry.id; runId = "" }, enabled = !busy) {
            Text((if (entry.id == audioId) "✓ " else "") + entry.title + if (entry.durationMs > 0) " · ${entry.durationMs / 1000}s" else "")
        }
    }
    if (history.size > 25) Row {
        TextButton(onClick = { historyPage = safeHistoryPage - 1 }, enabled = safeHistoryPage > 0) { Text("上一页") }
        Text("${safeHistoryPage + 1} / ${(history.size + 24) / 25}")
        TextButton(onClick = { historyPage = safeHistoryPage + 1 }, enabled = (safeHistoryPage + 1) * 25 < history.size) { Text("下一页") }
    }
    if (audio != null) {
        HorizontalDivider()
        Text(audio.title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(renameTitle, { renameTitle = it }, label = { Text("记录标题") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { scope.launch { dao.rename(audio.id, renameTitle.trim().ifBlank { audio.title }) } }) { Text("保存标题") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onPlay(File(audio.path)) }, enabled = !asr.active && !capture.active) { Text("回听音频") }
            TextButton(onClick = onStopPlayback) { Text("停止回听") }
        }
        Text("本地识别模型")
        if (models.isEmpty()) Text("请先在“模型”页导入离线模型包。")
        models.forEach { m -> TextButton(onClick = { modelId = m.id }, enabled = !asr.active) { Text((if (m.id == modelId) "✓ " else "") + m.name) } }
        val model = models.firstOrNull { it.id == modelId }
        Row {
            listOf("zh" to "普通话", "yue" to "粤语", "auto" to "自动").forEach { (code, label) ->
                TextButton(onClick = { language = code }, enabled = !asr.active && (model?.engine == "sensevoice" || code == "auto")) { Text((if (language == code) "✓ " else "") + label) }
            }
        }
        Button(onClick = {
            if (model != null) scope.launch {
                busy = true
                try {
                    val newRun = AsrRun(audioId = audio.id, modelId = model.id, modelName = model.name,
                        language = if (model.engine == "sensevoice") language else "auto")
                    dao.insertRun(newRun); runId = newRun.id
                    busy = false; start(newRun)
                } catch (e: Exception) { message = "创建任务失败：${e.message}" }
                finally { busy = false }
            }
        }, enabled = model != null && !asr.active && !capture.active && !busy) { Text(if (runs.isEmpty()) "开始离线转写" else "重新转写 / 比较模型（保留旧结果）") }
        runs.forEach { r -> TextButton(onClick = { runId = r.id }) { Text((if (r.id == run?.id) "✓ " else "") + "${r.modelName} · ${r.language} · ${r.state} ${r.progress}%") } }
        if (run != null) {
            Text(run.message)
            if (run.state in listOf("RUNNING", "DECODING")) LinearProgressIndicator(progress = { run.progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (asr.active && asr.runId == run.id) Button(onClick = {
                context.startService(Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.PAUSE))
            }) { Text("暂停（本段完成后保存）") }
            else if (run.state in listOf("PAUSED", "ERROR", "INTERRUPTED", "READY")) Button(onClick = { start(run) }, enabled = !asr.active && !capture.active && !busy) { Text("恢复 / 重试此任务") }
            Row { listOf("原文", "标点版", "编辑稿").forEach { m -> TextButton(onClick = { mode = m }) { Text((if (mode == m) "✓ " else "") + m) } } }
            Text("原文是未经修改的模型识别结果，可能有错漏。标点版只补标点并分段；手工编辑稿单独保存。时间戳对应语音段，不是逐字精确对齐。", style = MaterialTheme.typography.bodySmall)
            if (mode == "编辑稿") {
                Button(onClick = { draft = TranscriptText.readable(segments) }, enabled = run.edited == null && !asr.active) { Text("从标点版创建编辑稿") }
                OutlinedTextField(draft, { draft = it }, label = { Text("手工编辑（不覆盖原文）") }, minLines = 8, modifier = Modifier.fillMaxWidth())
                Button(onClick = { scope.launch { dao.edit(run.id, draft); message = "编辑稿已保存，原文未改变" } }) { Text("保存编辑稿") }
            } else {
            val safePage = segmentPage.coerceAtMost((maxOf(1, segments.size) - 1) / 50)
            segments.drop(safePage * 50).take(50).forEach { segment ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text("${TranscriptText.stamp(segment.startSample)} – ${TranscriptText.stamp(segment.endSample)}", style = MaterialTheme.typography.labelMedium)
                    Text(if (mode == "原文") segment.raw.ifEmpty { "[此段识别为空，请回听]" } else segment.readable.ifEmpty { "[此段识别为空，请回听]" })
                    if (segment.note.isNotBlank()) Text(segment.note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } }
            }
            if (segments.size > 50) Row {
                TextButton(onClick = { segmentPage = safePage - 1 }, enabled = safePage > 0) { Text("前 50 段") }
                Text("${safePage + 1} / ${(segments.size + 49) / 50}")
                TextButton(onClick = { segmentPage = safePage + 1 }, enabled = (safePage + 1) * 50 < segments.size) { Text("后 50 段") }
            }
            }
            val body = when (mode) { "原文" -> TranscriptText.faithful(segments); "编辑稿" -> draft; else -> TranscriptText.readable(segments) }
            TextButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("小棋", body)); message = "已复制 $mode" }, enabled = body.isNotEmpty()) { Text("复制当前文本") }
            Row {
                Button(onClick = { exportText(TranscriptText.export(audio.title, run.copy(edited = draft), segments, mode, false), "${audio.title}-$mode.txt") }, enabled = body.isNotEmpty()) { Text("导出 TXT") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { exportText(TranscriptText.export(audio.title, run.copy(edited = draft), segments, mode, true), "${audio.title}-$mode.md") }, enabled = body.isNotEmpty()) { Text("导出 Markdown") }
            }
            TextButton(onClick = { showEvaluation = !showEvaluation }) { Text("${if (showEvaluation) "收起" else "打开"}准确度评估") }
            if (showEvaluation) {
                Text("粘贴人工纠正的纯文字参考稿，不含时间戳。CER 忽略空白和标点，保留汉字、字母、数字及大小写差异。")
                OutlinedTextField(reference, { reference = it; cer = null }, label = { Text("人工参考稿") }, minLines = 5, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("人工标注：漏词、疑似幻觉、专名错误") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Button(onClick = { scope.launch {
                    busy = true
                    try {
                        val hypothesis = segments.joinToString("") { it.raw }
                        cer = withContext(Dispatchers.Default) { Accuracy.evaluate(reference, hypothesis) }
                        dao.evaluation(run.id, reference, notes)
                        message = "评估已保存；插入字符不自动等于幻觉"
                    } catch (e: Exception) { message = e.message.orEmpty() }
                    finally { busy = false }
                } }, enabled = !busy && run.state == "DONE") { Text("计算 CER 并保存评估") }
                cer?.let { value ->
                    Text("CER %.2f%% · 替换 %d · 删除 %d · 插入 %d".format(value.cer * 100, value.substitutions, value.deletions, value.insertions))
                    TextButton(onClick = {
                        exportText("# 准确度评估\n\n音频：${audio.title}\n任务：${run.id}\n模型：${run.modelName} (${run.modelId})\n语言：${run.language}\nCER：${value.cer}\n参考字数：${value.referenceCharacters}\n替换：${value.substitutions}\n删除：${value.deletions}\n插入：${value.insertions}\n\n## 人工标注\n$notes\n\n## 人工参考稿\n$reference\n\n## 原始识别\n${segments.joinToString("") { it.raw }}\n", "${audio.title}-评估.md")
                    }) { Text("导出评估报告") }
                }
            }
        }
        TextButton(onClick = { deleteAudio = true }, enabled = !asr.active && !capture.active && !busy) { Text("删除此音频及全部转写 / 编辑稿") }
    }
    if (busy) Text("正在处理…")
    if (message.isNotEmpty()) Text(message)
    if (deleteAudio && audio != null) AlertDialog(onDismissRequest = { deleteAudio = false }, title = { Text("删除此记录？") },
        text = { Text("原音频、所有模型的识别结果和编辑稿都会删除。已导出的副本需自行删除。") },
        confirmButton = { TextButton(onClick = {
            deleteAudio = false; onStopPlayback(); busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val original = File(audio.path)
                        check(!original.exists() || original.delete()) { "音频删除失败" }
                        val normalized = File(context.filesDir, "processing/${audio.id}.wav")
                        check(!normalized.exists() || normalized.delete()) { "解码副本删除失败" }
                        val log = File(original.parentFile, original.name.removeSuffix("-interrupted.wav").removeSuffix(".wav") + ".log")
                        if (log.exists()) check(log.delete())
                    }
                    dao.deleteAudio(audio.id); audioId = ""; runId = ""; message = "记录已删除"
                } catch (e: Exception) { message = "删除失败：${e.message}" }
                finally { busy = false }
            }
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteAudio = false }) { Text("取消") } })
}
