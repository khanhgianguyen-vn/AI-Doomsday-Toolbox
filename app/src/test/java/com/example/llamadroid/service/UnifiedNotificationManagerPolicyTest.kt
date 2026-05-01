package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedNotificationManagerPolicyTest {

    @Test
    fun `only agent tasks opt into completion alerts by default`() {
        assertEquals(
            UnifiedNotificationManager.CompletionAlertPolicy.SUCCESS_ONLY,
            UnifiedNotificationManager.TaskType.AGENT.defaultCompletionAlertPolicy
        )
        assertEquals(
            UnifiedNotificationManager.CompletionAlertPolicy.NEVER,
            UnifiedNotificationManager.TaskType.IMAGE_GEN.defaultCompletionAlertPolicy
        )
        assertEquals(
            UnifiedNotificationManager.CompletionAlertPolicy.NEVER,
            UnifiedNotificationManager.TaskType.ADVENTURE.defaultCompletionAlertPolicy
        )
        assertEquals(
            UnifiedNotificationManager.CompletionAlertPolicy.NEVER,
            UnifiedNotificationManager.TaskType.LLAMA_SERVER.defaultCompletionAlertPolicy
        )
    }
}
