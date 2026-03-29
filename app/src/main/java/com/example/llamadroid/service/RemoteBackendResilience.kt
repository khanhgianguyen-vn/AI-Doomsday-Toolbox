package com.example.llamadroid.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal object RemoteBackendResilience {
    private val recoverableMessagePatterns = listOf(
        "software caused connection abort",
        "connection abort",
        "connection reset",
        "broken pipe",
        "failed to connect",
        "connection refused",
        "socket closed",
        "closed channel",
        "timed out",
        "timeout",
        "unexpected end of stream",
        "network is unreachable",
        "no route to host"
    )

    fun isRecoverable(throwable: Throwable?): Boolean {
        var current = throwable
        while (current != null) {
            if (current is ConnectException ||
                current is SocketException ||
                current is SocketTimeoutException ||
                current is InterruptedIOException ||
                current is NoRouteToHostException ||
                current is UnknownHostException ||
                current is EOFException ||
                current is SSLException
            ) {
                return true
            }
            val message = current.message?.lowercase().orEmpty()
            if (recoverableMessagePatterns.any { it in message }) {
                return true
            }
            current = current.cause
        }
        return false
    }

    fun summarize(throwable: Throwable?): String {
        var current = throwable
        while (current != null) {
            current.message?.takeIf { it.isNotBlank() }?.let { return it }
            current = current.cause
        }
        return throwable?.javaClass?.simpleName ?: "Unknown error"
    }

    suspend fun <T> runWithSingleRetry(
        onRetry: suspend (Throwable) -> Unit = {},
        shouldRetry: (Throwable) -> Boolean = { true },
        block: suspend () -> T
    ): T {
        return try {
            block()
        } catch (first: Throwable) {
            if (first is CancellationException) throw first
            if (!isRecoverable(first)) throw first
            if (!shouldRetry(first)) throw first
            onRetry(first)
            delay(1_500)
            block()
        }
    }
}
