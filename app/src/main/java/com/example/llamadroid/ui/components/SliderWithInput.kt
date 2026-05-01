package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    fun normalizedText(parsedValue: Float): String {
        return if (isInteger) {
            parsedValue.roundToInt().toString()
        } else {
            String.format("%.${decimalPlaces}f", parsedValue)
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
                    onValueChange(newValue)
                },
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )

            if (isInteger) {
                DraftIntTextField(
                    value = value.roundToInt(),
                    onValueChange = { onValueChange(it.toFloat()) },
                    valueRange = valueRange.start.roundToInt()..valueRange.endInclusive.roundToInt(),
                    modifier = Modifier.widthIn(min = 88.dp, max = 128.dp),
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    suffix = if (suffix.isNotEmpty()) { { Text(suffix) } } else null
                )
            } else {
                DraftFloatTextField(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    valueToText = ::normalizedText,
                    modifier = Modifier.widthIn(min = 88.dp, max = 128.dp),
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    suffix = if (suffix.isNotEmpty()) { { Text(suffix) } } else null
                )
            }
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
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        DraftIntTextField(
            value = value,
            onValueChange = onValueChange,
            valueRange = minValue..Int.MAX_VALUE,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium,
            suffix = if (suffix.isNotEmpty()) { { Text(suffix) } } else null
        )
    }
}
