package com.tw.perapp4grouter

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "AppLogger"
    private val logs = mutableListOf<String>()
    private val listeners = mutableListOf<(String) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("[HH:mm:ss] ", Locale.getDefault())

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        appendLog(tag, message)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        Log.e(tag, message, t)
        appendLog(tag, message + (t?.let { " - ${it.message}" } ?: ""))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        appendLog(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        appendLog(tag, message)
    }

    private fun appendLog(tag: String, message: String) {
        val timeString = dateFormat.format(Date())
        val logLine = "$timeString$message"
        synchronized(logs) {
            logs.add(logLine)
            if (logs.size > 200) {
                logs.removeAt(0)
            }
        }
        mainHandler.post {
            listeners.forEach { it.invoke(logLine) }
        }
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        // 回放現有日誌
        synchronized(logs) {
            logs.forEach { listener.invoke(it) }
        }
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
    }
}
