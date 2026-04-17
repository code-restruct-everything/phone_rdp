package com.example.phonerdp.native_bridge

sealed interface NativeBridgeEvent

data class CertificatePromptEvent(
    val requestId: Long,
    val host: String,
    val port: Int,
    val commonName: String,
    val subject: String,
    val issuer: String,
    val fingerprint: String,
    val changed: Boolean,
) : NativeBridgeEvent

data class SessionDisconnectedEvent(
    val code: Int,
    val freerdpError: Long,
    val message: String,
) : NativeBridgeEvent
