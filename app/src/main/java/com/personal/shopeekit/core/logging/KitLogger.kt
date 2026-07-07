package com.personal.shopeekit.core.logging

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

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
    // O(1) size — ConcurrentLinkedDeque.size() is an O(n) traversal, and this runs
    // on every single log call.
    private val bufferCount = AtomicInteger(0)

    private val _flow = MutableSharedFlow<Entry>(replay = 200, extraBufferCapacity = 800)
    val flow: SharedFlow<Entry> = _flow.asSharedFlow()

    // Single-consumer file writer: entries are drained in FIFO order by one
    // coroutine holding an open BufferedWriter, so lines can't interleave/reorder
    // (the old per-line appendText launched on the IO pool, so order wasn't
    // guaranteed and each line paid an open/close). Unbounded — log volume is low.
    private val writeChannel = Channel<Entry>(Channel.UNLIMITED)

    private var logDir: File? = null
    @Volatile private var currentFile: File? = null
    private var currentDate: String = ""
    private var writer: BufferedWriter? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, LOG_DIR).also { it.mkdirs() }
        logDir = dir
        currentFile = File(dir, "kit_log_${DATE_FMT.format(Date())}.txt")
        scope.launch { consumeWrites() }
        i("KIT", "=== KitLogger init — ${Date()} ===")
    }

    /**
     * Drain [writeChannel] with a persistent [BufferedWriter], rolling the file
     * over when the calendar day changes. One consumer → deterministic line order.
     */
    private suspend fun consumeWrites() {
        for (entry in writeChannel) {
            try {
                val date = DATE_FMT.format(Date(entry.ts))
                if (writer == null || date != currentDate) {
                    writer?.runCatching { flush(); close() }
                    currentDate = date
                    val dir = logDir ?: continue
                    val file = File(dir, "kit_log_$date.txt")
                    currentFile = file
                    // FileWriter(append=true), not File.bufferedWriter() which truncates —
                    // a same-day restart must append, not wipe the day's log.
                    writer = java.io.FileWriter(file, /* append = */ true).buffered()
                }
                writer?.apply {
                    write(formatLine(entry))
                    newLine()
                    // Flush per line so a `run-as cat` of the log sees the latest
                    // entries immediately (debugging aid); still one open handle.
                    flush()
                }
            } catch (_: Exception) {
                // Never let a logging failure crash the app.
            }
        }
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

        // ring buffer with O(1) size tracking
        buffer.addLast(entry)
        if (bufferCount.incrementAndGet() > MAX_ENTRIES) {
            if (buffer.pollFirst() != null) bufferCount.decrementAndGet()
        }

        // live viewers — tryEmit is non-blocking (extraBufferCapacity absorbs bursts),
        // so no coroutine launch per log line.
        _flow.tryEmit(entry)

        // hand off to the single file-writer (non-blocking; unbounded channel)
        writeChannel.trySend(entry)

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
        bufferCount.set(0)
        i("KIT", "=== Log cleared ===")
    }

    fun exportToFile(context: Context): android.net.Uri? {
        val file = currentFile ?: return null
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (_: Exception) { null }
    }

    fun getLogFilePath(): String = currentFile?.absolutePath ?: "(not initialized)"
}
