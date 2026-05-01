package com.example.llamadroid.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun DraftIntTextField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    valueRange: IntRange? = null,
    blankValue: Int? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    imeAction: ImeAction = ImeAction.Done,
    liveUpdate: Boolean = true
) {
    DraftNumberTextField(
        externalText = value.toString(),
        onLiveText = { text ->
            if (text.isBlank()) {
                if (liveUpdate && blankValue != null) {
                    onValueChange(blankValue)
                }
                return@DraftNumberTextField
            }
            val parsed = text.toIntOrNull() ?: return@DraftNumberTextField
            if (liveUpdate && valueRange?.contains(parsed) != false) {
                onValueChange(parsed)
            }
        },
        onCommitText = { text ->
            val committed = text.toIntOrNull()
                ?.let { parsed ->
                    valueRange?.let { parsed.coerceIn(it.first, it.last) } ?: parsed
                }
                ?: blankValue?.takeIf { text.isBlank() }
                ?: value
            onValueChange(committed)
            committed.toString()
        },
        sanitize = { it.filter(Char::isDigit) },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        suffix = suffix,
        keyboardType = KeyboardType.Number,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = textStyle,
        shape = shape,
        colors = colors,
        imeAction = imeAction
    )
}

@Composable
fun DraftNullableIntTextField(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    valueRange: IntRange? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    imeAction: ImeAction = ImeAction.Done,
    liveUpdate: Boolean = true
) {
    DraftNumberTextField(
        externalText = value?.toString().orEmpty(),
        onLiveText = { text ->
            if (!liveUpdate) return@DraftNumberTextField
            if (text.isBlank()) {
                onValueChange(null)
                return@DraftNumberTextField
            }
            val parsed = text.toIntOrNull() ?: return@DraftNumberTextField
            if (valueRange?.contains(parsed) != false) {
                onValueChange(parsed)
            }
        },
        onCommitText = { text ->
            val parsed = text.toIntOrNull()
            val committed = when {
                text.isBlank() -> null
                parsed != null -> valueRange?.let { parsed.coerceIn(it.first, it.last) } ?: parsed
                else -> value
            }
            onValueChange(committed)
            committed?.toString().orEmpty()
        },
        sanitize = { it.filter(Char::isDigit) },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        suffix = suffix,
        keyboardType = KeyboardType.Number,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = textStyle,
        shape = shape,
        colors = colors,
        imeAction = imeAction
    )
}

@Composable
fun DraftLongTextField(
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    valueRange: LongRange? = null,
    blankValue: Long? = null,
    allowNegative: Boolean = true,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    imeAction: ImeAction = ImeAction.Done,
    liveUpdate: Boolean = true
) {
    fun displayText(number: Long): String {
        return if (blankValue != null && number == blankValue) "" else number.toString()
    }

    DraftNumberTextField(
        externalText = displayText(value),
        onLiveText = { text ->
            if (text.isBlank()) {
                if (liveUpdate && blankValue != null) {
                    onValueChange(blankValue)
                }
                return@DraftNumberTextField
            }
            val parsed = text.toLongOrNull() ?: return@DraftNumberTextField
            if (liveUpdate && valueRange?.contains(parsed) != false) {
                onValueChange(parsed)
            }
        },
        onCommitText = { text ->
            val committed = text.toLongOrNull()
                ?.let { parsed ->
                    valueRange?.let { parsed.coerceIn(it.first, it.last) } ?: parsed
                }
                ?: blankValue?.takeIf { text.isBlank() }
                ?: value
            onValueChange(committed)
            displayText(committed)
        },
        sanitize = { sanitizeLongText(it, allowNegative) },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        suffix = suffix,
        keyboardType = KeyboardType.Number,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = textStyle,
        shape = shape,
        colors = colors,
        imeAction = imeAction
    )
}

@Composable
fun DraftFloatTextField(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float>? = null,
    valueToText: (Float) -> String = { it.toString() },
    allowNegative: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    imeAction: ImeAction = ImeAction.Done,
    liveUpdate: Boolean = true
) {
    DraftNumberTextField(
        externalText = valueToText(value),
        onLiveText = { text ->
            val parsed = text.toFloatOrNull() ?: return@DraftNumberTextField
            if (liveUpdate && valueRange?.contains(parsed) != false) {
                onValueChange(parsed)
            }
        },
        onCommitText = { text ->
            val committed = text.toFloatOrNull()
                ?.let { parsed ->
                    valueRange?.let { parsed.coerceIn(it.start, it.endInclusive) } ?: parsed
                }
                ?: value
            onValueChange(committed)
            valueToText(committed)
        },
        sanitize = { sanitizeDecimalText(it, allowNegative) },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        suffix = suffix,
        keyboardType = KeyboardType.Decimal,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = textStyle,
        shape = shape,
        colors = colors,
        imeAction = imeAction
    )
}

@Composable
private fun DraftNumberTextField(
    externalText: String,
    onLiveText: (String) -> Unit,
    onCommitText: (String) -> String,
    sanitize: (String) -> String,
    modifier: Modifier,
    label: @Composable (() -> Unit)?,
    placeholder: @Composable (() -> Unit)?,
    supportingText: @Composable (() -> Unit)?,
    suffix: @Composable (() -> Unit)?,
    keyboardType: KeyboardType,
    enabled: Boolean,
    singleLine: Boolean,
    textStyle: TextStyle,
    shape: Shape,
    colors: TextFieldColors,
    imeAction: ImeAction
) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(externalText) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(externalText, focused) {
        if (!focused && text != externalText) {
            text = externalText
        }
    }

    fun commitDraft() {
        text = onCommitText(text)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = sanitize(raw)
            text = filtered
            onLiveText(filtered)
        },
        modifier = modifier.onFocusChanged { focusState ->
            if (focused && !focusState.isFocused) {
                commitDraft()
            }
            focused = focusState.isFocused
        },
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        suffix = suffix,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = textStyle,
        shape = shape,
        colors = colors,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                commitDraft()
                focusManager.clearFocus()
            }
        )
    )
}

private fun sanitizeLongText(raw: String, allowNegative: Boolean): String {
    return buildString {
        raw.forEach { char ->
            when {
                char.isDigit() -> append(char)
                allowNegative && char == '-' && isEmpty() -> append(char)
            }
        }
    }
}

private fun sanitizeDecimalText(raw: String, allowNegative: Boolean): String {
    return buildString {
        var hasDecimal = false
        raw.forEach { char ->
            when {
                char.isDigit() -> append(char)
                char == '.' && !hasDecimal -> {
                    append(char)
                    hasDecimal = true
                }
                allowNegative && char == '-' && isEmpty() -> append(char)
            }
        }
    }
}
