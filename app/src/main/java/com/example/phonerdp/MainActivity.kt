package com.example.phonerdp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.phonerdp.native_bridge.RdpNativeBridge
import com.example.phonerdp.ui.AppRoot
import com.example.phonerdp.ui.theme.PhoneRdpTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RdpNativeBridge.configureNativePaths(
            homePath = filesDir.absolutePath,
            cachePath = cacheDir.absolutePath
        )
        setContent {
            PhoneRdpTheme {
                AppRoot()
            }
        }
    }
}
