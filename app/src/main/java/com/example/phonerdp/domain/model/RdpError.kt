package com.example.phonerdp.domain.model

import com.example.phonerdp.native_bridge.RdpNativeCodes

data class RdpError(
    val code: Int,
    val title: String,
    val detail: String,
) {
    companion object {
        fun fromCode(code: Int): RdpError = when (code) {
            RdpNativeCodes.INVALID_ARGUMENT -> RdpError(
                code = code,
                title = "Invalid Parameters",
                detail = "Please verify host, port, username and password."
            )

            RdpNativeCodes.NETWORK_FAILURE -> RdpError(
                code = code,
                title = "Network Failure",
                detail = "Unable to reach target host or RDP transport failed."
            )

            RdpNativeCodes.AUTH_FAILURE -> RdpError(
                code = code,
                title = "Authentication Failed",
                detail = "Username or password was rejected by the remote host."
            )

            RdpNativeCodes.TLS_FAILURE -> RdpError(
                code = code,
                title = "TLS/NLA Failure",
                detail = "Security negotiation failed. Check certificate trust and NLA policy."
            )

            RdpNativeCodes.CERTIFICATE_REJECTED -> RdpError(
                code = code,
                title = "Certificate Rejected",
                detail = "Connection was cancelled because the server certificate was not trusted."
            )

            RdpNativeCodes.BACKEND_UNAVAILABLE -> RdpError(
                code = code,
                title = "RDP Backend Unavailable",
                detail = "Native backend is unavailable. Verify FreeRDP native libraries for current ABI."
            )

            RdpNativeCodes.FRAME_UNAVAILABLE -> RdpError(
                code = code,
                title = "Frame Not Ready",
                detail = "Connected, but no remote frame has been produced yet."
            )

            RdpNativeCodes.INPUT_FAILURE -> RdpError(
                code = code,
                title = "Input Send Failed",
                detail = "Failed to forward pointer or keyboard input to the remote session."
            )

            else -> RdpError(
                code = code,
                title = "Unknown Error",
                detail = "Unexpected error code: $code"
            )
        }
    }
}
