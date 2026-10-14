package com.example.captureprobe

import java.util.Locale

object TranscriptText {
    fun stamp(sample: Long): String {
        val ms = sample * 1000 / 16000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }
    fun content(text: String) = text.filterNot { it.isWhitespace() || Character.getType(it) in listOf(
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt()) }
    /** Punctuation may change formatting, but lexical changes are rejected. */
    fun conservative(raw: String, punctuated: String): String {
        val numbers = Regex("[0-9]+(?:[.,:/-][0-9]+)*")
        val sameNumbers = numbers.findAll(raw).map { it.value }.toList() == numbers.findAll(punctuated).map { it.value }.toList()
        return if (content(raw) == content(punctuated) && sameNumbers) punctuated.trim() else raw
    }
    fun faithful(segments: List<TextSegment>) = segments.joinToString("\n") {
        "[${stamp(it.startSample)} – ${stamp(it.endSample)}] ${it.raw}"
    }
    fun readable(segments: List<TextSegment>): String {
        val text = StringBuilder()
        var previousEnd = 0L
        var paragraphSize = 0
        for (segment in segments) {
            if (text.isNotEmpty()) {
                if (segment.startSample - previousEnd >= 19200 || paragraphSize >= 280) {
                    text.append("\n\n"); paragraphSize = 0
                } else text.append(' ')
            }
            text.append(segment.readable.trim()); paragraphSize += segment.readable.length
            previousEnd = segment.endSample
        }
        return text.toString()
    }
    fun export(title: String, run: AsrRun, segments: List<TextSegment>, mode: String, markdown: Boolean): String {
        val body = when (mode) { "原文" -> faithful(segments); "编辑稿" -> run.edited ?: ""; else -> readable(segments) }
        val metadata = "模型：${run.modelName} / ${run.language}\n输出：$mode\n状态：${run.state}\n任务：${run.id}\n识别可能有错漏；时间为音频内的语音段范围。"
        return if (markdown) "# ${title.replace("\n", " ")}\n\n$metadata\n\n$body\n" else "$title\n$metadata\n\n$body\n"
    }
}
