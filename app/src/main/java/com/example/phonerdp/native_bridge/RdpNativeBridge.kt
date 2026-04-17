package com.example.phonerdp.native_bridge

import android.graphics.Bitmap
import com.example.phonerdp.domain.model.ConnectionConfig
import org.json.JSONObject

data class FrameMeta(
    val width: Int,
    val height: Int,
    val sequence: Int,
)

object PointerAction {
    const val MOVE = 0
    const val LEFT_CLICK = 1
    const val RIGHT_CLICK = 2
    const val SCROLL = 3
}

object RdpNativeBridge {
    private val nativeLoaded: Boolean = runCatching {
        System.loadLibrary("rdpbridge")
        true
    }.getOrDefault(false)

    @Volatile
    private var initialized = false

    fun connect(config: ConnectionConfig): Int {
        if (!nativeLoaded) {
            return RdpNativeCodes.BACKEND_UNAVAILABLE
        }

        val initCode = ensureInitialized()
        if (initCode != RdpNativeCodes.OK) {
            return initCode
        }

        return runCatching {
            nativeConnect(
                config.host,
                config.port,
                config.username,
                config.password,
                config.domain
            )
        }.getOrElse {
            RdpNativeCodes.INTERNAL_ERROR
        }
    }

    fun disconnect(): Int {
        if (!nativeLoaded) {
            return RdpNativeCodes.BACKEND_UNAVAILABLE
        }

        return runCatching {
            nativeDisconnect()
        }.getOrElse {
            RdpNativeCodes.INTERNAL_ERROR
        }
    }

    fun sendTextInput(text: String): Int {
        if (!nativeLoaded) {
            return RdpNativeCodes.BACKEND_UNAVAILABLE
        }

        return runCatching {
            nativeSendTextInput(text)
        }.getOrElse {
            RdpNativeCodes.INTERNAL_ERROR
        }
    }

    fun sendPointerEvent(action: Int, x: Int, y: Int, delta: Int = 0): Int {
        if (!nativeLoaded) {
            return RdpNativeCodes.BACKEND_UNAVAILABLE
        }

        return runCatching {
            nativeSendPointerEvent(action, x, y, delta)
        }.getOrElse {
            RdpNativeCodes.INTERNAL_ERROR
        }
    }

    fun getFrameMeta(): FrameMeta? {
        if (!nativeLoaded) {
            return null
        }

        val info = IntArray(3)
        val rc = runCatching { nativeGetFrameInfo(info) }
            .getOrElse { RdpNativeCodes.INTERNAL_ERROR }

        if (rc < 0 || info[0] <= 0 || info[1] <= 0) {
            return null
        }

        return FrameMeta(
            width = info[0],
            height = info[1],
            sequence = info[2]
        )
    }

    fun copyFrameToBitmap(bitmap: Bitmap): Int {
        if (!nativeLoaded) {
            return RdpNativeCodes.BACKEND_UNAVAILABLE
        }

        return runCatching {
            nativeCopyFrameToBitmap(bitmap)
        }.getOrElse {
            RdpNativeCodes.INTERNAL_ERROR
        }
    }

    fun pollEvent(): NativeBridgeEvent? {
        if (!nativeLoaded) {
            return null
        }

        val raw = runCatching { nativePollEvent() }.getOrNull() ?: return null
        if (raw.isBlank()) {
            return null
        }

        return runCatching {
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "CERTIFICATE_PROMPT" -> CertificatePromptEvent(
                    requestId = json.optLong("requestId"),
                    host = json.optString("host"),
                    port = json.optInt("port"),
                    commonName = json.optString("commonName"),
                    subject = json.optString("subject"),
                    issuer = json.optString("issuer"),
                    fingerprint = json.optString("fingerprint"),
                    changed = json.optBoolean("changed", false),
                )

                "SESSION_DISCONNECTED" -> SessionDisconnectedEvent(
                    code = json.optInt("code", RdpNativeCodes.NOT_CONNECTED),
                    freerdpError = json.optLong("freerdpError"),
                    message = json.optString("message", "Session disconnected."),
                )

                else -> null
            }
        }.getOrNull()
    }

    fun submitCertificateDecision(requestId: Long, accept: Boolean): Int {
        if (!nativeLoaded) {
            return RdpNativeCodes.BACKEND_UNAVAILABLE
        }

        return runCatching {
            nativeSubmitCertificateDecision(requestId, accept)
        }.getOrElse {
            RdpNativeCodes.INTERNAL_ERROR
        }
    }

    private fun ensureInitialized(): Int {
        if (initialized) {
            return RdpNativeCodes.OK
        }

        val ok = runCatching { nativeInit() }.getOrDefault(false)
        if (!ok) {
            return RdpNativeCodes.BACKEND_UNAVAILABLE
        }

        initialized = true
        return RdpNativeCodes.OK
    }

    private external fun nativeInit(): Boolean
    private external fun nativeConnect(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String?,
    ): Int

    private external fun nativeDisconnect(): Int
    private external fun nativeSendTextInput(text: String): Int
    private external fun nativeSendPointerEvent(action: Int, x: Int, y: Int, delta: Int): Int
    private external fun nativeGetFrameInfo(outInfo: IntArray): Int
    private external fun nativeCopyFrameToBitmap(bitmap: Bitmap): Int
    private external fun nativePollEvent(): String?
    private external fun nativeSubmitCertificateDecision(requestId: Long, accept: Boolean): Int
}
