package com.example.phonerdp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.phonerdp.data.repository.EncryptedRecentConnectionRepository
import com.example.phonerdp.domain.model.ConnectionConfig
import com.example.phonerdp.domain.model.RdpConnectionStatus
import com.example.phonerdp.domain.model.RdpError
import com.example.phonerdp.domain.usecase.ConnectRdpUseCase
import com.example.phonerdp.domain.usecase.DisconnectRdpUseCase
import com.example.phonerdp.domain.validation.ConnectionConfigValidator
import com.example.phonerdp.native_bridge.CertificatePromptEvent
import com.example.phonerdp.native_bridge.RdpNativeBridge
import com.example.phonerdp.native_bridge.RdpNativeCodes
import com.example.phonerdp.native_bridge.SessionDisconnectedEvent
import com.example.phonerdp.ui.connection.ConnectionScreen
import com.example.phonerdp.ui.session.SessionScreen
import com.example.phonerdp.ui.session.SessionUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen {
    CONNECTION,
    SESSION,
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

@Composable
fun AppRoot() {
    val localContext = LocalContext.current
    val context = localContext.applicationContext
    val activity = localContext.findActivity()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val validator = remember { ConnectionConfigValidator() }
    val recentRepository = remember(context) { EncryptedRecentConnectionRepository(context) }
    val connectUseCase = remember { ConnectRdpUseCase() }
    val disconnectUseCase = remember { DisconnectRdpUseCase() }

    var currentScreen by remember { mutableStateOf(Screen.CONNECTION) }
    var activeConnection by remember { mutableStateOf(ConnectionConfig.empty()) }
    var sessionState by remember { mutableStateOf(SessionUiState()) }
    var recentConnections by remember { mutableStateOf(recentRepository.getRecentConnections()) }
    var reconnectAttempts by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentScreen, activity) {
        activity?.requestedOrientation = if (currentScreen == Screen.SESSION) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun connectCurrentTarget(config: ConnectionConfig, isReconnect: Boolean) {
        scope.launch {
            val nextReconnectAttempts = if (isReconnect) reconnectAttempts + 1 else 0
            reconnectAttempts = nextReconnectAttempts

            sessionState = SessionUiState(
                status = RdpConnectionStatus.CONNECTING,
                reconnectAttempts = nextReconnectAttempts,
                statusNote = if (isReconnect) "Trying to reconnect..." else "Connecting to target..."
            )

            val code = withContext(Dispatchers.IO) {
                connectUseCase(config)
            }

            sessionState = if (code >= 0) {
                SessionUiState(
                    status = RdpConnectionStatus.CONNECTED,
                    lastCode = code,
                    reconnectAttempts = nextReconnectAttempts,
                    statusNote = if (isReconnect) {
                        "Reconnected successfully."
                    } else {
                        "Connected."
                    }
                )
            } else {
                SessionUiState(
                    status = RdpConnectionStatus.ERROR,
                    lastCode = code,
                    error = RdpError.fromCode(code),
                    reconnectAttempts = nextReconnectAttempts,
                    statusNote = if (isReconnect) {
                        "Reconnect failed. You can retry."
                    } else {
                        "Connection failed. Tap Reconnect to try again."
                    }
                )
            }

            if (code < 0) {
                val error = RdpError.fromCode(code)
                snackbarHostState.showSnackbar("${error.title}: ${error.detail}")
            } else if (isReconnect) {
                snackbarHostState.showSnackbar("Reconnected.")
            }
        }
    }

    fun disconnectCurrentTarget() {
        scope.launch {
            sessionState = sessionState.copy(
                status = RdpConnectionStatus.DISCONNECTING,
                error = null,
                statusNote = "Disconnecting..."
            )

            val code = withContext(Dispatchers.IO) {
                disconnectUseCase()
            }

            sessionState = if (code >= 0) {
                SessionUiState(
                    status = RdpConnectionStatus.DISCONNECTED,
                    lastCode = code,
                    reconnectAttempts = reconnectAttempts,
                    statusNote = "Disconnected. Tap Reconnect to continue."
                )
            } else {
                SessionUiState(
                    status = RdpConnectionStatus.ERROR,
                    lastCode = code,
                    error = RdpError.fromCode(code),
                    reconnectAttempts = reconnectAttempts,
                    statusNote = "Disconnect failed."
                )
            }

            if (code < 0) {
                val error = RdpError.fromCode(code)
                snackbarHostState.showSnackbar("${error.title}: ${error.detail}")
            } else {
                snackbarHostState.showSnackbar("Session disconnected.")
            }
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            when (val event = withContext(Dispatchers.IO) { RdpNativeBridge.pollEvent() }) {
                is CertificatePromptEvent -> {
                    withContext(Dispatchers.IO) {
                        RdpNativeBridge.submitCertificateDecision(event.requestId, true)
                    }
                }

                is SessionDisconnectedEvent -> {
                    if (sessionState.status != RdpConnectionStatus.DISCONNECTING) {
                        val normalizedCode = if (event.code == RdpNativeCodes.OK) {
                            RdpNativeCodes.NOT_CONNECTED
                        } else {
                            event.code
                        }
                        sessionState = if (normalizedCode < 0) {
                            SessionUiState(
                                status = RdpConnectionStatus.ERROR,
                                lastCode = normalizedCode,
                                error = RdpError.fromCode(normalizedCode),
                                reconnectAttempts = reconnectAttempts,
                                statusNote = event.message.ifBlank { "Session disconnected unexpectedly." }
                            )
                        } else {
                            SessionUiState(
                                status = RdpConnectionStatus.DISCONNECTED,
                                lastCode = normalizedCode,
                                reconnectAttempts = reconnectAttempts,
                                statusNote = event.message.ifBlank { "Session disconnected by remote host." }
                            )
                        }
                        snackbarHostState.showSnackbar(sessionState.statusNote ?: "Session disconnected.")
                    }
                }

                null -> Unit
            }

            delay(120)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            if (currentScreen == Screen.CONNECTION) {
                SnackbarHost(hostState = snackbarHostState)
            }
        }
    ) { padding ->
        when (currentScreen) {
            Screen.CONNECTION -> ConnectionScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                initialValue = activeConnection,
                recentConnections = recentConnections,
                onConnectClick = { config ->
                    val validation = validator.validate(config)
                    if (!validation.isValid) {
                        scope.launch {
                            snackbarHostState.showSnackbar(validation.message)
                        }
                        return@ConnectionScreen
                    }

                    recentRepository.saveConnection(config)
                    recentConnections = recentRepository.getRecentConnections()
                    activeConnection = config
                    currentScreen = Screen.SESSION
                    connectCurrentTarget(config, isReconnect = false)
                },
                onRecentSelected = { config ->
                    activeConnection = config
                },
            )

            Screen.SESSION -> SessionScreen(
                modifier = Modifier.fillMaxSize(),
                state = sessionState,
                onDisconnect = { disconnectCurrentTarget() },
                onBack = { currentScreen = Screen.CONNECTION }
            )
        }
    }
}
