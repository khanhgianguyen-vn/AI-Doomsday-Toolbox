package com.example.llamadroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.llamadroid.R
import kotlinx.coroutines.runBlocking

class AiRuntimeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val hasActiveJobs = runBlocking {
            AiRuntimeJobStore.getActiveJobs(context.applicationContext).isNotEmpty()
        }
        if (hasActiveJobs) {
            AgentForegroundService.start(
                context.applicationContext,
                context.getString(R.string.agent_runtime_recovering_jobs)
            )
        }
    }
}
