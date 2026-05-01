package com.example.llamadroid

internal object AppStartupDeploymentPolicy {
    fun shouldSkipDeploymentAfterUpdate(
        previousVersionCode: Long,
        currentVersionCode: Long
    ): Boolean {
        return previousVersionCode > 0L && previousVersionCode != currentVersionCode
    }

    fun shouldDeferStartupProvisioning(
        previousVersionCode: Long,
        currentVersionCode: Long
    ): Boolean {
        return shouldSkipDeploymentAfterUpdate(previousVersionCode, currentVersionCode)
    }
}
