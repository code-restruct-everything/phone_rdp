package com.example.phonerdp.domain.usecase

import com.example.phonerdp.native_bridge.RdpNativeBridge

class DisconnectRdpUseCase {
    operator fun invoke(): Int {
        return RdpNativeBridge.disconnect()
    }
}
