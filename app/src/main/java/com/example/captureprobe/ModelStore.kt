package com.example.captureprobe

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class LocalModel(val id: String, val name: String, val engine: String, val dir: File,
    val manifest: JSONObject) {
    fun file(name: String): String {
        val path = File(dir, name).canonicalFile
        require(path.path.startsWith(dir.canonicalPath + File.separator) && path.isFile) { "模型文件缺失：$name" }
        return path.absolutePath
    }
}

object ModelStore {
    private fun root(context: Context) = File(context.filesDir, "models").apply { check(mkdirs() || isDirectory) }
    fun list(context: Context): List<LocalModel> = root(context).listFiles()?.filter { it.isDirectory && it.name.startsWith("m-") }
        ?.mapNotNull { runCatching { read(it) }.getOrNull() }?.sortedBy { it.name } ?: emptyList()
    fun get(context: Context, id: String): LocalModel = list(context).firstOrNull { it.id == id } ?: error("模型已移除，请重新导入")

    private fun read(dir: File): LocalModel {
        val manifestFile = File(dir, "manifest.json")
        require(manifestFile.length() in 1..65536)
        val json = JSONObject(manifestFile.readText())
        require(json.getInt("schema") == 1)
        val engine = json.getString("engine")
        require(engine in listOf("sensevoice", "paraformer", "qwen3")) { "不支持的模型类型" }
        val model = LocalModel(dir.name, json.getString("name").take(100), engine, dir, json)
        model.file("vad/silero_vad.onnx")
        model.file("punct/model.int8.onnx")
        if (engine != "qwen3") { model.file("asr/model.int8.onnx"); model.file("asr/tokens.txt") }
        else {
            model.file("asr/conv_frontend.int8.onnx"); model.file("asr/encoder.int8.onnx"); model.file("asr/decoder.int8.onnx")
            require(File(dir, "asr/tokenizer").isDirectory)
        }
        return model
    }

    fun import(context: Context, uri: Uri, progress: (Long) -> Unit): LocalModel {
        val root = root(context)
        val stage = File(root, "import-${UUID.randomUUID()}").apply { check(mkdir()) }
        var total = 0L
        try {
            ZipInputStream(context.contentResolver.openInputStream(uri) ?: error("无法打开模型包")).use { zip ->
                val names = hashSetOf<String>()
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 128) { "模型包文件数量超限" }
                    val name = entry.name
                    require(name.length in 1..200 && !name.contains('\\') && !name.startsWith('/') &&
                        name.split('/').none { it == ".." || it == "." }) { "不安全的 ZIP 路径" }
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    require(names.add(name)) { "重复 ZIP 文件" }
                    val file = File(stage, name).canonicalFile
                    require(file.path.startsWith(stage.canonicalPath + File.separator))
                    require(name == "manifest.json" || name.startsWith("asr/") || name.startsWith("punct/") ||
                        name.startsWith("vad/") || name.startsWith("licenses/")) { "模型包中包含非模型文件" }
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { out ->
                        val bytes = ByteArray(65536)
                        while (true) {
                            val n = zip.read(bytes)
                            if (n < 0) break
                            total += n
                            require(total <= 1800L * 1024 * 1024) { "解压大小超限" }
                            if (name == "manifest.json") require(file.length() + n <= 65536)
                            check(root.usableSpace >= 32L * 1024 * 1024) { "模型导入空间不足" }
                            out.write(bytes, 0, n)
                            progress(total)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val parsed = read(stage)
            val hashes = parsed.manifest.getJSONObject("sha256")
            val checked = hashes.keys().asSequence().toSet()
            val payload = stage.walkTopDown().filter { it.isFile && it.name != "manifest.json" }.map { it.relativeTo(stage).invariantSeparatorsPath }.toSet()
            require(checked == payload) { "模型校验清单不完整" }
            checked.forEach { name ->
                val expected = hashes.getString(name)
                require(expected.matches(Regex("[a-fA-F0-9]{64}")))
                val digest = MessageDigest.getInstance("SHA-256")
                File(stage, name).inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)) { "模型校验失败：$name" }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(File(stage, "manifest.json").readBytes())
                .joinToString("") { "%02x".format(it) }
            val target = File(root, "m-${digest.take(24)}")
            if (target.exists()) { stage.deleteRecursively(); return read(target) }
            check(stage.renameTo(target)) { "保存模型失败" }
            return read(target)
        } catch (e: Throwable) { stage.deleteRecursively(); throw e }
    }
}
