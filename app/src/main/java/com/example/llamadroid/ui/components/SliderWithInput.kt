package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    
    Column(modifier = modifier) {
        if (label != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium
                )
                
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { newText ->
                        textValue = newText
                        isEditing = true
                    },
                    modifier = Modifier.width(100.dp),
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
                            val parsed = textValue.toFloatOrNull()
                            if (parsed != null) {
                                val clamped = parsed.coerceIn(valueRange.start, valueRange.endInclusive)
                                onValueChange(clamped)
                                textValue = if (isInteger) clamped.roundToInt().toString()
                                    else String.format("%.${decimalPlaces}f", clamped)
                            }
                            isEditing = false
                            focusManager.clearFocus()
                        }
                    )
                )
            }
        }
        
        Slider(
            value = value,
            onValueChange = { newValue ->
                onValueChange(newValue)
                if (!isEditing) {
                    textValue = if (isInteger) newValue.roundToInt().toString()
                        else String.format("%.${decimalPlaces}f", newValue)
                }
            },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
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
