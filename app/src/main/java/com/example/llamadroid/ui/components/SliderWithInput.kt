package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A slider with an editable text field for manual input.
 * The text field shows the current value and can be edited by the user.
 */
@Composable
fun SliderWithInput(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    label: String? = null,
    steps: Int = 0,
    enabled: Boolean = true,
    isInteger: Boolean = false,
    decimalPlaces: Int = 2,
    suffix: String = ""
) {
    val focusManager = LocalFocusManager.current
    var textValue by remember(value) {
        mutableStateOf(
            if (isInteger) value.roundToInt().toString()
            else String.format("%.${decimalPlaces}f", value)
        )
    }
    var isEditing by remember { mutableStateOf(false) }

    fun normalizedText(parsedValue: Float): String {
        return if (isInteger) {
            parsedValue.roundToInt().toString()
        } else {
            String.format("%.${decimalPlaces}f", parsedValue)
        }
    }

    fun commitCurrentText(normalizeText: Boolean) {
        val parsed = textValue.toFloatOrNull() ?: return
        val clamped = parsed.coerceIn(valueRange.start, valueRange.endInclusive)
        onValueChange(clamped)
        if (normalizeText) {
            textValue = normalizedText(clamped)
        }
    }

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Slider(
                value = value,
                onValueChange = { newValue ->
                    isEditing = false
                    onValueChange(newValue)
                    textValue = normalizedText(newValue)
                },
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = textValue,
                onValueChange = { newText ->
                    textValue = newText
                    isEditing = true
                    if (newText.toFloatOrNull() != null) {
                        commitCurrentText(normalizeText = false)
                    }
                },
                modifier = Modifier
                    .widthIn(min = 88.dp, max = 128.dp)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && isEditing) {
                            commitCurrentText(normalizeText = true)
                            isEditing = false
                        }
                    },
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                suffix = if (suffix.isNotEmpty()) { { Text(suffix) } } else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isInteger) KeyboardType.Number else KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitCurrentText(normalizeText = true)
                        isEditing = false
                        focusManager.clearFocus()
                    }
                )
            )
        }
    }
}

/**
 * Convenience wrapper for integer sliders
 */
@Composable
fun IntSliderWithInput(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    modifier: Modifier = Modifier,
    label: String? = null,
    steps: Int = 0,
    enabled: Boolean = true,
    suffix: String = ""
) {
    SliderWithInput(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
        modifier = modifier,
        label = label,
        steps = steps,
        enabled = enabled,
        isInteger = true,
        suffix = suffix
    )
}

@Composable
fun IntInputField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    suffix: String = "",
    minValue: Int = 1
) {
    val focusManager = LocalFocusManager.current
    var textValue by remember(value) { mutableStateOf(value.toString()) }
    var isEditing by remember { mutableStateOf(false) }

    fun commitCurrentText(normalizeText: Boolean) {
        val parsed = textValue.toIntOrNull() ?: return
        val normalized = parsed.coerceAtLeast(minValue)
        onValueChange(normalized)
        if (normalizeText) {
            textValue = normalized.toString()
        }
    }

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = textValue,
            onValueChange = { newText ->
                textValue = newText
                isEditing = true
                if (newText.toIntOrNull() != null) {
                    commitCurrentText(normalizeText = false)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused && isEditing) {
                        commitCurrentText(normalizeText = true)
                        isEditing = false
                    }
                },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            suffix = if (suffix.isNotEmpty()) { { Text(suffix) } } else null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    commitCurrentText(normalizeText = true)
                    isEditing = false
                    focusManager.clearFocus()
                }
            )
        )
    }
}
