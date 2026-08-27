package com.djbooya.speedvolume

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        logFile = File(logsDir, "speedvolume.log")
        cleanupOldLogs(logsDir)
    }

    fun d(tag: String, message: String) {
        log("DEBUG", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        log("ERROR", tag, msg)
    }

    fun i(tag: String, message: String) {
        log("INFO", tag, message)
    }

    fun w(tag: String, message: String) {
        log("WARN", tag, message)
    }

    private fun log(level: String, tag: String, message: String) {
        if (logFile == null) return
        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "[$timestamp] $level/$tag: $message\n"
            logFile!!.appendText(logLine)
        } catch (e: Exception) {
            android.util.Log.e("DebugLog", "Failed to write log", e)
        }
    }

    private fun cleanupOldLogs(logsDir: File) {
        try {
            val now = System.currentTimeMillis()
            val oneDayMs = 24 * 60 * 60 * 1000
            logsDir.listFiles()?.forEach { file ->
                if (file.isFile && now - file.lastModified() > oneDayMs) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DebugLog", "Failed to cleanup old logs", e)
        }
    }

    fun getLogFile(): File? = logFile

    fun clearLogs() {
        try {
            logFile?.delete()
            logFile?.createNewFile()
            d("DebugLog", "Logs cleared")
        } catch (e: Exception) {
            e("DebugLog", "Failed to clear logs", e)
        }
    }
}
