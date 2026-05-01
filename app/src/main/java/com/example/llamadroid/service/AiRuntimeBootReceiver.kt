package com.example.llamadroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AiRuntimeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AiRuntimeRecovery.isRelevantAction(intent?.action)) {
            return
        }

        val pendingResult = goAsync()
        AiRuntimeRecovery.dispatch(
            context = context,
            action = intent?.action,
            onFinished = pendingResult::finish
        )
    }
}
