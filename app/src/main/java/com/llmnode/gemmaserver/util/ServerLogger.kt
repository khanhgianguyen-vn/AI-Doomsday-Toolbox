package com.llmnode.gemmaserver.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

object ServerLogger {
    private const val MAX_SIZE = 5 * 1024 * 1024L // 5MB
    private const val LOG_FILE = "server.log"
    private const val OLD_LOG_FILE = "server.log.old"

    private var logFile: File? = null
    private var logDir: File? = null

    fun init(context: Context) {
        logDir = context.getExternalFilesDir("logs")
        logDir?.mkdirs()
        logFile = File(logDir, LOG_FILE)
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val file = logFile ?: return
        try {
            // Rotate if too large
            if (file.exists() && file.length() > MAX_SIZE) {
                val old = File(logDir, OLD_LOG_FILE)
                old.delete()
                file.renameTo(old)
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            PrintWriter(FileWriter(file, true)).use { pw ->
                pw.println("$timestamp [$tag] $message")
            }
        } catch (_: Exception) { }
    }

    fun i(tag: String, msg: String) { log(tag, "INFO: $msg"); android.util.Log.i(tag, msg) }
    fun e(tag: String, msg: String) { log(tag, "ERROR: $msg"); android.util.Log.e(tag, msg) }
    fun w(tag: String, msg: String) { log(tag, "WARN: $msg"); android.util.Log.w(tag, msg) }
}
