package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TamaWorkCatalogTest {
    @Test
    fun `work pay ladder matches the agreed progression`() {
        val expectedHourlyPay = mapOf(
            "town_runner" to 8L,
            "shop_helper" to 14L,
            "garden_keeper" to 24L,
            "library_helper" to 40L,
            "potion_assistant" to 58L,
            "town_scholar" to 80L
        )

        expectedHourlyPay.forEach { (jobId, hourlyPay) ->
            val job = TamaWorkCatalog.jobById(jobId)
            assertNotNull("Expected job $jobId to exist", job)
            assertEquals(hourlyPay, job?.hourlyPay)
        }
    }
}
