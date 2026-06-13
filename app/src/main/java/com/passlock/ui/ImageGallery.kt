package com.passlock.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.passlock.VaultViewModel

/** Full-screen, swipeable, pinch-to-zoom viewer for the encrypted image attachments. */
@Composable
fun ImageViewer(vm: VaultViewModel, ids: List<String>, startIndex: Int, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, (ids.size - 1).coerceAtLeast(0))) { ids.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(vm, ids[page])
            }
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                IconAction("✕", "Close", tint = Color.White) { onDismiss() }
            }
            if (ids.size > 1) {
                Text(
                    "${pagerState.currentPage + 1} / ${ids.size}",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(vm: VaultViewModel, id: String) {
    var bmp by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id) {
        val bytes = vm.loadImage(id)
        bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    var scale by remember(id) { mutableStateOf(1f) }
    var offset by remember(id) { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier.fillMaxSize().pointerInput(id) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset = if (scale > 1f) offset + pan else Offset.Zero
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}
