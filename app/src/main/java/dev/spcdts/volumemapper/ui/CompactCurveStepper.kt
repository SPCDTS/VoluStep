package dev.spcdts.volumemapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.spcdts.volumemapper.R

/** 紧凑整数输入控件，统一管理草稿、焦点与边界提交。 */
@Composable
internal fun CompactCurveStepper(
    label: String,
    value: Int,
    minimum: Int,
    maximum: Int,
    enabled: Boolean,
    valueEditable: Boolean = true,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
    onValueChange: (Int) -> Unit,
    valueTag: String,
    decrementTag: String,
    incrementTag: String,
) {
    var draftValue by remember { mutableStateOf(value.toString()) }
    var inputHasFocus by remember { mutableStateOf(false) }
    var draftEdited by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = MaterialTheme.colorScheme.onSurface
    val emptyValueDescription = stringResource(R.string.curve_empty_value)
    val decreaseDescription = if (valueEditable) {
        stringResource(R.string.curve_decrease_value, label)
    } else {
        stringResource(R.string.curve_action_delete_point)
    }
    val increaseDescription = if (valueEditable) {
        stringResource(R.string.curve_increase_value, label)
    } else {
        stringResource(R.string.curve_action_insert_point)
    }

    LaunchedEffect(value, inputHasFocus, draftEdited) {
        if (!inputHasFocus || !draftEdited) {
            draftValue = value.toString()
            draftEdited = false
        }
    }

    fun commitDraft() {
        if (!valueEditable) {
            draftValue = value.toString()
            draftEdited = false
            return
        }
        if (!draftEdited) {
            draftValue = value.toString()
            return
        }
        val requested = draftValue.toIntOrNull()
        if (requested == null) {
            draftValue = value.toString()
            draftEdited = false
            return
        }
        val accepted = requested.coerceIn(minimum, maximum)
        draftValue = accepted.toString()
        draftEdited = false
        if (accepted != value) onValueChange(accepted)
    }

    fun updateFromButton(delta: Int) {
        val base = if (valueEditable) {
            draftValue.toIntOrNull()?.coerceIn(minimum, maximum) ?: value
        } else {
            value
        }
        val accepted = (base + delta).coerceIn(minimum, maximum)
        draftValue = accepted.toString()
        draftEdited = false
        if (accepted != value) onValueChange(accepted)
        inputHasFocus = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val buttonBase = if (valueEditable) {
        draftValue.toIntOrNull()?.coerceIn(minimum, maximum) ?: value
    } else {
        value
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        IconButton(
            onClick = { updateFromButton(-1) },
            enabled = enabled && canDecrement && buttonBase > minimum,
            modifier = Modifier
                .size(40.dp)
                .testTag(decrementTag),
        ) {
            Icon(
                Icons.Default.Remove,
                contentDescription = decreaseDescription,
                modifier = Modifier.size(18.dp),
            )
        }
        if (valueEditable) {
            BasicTextField(
                value = draftValue,
                onValueChange = { requested ->
                    if (requested.all(Char::isDigit) && requested.length <= 9) {
                        draftValue = requested
                        draftEdited = true
                    }
                },
                enabled = enabled,
                singleLine = true,
                modifier = Modifier
                    .width(52.dp)
                    .height(48.dp)
                    .testTag(valueTag)
                    .onFocusChanged { focusState ->
                        if (inputHasFocus && !focusState.isFocused) commitDraft()
                        inputHasFocus = focusState.isFocused
                    }
                    .semantics {
                        contentDescription = label
                        stateDescription = draftValue.ifBlank { emptyValueDescription }
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitDraft()
                        inputHasFocus = false
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    },
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        innerTextField()
                    }
                },
            )
        } else {
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(48.dp)
                    .testTag(valueTag)
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        stateDescription = value.toString()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = value.toString(),
                    color = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        IconButton(
            onClick = { updateFromButton(1) },
            enabled = enabled && canIncrement && buttonBase < maximum,
            modifier = Modifier
                .size(40.dp)
                .testTag(incrementTag),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = increaseDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
