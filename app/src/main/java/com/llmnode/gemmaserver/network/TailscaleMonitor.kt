package com.llmnode.gemmaserver.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class TailscaleStatus(
    val isConnected: Boolean = false,
    val tailscaleIp: String? = null,
    val lanIp: String? = null
)

class TailscaleMonitor {
    private val _status = MutableStateFlow(TailscaleStatus())
    val status: StateFlow<TailscaleStatus> = _status

    private var job: Job? = null

    fun startPolling(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _status.value = detectNetworkStatus()
                delay(5000)
            }
        }
    }

    fun stopPolling() {
        job?.cancel()
        job = null
    }

    fun detectNow() {
        _status.value = detectNetworkStatus()
    }

    private fun detectNetworkStatus(): TailscaleStatus {
        var tailscaleIp: String? = null
        var lanIp: String? = null

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return TailscaleStatus()
            for (iface in interfaces) {
                if (!iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        // Tailscale IPs are in the 100.x.y.z CGNAT range
                        if (ip.startsWith("100.")) {
                            tailscaleIp = ip
                        } else if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            lanIp = ip
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        return TailscaleStatus(
            isConnected = tailscaleIp != null,
            tailscaleIp = tailscaleIp,
            lanIp = lanIp
        )
    }
}
