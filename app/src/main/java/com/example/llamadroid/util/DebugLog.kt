package com.example.llamadroid.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(val timestamp: Long, val message: String)

object DebugLog {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()
    
    // Patterns to filter out (noisy server logs + sensitive build info)
    private val filterPatterns = listOf(
        // Noisy server health checks
        "GET /health",
        "GET /props", 
        "log_server_r: request: GET /health",
        "log_server_r: request: GET /props",
        // Sensitive build configuration info (security)
        "configuration:",
        "--prefix=",
        "--cc=",
        "--cxx=",
        "--ar=",
        "--ranlib=",
        "--strip=",
        "--sysroot=",
        "--extra-cflags=",
        "--extra-ldflags=",
        "/home/",
        "/Users/",
        "/mnt/",
        "prebuilt/linux",
        "toolchains/llvm"
    )
    
    fun log(message: String) {
        // Skip noisy logs that spam the output
        if (filterPatterns.any { message.contains(it) }) {
            return
        }
        
        val entry = LogEntry(System.currentTimeMillis(), message)
        _logs.value = _logs.value + entry
    }
    
    fun clear() {
        _logs.value = emptyList()
    }
}
