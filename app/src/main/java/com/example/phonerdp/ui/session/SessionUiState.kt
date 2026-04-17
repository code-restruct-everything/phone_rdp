package com.example.phonerdp.ui.session

import com.example.phonerdp.domain.model.RdpConnectionStatus
import com.example.phonerdp.domain.model.RdpError

data class SessionUiState(
    val status: RdpConnectionStatus = RdpConnectionStatus.IDLE,
    val lastCode: Int? = null,
    val error: RdpError? = null,
    val reconnectAttempts: Int = 0,
    val statusNote: String? = null,
) {
    val statusText: String
        get() = when (status) {
            RdpConnectionStatus.IDLE -> "Idle"
            RdpConnectionStatus.CONNECTING -> "Connecting"
            RdpConnectionStatus.CONNECTED -> "Connected"
            RdpConnectionStatus.DISCONNECTING -> "Disconnecting"
            RdpConnectionStatus.DISCONNECTED -> "Disconnected"
            RdpConnectionStatus.ERROR -> "Error"
        }
}
