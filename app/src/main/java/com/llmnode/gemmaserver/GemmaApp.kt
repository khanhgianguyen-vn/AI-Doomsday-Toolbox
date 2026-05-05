package com.llmnode.gemmaserver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class GemmaApp : Application() {
    companion object {
        const val CHANNEL_ID = "gemma_server_channel"
        const val CHANNEL_NAME = "Gemma Server"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Gemma LLM server status"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
