package com.example.llamadroid.service

import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class AiRuntimeRecoveryTest {

    @Test
    fun `package replace recovery dispatch returns quickly and runs asynchronously`() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val elapsedMs = measureTimeMillis {
        AiRuntimeRecovery.dispatch(
            context = context,
            action = Intent.ACTION_BOOT_COMPLETED,
            scope = scope,
            recover = {
                started.countDown()
                release.await(1, TimeUnit.SECONDS)
            }
        )
        }

        assertTrue("dispatch should return quickly", elapsedMs < 200)
        assertTrue("recovery should run on the background scope", started.await(1, TimeUnit.SECONDS))
        release.countDown()
        assertFalse("package replace should not trigger boot recovery anymore", AiRuntimeRecovery.isRelevantAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse("irrelevant actions must not be treated as recovery triggers", AiRuntimeRecovery.isRelevantAction("anything_else"))
    }
}
