package com.example.captureprobe

import android.app.Application
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

@Entity(tableName = "audio", indices = [Index(value = ["path"], unique = true)])
data class AudioEntry(@PrimaryKey val id: String = UUID.randomUUID().toString(), val path: String,
    val title: String, val created: Long = System.currentTimeMillis(), val durationMs: Long = 0)

@Entity(tableName = "runs", foreignKeys = [ForeignKey(entity = AudioEntry::class,
    parentColumns = ["id"], childColumns = ["audioId"], onDelete = ForeignKey.CASCADE)], indices = [Index("audioId")])
data class AsrRun(@PrimaryKey val id: String = UUID.randomUUID().toString(), val audioId: String,
    val modelId: String, val modelName: String, val language: String, val created: Long = System.currentTimeMillis(),
    val state: String = "READY", val progress: Int = 0, val processedSamples: Long = 0,
    val totalSamples: Long = 0, val message: String = "", val edited: String? = null,
    val reference: String = "", val evaluationNotes: String = "")

@Entity(tableName = "segments", primaryKeys = ["runId", "startSample"], foreignKeys = [ForeignKey(
    entity = AsrRun::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)])
data class TextSegment(val runId: String, val startSample: Long, val endSample: Long,
    val raw: String, val readable: String, val tokensJson: String = "[]", val timestampJson: String = "[]",
    val language: String = "", val note: String = "")

@Dao
interface AppDao {
    @Query("SELECT * FROM audio WHERE title LIKE '%' || :query || '%' OR EXISTS (SELECT 1 FROM runs JOIN segments ON runs.id=segments.runId WHERE runs.audioId=audio.id AND (segments.raw LIKE '%' || :query || '%' OR segments.readable LIKE '%' || :query || '%')) OR EXISTS (SELECT 1 FROM runs WHERE runs.audioId=audio.id AND edited LIKE '%' || :query || '%') ORDER BY created DESC")
    fun history(query: String): Flow<List<AudioEntry>>
    @Query("SELECT * FROM audio WHERE id=:id") suspend fun audio(id: String): AudioEntry?
    @Query("SELECT * FROM audio WHERE path=:path") suspend fun byPath(path: String): AudioEntry?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAudio(audio: AudioEntry)
    @Query("UPDATE audio SET title=:title WHERE id=:id") suspend fun rename(id: String, title: String)
    @Query("UPDATE audio SET durationMs=:duration WHERE id=:id") suspend fun duration(id: String, duration: Long)
    @Query("DELETE FROM audio WHERE id=:id") suspend fun deleteAudio(id: String)
    @Query("SELECT * FROM runs WHERE audioId=:id ORDER BY created DESC") fun runs(id: String): Flow<List<AsrRun>>
    @Query("SELECT * FROM runs WHERE id=:id") suspend fun run(id: String): AsrRun?
    @Insert suspend fun insertRun(run: AsrRun)
    @Query("UPDATE runs SET state=:state, progress=:progress, message=:message WHERE id=:id")
    suspend fun status(id: String, state: String, progress: Int, message: String)
    @Query("UPDATE runs SET totalSamples=:total WHERE id=:id") suspend fun total(id: String, total: Long)
    @Query("UPDATE runs SET processedSamples=:sample WHERE id=:id") suspend fun checkpoint(id: String, sample: Long)
    @Query("UPDATE runs SET edited=:text WHERE id=:id") suspend fun edit(id: String, text: String?)
    @Query("UPDATE runs SET reference=:reference, evaluationNotes=:notes WHERE id=:id")
    suspend fun evaluation(id: String, reference: String, notes: String)
    @Query("SELECT * FROM segments WHERE runId=:id ORDER BY startSample") fun segments(id: String): Flow<List<TextSegment>>
    @Query("SELECT * FROM segments WHERE runId=:id ORDER BY startSample") suspend fun segmentList(id: String): List<TextSegment>
    @Query("SELECT EXISTS(SELECT 1 FROM segments WHERE runId=:id AND startSample=:start)") suspend fun hasSegment(id: String, start: Long): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSegment(segment: TextSegment)
    @Query("UPDATE runs SET state='INTERRUPTED', message='上次处理被中断，可恢复已保存的段落' WHERE state IN ('RUNNING','DECODING')")
    suspend fun recover()
}

@Database(entities = [AudioEntry::class, AsrRun::class, TextSegment::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() { abstract fun dao(): AppDao }

class OfflineApp : Application() {
    lateinit var database: AppDatabase
    lateinit var initialized: Deferred<Unit>
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, AppDatabase::class.java, "offline.db").build()
        initialized = scope.async { database.dao().recover() }
    }
}

fun Context.offlineApp() = applicationContext as OfflineApp
fun Context.dao() = offlineApp().database.dao()

suspend fun Context.registerAudio(file: File, title: String = file.name): AudioEntry {
    val existing = dao().byPath(file.absolutePath)
    if (existing != null) return existing
    val entry = AudioEntry(path = file.absolutePath, title = title, created = file.lastModified())
    dao().insertAudio(entry)
    return dao().byPath(file.absolutePath) ?: entry
}

suspend fun Context.scanAudio() {
    offlineApp().initialized.await()
    listOf("recordings", "imports").forEach { folder ->
        File(filesDir, folder).listFiles()?.filter { it.name.endsWith(".wav") || it.name.endsWith(".media") }
            ?.forEach { registerAudio(it) }
    }
}
