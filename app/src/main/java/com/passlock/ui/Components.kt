package com.passlock.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
