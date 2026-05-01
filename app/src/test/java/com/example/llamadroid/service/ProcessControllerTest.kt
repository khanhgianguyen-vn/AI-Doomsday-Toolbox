package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessControllerTest {

    @Test
    fun `unexpected exit resolves to error state`() {
        val controller = ProcessController()

        val state = controller.resolveExitState(42, "exited")

        assertEquals(ServerState.Error("exited"), state)
    }

    @Test
    fun `intentional stop resolves to stopped state`() {
        val controller = ProcessController()
        controller.stop()

        val state = controller.resolveExitState(1, "ignored")

        assertTrue(state is ServerState.Stopped)
    }
}
