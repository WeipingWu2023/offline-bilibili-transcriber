package com.example.captureprobe

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProbeStatus(
    val active: Boolean = false,
    val message: String = "待测试：尚未验证 Bilibili 兼容性",
    val elapsedMs: Long = 0,
    val audioMs: Long = 0,
    val db: Double = -120.0,
    val peak: Int = 0,
    val warning: String = "",
    val file: String = "",
    val logs: List<String> = emptyList()
)

object ProbeState {
    val status = MutableStateFlow(ProbeStatus())
    @Synchronized fun log(message: String) {
        val line = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + message
        Log.i("CaptureProbe", message)
        status.update { it.copy(logs = (it.logs + line).takeLast(120)) }
    }
}

/** Session logs live beside WAV, so process death does not erase diagnostics. */
class SessionLog(file: File) : AutoCloseable {
    private val writer = file.bufferedWriter()
    @Synchronized fun write(message: String) {
        ProbeState.log(message)
        writer.appendLine("${System.currentTimeMillis()} $message")
        writer.flush()
    }
    @Synchronized override fun close() { writer.close() }
}
