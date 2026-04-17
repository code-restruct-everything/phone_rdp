package com.example.phonerdp.ui.session

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.phonerdp.domain.model.ConnectionConfig
import com.example.phonerdp.domain.model.RdpConnectionStatus
import com.example.phonerdp.native_bridge.CertificatePromptEvent
import com.example.phonerdp.native_bridge.FrameMeta
import com.example.phonerdp.native_bridge.PointerAction
import com.example.phonerdp.native_bridge.RdpNativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SessionScreen(
    modifier: Modifier = Modifier,
    config: ConnectionConfig,
    state: SessionUiState,
    pendingCertificatePrompt: CertificatePromptEvent?,
    onCertificateAccept: (CertificatePromptEvent) -> Unit,
    onCertificateReject: (CertificatePromptEvent) -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }
    var softInputText by remember { mutableStateOf("") }
    var inputLastCode by remember { mutableStateOf<Int?>(null) }

    var frameMeta by remember { mutableStateOf<FrameMeta?>(null) }
    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var frameSequence by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var frameTick by remember { mutableIntStateOf(0) }

    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var scrollAccumulator by remember { mutableFloatStateOf(0f) }

    val renderedImage = remember(frameBitmap, frameTick) {
        frameBitmap?.asImageBitmap()
    }

    fun toRemotePoint(localOffset: Offset): Pair<Int, Int>? {
        val meta = frameMeta ?: return null
        val width = surfaceSize.width
        val height = surfaceSize.height
        if (width <= 0 || height <= 0) return null

        val normalizedX = (localOffset.x / zoom).coerceIn(0f, width.toFloat()) / width.toFloat()
        val normalizedY = (localOffset.y / zoom).coerceIn(0f, height.toFloat()) / height.toFloat()

        val x = (normalizedX * meta.width).roundToInt().coerceIn(0, meta.width - 1)
        val y = (normalizedY * meta.height).roundToInt().coerceIn(0, meta.height - 1)
        return x to y
    }

    fun sendPointer(action: Int, offset: Offset, delta: Int = 0) {
        val remote = toRemotePoint(offset) ?: return
        scope.launch(Dispatchers.IO) {
            RdpNativeBridge.sendPointerEvent(action, remote.first, remote.second, delta)
        }
    }

    fun sendSoftInput() {
        val payload = softInputText
        if (payload.isBlank()) {
            return
        }

        scope.launch {
            val rc = withContext(Dispatchers.IO) {
                RdpNativeBridge.sendTextInput(payload)
            }
            inputLastCode = rc
            if (rc >= 0) {
                softInputText = ""
            }
        }
    }

    LaunchedEffect(state.status) {
        if (state.status != RdpConnectionStatus.CONNECTED) {
            return@LaunchedEffect
        }

        while (isActive) {
            val meta = withContext(Dispatchers.IO) { RdpNativeBridge.getFrameMeta() }
            if (meta != null && meta.width > 0 && meta.height > 0) {
                val needsBitmap = frameBitmap == null ||
                    frameBitmap?.width != meta.width ||
                    frameBitmap?.height != meta.height

                if (needsBitmap) {
                    frameBitmap = Bitmap.createBitmap(meta.width, meta.height, Bitmap.Config.ARGB_8888)
                }

                val targetBitmap = frameBitmap
                if (targetBitmap != null && meta.sequence != frameSequence) {
                    val copyCode = withContext(Dispatchers.IO) {
                        RdpNativeBridge.copyFrameToBitmap(targetBitmap)
                    }
                    if (copyCode >= 0) {
                        frameMeta = meta
                        frameSequence = meta.sequence
                        frameTick++
                    }
                }
            }

            delay(33)
        }
    }

    if (pendingCertificatePrompt != null) {
        AlertDialog(
            onDismissRequest = { /* force explicit choice */ },
            title = { Text(if (pendingCertificatePrompt.changed) "Certificate Changed" else "Certificate Verification") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Host: ${pendingCertificatePrompt.host}:${pendingCertificatePrompt.port}")
                    if (pendingCertificatePrompt.commonName.isNotBlank()) {
                        Text("CN: ${pendingCertificatePrompt.commonName}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (pendingCertificatePrompt.issuer.isNotBlank()) {
                        Text("Issuer: ${pendingCertificatePrompt.issuer}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = "Fingerprint: ${pendingCertificatePrompt.fingerprint}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Trust this certificate for this session?",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onCertificateAccept(pendingCertificatePrompt) }) {
                    Text("Trust Once")
                }
            },
            dismissButton = {
                TextButton(onClick = { onCertificateReject(pendingCertificatePrompt) }) {
                    Text("Reject")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Session", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "Connected target")
                Text(text = "${config.host}:${config.port}", style = MaterialTheme.typography.titleMedium)
                Text(text = "User: ${config.username}", style = MaterialTheme.typography.bodySmall)
                Text(text = "Status: ${state.statusText}", style = MaterialTheme.typography.bodyMedium)
                state.lastCode?.let { code ->
                    Text(text = "Native code: $code", style = MaterialTheme.typography.bodySmall)
                }
                if (state.reconnectAttempts > 0) {
                    Text(
                        text = "Reconnect attempts: ${state.reconnectAttempts}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                state.statusNote?.let { note ->
                    Text(text = note, style = MaterialTheme.typography.bodySmall)
                }
                frameMeta?.let { meta ->
                    Text(
                        text = "Frame: ${meta.width}x${meta.height} | Zoom: ${"%.2f".format(zoom)}x",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (state.error != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = state.error.title, style = MaterialTheme.typography.titleMedium)
                    Text(text = state.error.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Remote Surface")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(Color(0xFF1A1C1E))
                        .pointerInput(frameMeta, zoom, surfaceSize) {
                            detectTapGestures(
                                onTap = { offset ->
                                    sendPointer(PointerAction.LEFT_CLICK, offset)
                                },
                                onLongPress = { offset ->
                                    sendPointer(PointerAction.RIGHT_CLICK, offset)
                                }
                            )
                        }
                        .pointerInput(frameMeta, zoom, surfaceSize) {
                            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(1f, 3f)

                                scrollAccumulator += pan.y
                                val threshold = 24f
                                while (abs(scrollAccumulator) >= threshold) {
                                    val delta = if (scrollAccumulator > 0) -120 else 120
                                    sendPointer(PointerAction.SCROLL, centroid, delta)
                                    scrollAccumulator += if (scrollAccumulator > 0) -threshold else threshold
                                }
                            }
                        }
                        .background(Color(0xFF101214))
                        .padding(2.dp)
                        .onSizeChanged { surfaceSize = it }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .graphicsLayer(
                                scaleX = zoom,
                                scaleY = zoom,
                                transformOrigin = TransformOrigin(0f, 0f)
                            )
                    ) {
                        if (renderedImage != null) {
                            Image(
                                bitmap = renderedImage,
                                contentDescription = "Remote Desktop",
                                modifier = Modifier
                                    .fillMaxSize(),
                                contentScale = ContentScale.FillBounds
                            )
                        } else {
                            Text(
                                text = "Waiting for first remote frame...",
                                modifier = Modifier.padding(12.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Soft Keyboard")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = softInputText,
                        onValueChange = { softInputText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Input text") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { sendSoftInput() })
                    )

                    Button(
                        onClick = { sendSoftInput() },
                        modifier = Modifier.widthIn(min = 88.dp)
                    ) {
                        Text("Send")
                    }
                }

                inputLastCode?.let { code ->
                    Text(
                        text = "Input code: $code",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onReconnect,
                enabled = state.status != RdpConnectionStatus.CONNECTING &&
                    state.status != RdpConnectionStatus.DISCONNECTING
            ) {
                Text("Reconnect")
            }

            Button(
                onClick = onDisconnect,
                enabled = state.status == RdpConnectionStatus.CONNECTED
            ) {
                Text("Disconnect")
            }

            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}
