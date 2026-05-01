package com.example.llamadroid.service

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL

class OllamaServiceCancellationTest {

    @Test
    fun `stop only disconnects connections owned by the requested job`() {
        val targetJob = Job()
        val otherJob = Job()
        val targetConnection = RecordingHttpURLConnection()
        val otherConnection = RecordingHttpURLConnection()

        registerConnection(targetJob, targetConnection)
        registerConnection(otherJob, otherConnection)

        OllamaService.stop(targetJob)

        assertTrue(targetConnection.disconnected)
        assertFalse(otherConnection.disconnected)

        OllamaService.stop(otherJob)
    }

    private fun registerConnection(job: CompletableJob, connection: HttpURLConnection) {
        val method: Method = OllamaService.Companion::class.java.getDeclaredMethod(
            "registerConnection",
            Job::class.java,
            HttpURLConnection::class.java
        )
        method.isAccessible = true
        method.invoke(OllamaService.Companion, job, connection)
    }

    private class RecordingHttpURLConnection : HttpURLConnection(URL("http://localhost")) {
        var disconnected = false

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }
}
