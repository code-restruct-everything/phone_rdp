package com.example.phonerdp.domain.usecase

import com.example.phonerdp.domain.model.ConnectionConfig
import com.example.phonerdp.native_bridge.RdpNativeBridge

class ConnectRdpUseCase {
    operator fun invoke(config: ConnectionConfig): Int {
        return RdpNativeBridge.connect(config)
    }
}
