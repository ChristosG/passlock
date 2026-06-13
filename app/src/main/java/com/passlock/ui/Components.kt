package com.passlock.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.passlock.VaultViewModel
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A password text field with a tap-to-show eye toggle. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Text(
                text = if (visible) "🙈" else "👁",
                modifier = Modifier.clickable { visible = !visible }.padding(12.dp),
            )
        },
        modifier = modifier,
    )
}

/** A glyph "icon" button with a long-press tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconAction(
    glyph: String,
    tooltip: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    glyphSize: TextUnit = 20.sp,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        Text(
            text = glyph,
            color = tint,
            fontSize = glyphSize,
            modifier = Modifier.clickable(onClick = onClick).padding(10.dp),
        )
    }
}

/** Loads an encrypted image attachment by id, decrypts it, and renders it. */
@Composable
fun EncryptedImage(vm: VaultViewModel, id: String, modifier: Modifier = Modifier) {
    var bmp by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id) {
        val bytes = vm.loadImage(id)
        bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    val image = bmp
    if (image != null) {
        Image(bitmap = image, contentDescription = "attachment", modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** A small (i) that shows a help tooltip when tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoTip(text: String) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = state,
    ) {
        Text(
            text = "ⓘ",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { scope.launch { state.show() } }.padding(8.dp),
        )
    }
}
