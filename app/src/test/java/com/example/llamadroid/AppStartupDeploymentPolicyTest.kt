package com.example.llamadroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartupDeploymentPolicyTest {

    @Test
    fun `fresh installs do not skip deployment`() {
        assertFalse(
            AppStartupDeploymentPolicy.shouldSkipDeploymentAfterUpdate(
                previousVersionCode = 0L,
                currentVersionCode = 936L
            )
        )
    }

    @Test
    fun `updates skip the blocking startup deployment path`() {
        assertTrue(
            AppStartupDeploymentPolicy.shouldSkipDeploymentAfterUpdate(
                previousVersionCode = 935L,
                currentVersionCode = 936L
            )
        )
    }

    @Test
    fun `updates defer startup provisioning`() {
        assertTrue(
            AppStartupDeploymentPolicy.shouldDeferStartupProvisioning(
                previousVersionCode = 935L,
                currentVersionCode = 936L
            )
        )
    }
}
