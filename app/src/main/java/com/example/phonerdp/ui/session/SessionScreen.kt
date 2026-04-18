package com.example.phonerdp.ui.session

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.phonerdp.domain.model.RdpConnectionStatus
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
    state: SessionUiState,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF080A0C)),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val frameAspectRatio = remember(frameMeta) {
                val meta = frameMeta
                if (meta != null && meta.width > 0 && meta.height > 0) {
                    meta.width.toFloat() / meta.height.toFloat()
                } else {
                    16f / 9f
                }
            }

            val containerAspectRatio = maxWidth / maxHeight
            val frameModifier = if (frameAspectRatio > containerAspectRatio) {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(frameAspectRatio)
            } else {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(frameAspectRatio)
            }

            Box(
                modifier = frameModifier
                    .align(Alignment.Center)
                    .background(Color(0xFF101214))
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
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                onDisconnect()
                onBack()
            },
            enabled = state.status != RdpConnectionStatus.DISCONNECTING,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .zIndex(2f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "\u65AD\u5F00\u8FDE\u63A5",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
