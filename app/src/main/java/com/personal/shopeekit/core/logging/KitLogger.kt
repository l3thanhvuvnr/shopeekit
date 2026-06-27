package com.personal.shopeekit.core.logging

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object KitLogger {

    enum class Level(val label: String) {
        DEBUG("D"), INFO("I"), WARN("W"), ERROR("E")
    }

    data class Entry(
        val ts: Long,
        val tag: String,
        val level: Level,
        val msg: String
    )

    private const val MAX_ENTRIES = 2000
    private const val LOG_DIR = "logs"
    private val TS_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val buffer = ConcurrentLinkedDeque<Entry>()

    private val _flow = MutableSharedFlow<Entry>(replay = 200, extraBufferCapacity = 800)
    val flow: SharedFlow<Entry> = _flow.asSharedFlow()

    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, LOG_DIR).also { it.mkdirs() }
        val dateStr = DATE_FMT.format(Date())
        logFile = File(dir, "kit_log_$dateStr.txt")
        i("KIT", "=== KitLogger init — ${Date()} ===")
    }

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg — ${t.javaClass.simpleName}: ${t.message}" else msg
        log(Level.ERROR, tag, full)
    }

    private fun log(level: Level, tag: String, msg: String) {
        val entry = Entry(System.currentTimeMillis(), tag, level, msg)

        // ring buffer
        if (buffer.size >= MAX_ENTRIES) buffer.pollFirst()
        buffer.addLast(entry)

        // emit to flow (non-blocking)
        scope.launch { _flow.emit(entry) }

        // append to file
        scope.launch {
            logFile?.appendText(formatLine(entry) + "\n")
        }

        // also bridge to Android logcat so adb logcat works too
        try {
            val androidTag = "ShopeeKit/$tag"
            when (level) {
                Level.DEBUG -> android.util.Log.d(androidTag, msg)
                Level.INFO  -> android.util.Log.i(androidTag, msg)
                Level.WARN  -> android.util.Log.w(androidTag, msg)
                Level.ERROR -> android.util.Log.e(androidTag, msg)
            }
        } catch (_: RuntimeException) {
            // android.util.Log is not mocked in unit tests — silently ignore
        }
    }

    private fun formatLine(e: Entry): String {
        val time = TS_FMT.format(Date(e.ts))
        return "${e.level.label}/$time [${e.tag}] ${e.msg}"
    }

    fun getEntries(): List<Entry> = buffer.toList()

    fun clear() {
        buffer.clear()
        i("KIT", "=== Log cleared ===")
    }

    fun exportToFile(context: Context): android.net.Uri? {
        val file = logFile ?: return null
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (_: Exception) { null }
    }

    fun getLogFilePath(): String = logFile?.absolutePath ?: "(not initialized)"
}
