package dev.spcdts.volumemapper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.spcdts.volumemapper.R
import dev.spcdts.volumemapper.core.FixedVolumePresets
import dev.spcdts.volumemapper.core.RouteVolumeRange
import dev.spcdts.volumemapper.core.RouteVolumeSnapshot
import kotlin.math.roundToInt

@Composable
internal fun FixedVolumeCard(
    presets: FixedVolumePresets,
    snapshot: RouteVolumeSnapshot?,
    isVolumeFixed: Boolean,
    isMediaContextSafe: Boolean,
    settingsLoaded: Boolean,
    onApply: (Int) -> Unit,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = snapshot?.range
    val canApply = settingsLoaded &&
        range != null &&
        range.minIndex < range.maxIndex &&
        !isVolumeFixed &&
        isMediaContextSafe
    val canOpenEditor = settingsLoaded && range != null
    val shape = RoundedCornerShape(20.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(VolumeMapperTestTags.PRESET_SECTION),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.fixed_volume),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    modifier = Modifier
                        .weight(1f)
                        .testTag(VolumeMapperTestTags.PRESET_LIST),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 9.dp),
                ) {
                    items(presets.indices, key = { index -> index }) { index ->
                        FixedVolumeButton(
                            index = index,
                            range = range,
                            currentIndex = snapshot?.currentIndex,
                            enabled = canApply && range.contains(index),
                            onClick = { onApply(index) },
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedIconButton(
                    onClick = onOpenEditor,
                    enabled = canOpenEditor,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag(VolumeMapperTestTags.PRESET_ADD),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.fixed_volume_add),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FixedVolumeButton(
    index: Int,
    range: RouteVolumeRange?,
    currentIndex: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val selected = index == currentIndex
    val shape = RoundedCornerShape(14.dp)
    val primary = MaterialTheme.colorScheme.primary
    val stateCurrent = stringResource(R.string.fixed_volume_current)
    val setDescription = stringResource(R.string.fixed_volume_set_to, index)
    val unavailableState = if (range != null && !range.contains(index)) {
        stringResource(
            R.string.fixed_volume_out_of_range,
            index,
            range.minIndex,
            range.maxIndex,
        )
    } else {
        null
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(44.dp)
            .widthIn(min = 54.dp)
            .fixedVolumeGlow(selected = selected, color = primary, shape = shape)
            .testTag(VolumeMapperTestTags.presetButton(index))
            .semantics {
                contentDescription = setDescription
                when {
                    unavailableState != null -> stateDescription = unavailableState
                    selected -> stateDescription = stateCurrent
                }
            },
        shape = shape,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        contentPadding = PaddingValues(horizontal = 9.dp),
    ) {
        Icon(
            imageVector = fixedVolumeIcon(index, range),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(text = index.toString(), fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FixedVolumeEditorSheet(
    presets: FixedVolumePresets,
    snapshot: RouteVolumeSnapshot,
    settingsLoaded: Boolean,
    onDismiss: () -> Unit,
    onAddPreset: (Int) -> Unit,
    onRemovePreset: (Int) -> Unit,
) {
    val range = snapshot.range
    val canEdit = settingsLoaded && range.minIndex < range.maxIndex
    var workingPresets by remember { mutableStateOf(presets) }
    LaunchedEffect(presets) {
        workingPresets = presets
    }
    val suggested = remember(range, snapshot.currentIndex, workingPresets) {
        nextAvailableFixedVolume(
            start = snapshot.currentIndex,
            range = range,
            occupied = workingPresets.indices.toSet(),
        ) ?: range.minIndex
    }
    var candidate by rememberSaveable(range.minIndex, range.maxIndex) {
        mutableIntStateOf(suggested)
    }
    LaunchedEffect(range.minIndex, range.maxIndex) {
        candidate = candidate.coerceIn(range.minIndex, range.maxIndex)
    }
    val alreadyAdded = candidate in workingPresets.indices
    val canAdd = canEdit &&
        !alreadyAdded &&
        workingPresets.indices.count(range::contains) < range.indexCount
    val alreadyAddedDescription = stringResource(R.string.fixed_volume_already_added, candidate)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(VolumeMapperTestTags.PRESET_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.fixed_volume),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(workingPresets.indices, key = { index -> index }) { index ->
                    val removeDescription = stringResource(
                        R.string.fixed_volume_remove,
                        index,
                    )
                    AssistChip(
                        onClick = {
                            workingPresets = FixedVolumePresets(
                                workingPresets.indices - index,
                            )
                            onRemovePreset(index)
                        },
                        label = { Text(index.toString()) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        modifier = Modifier
                            .testTag(VolumeMapperTestTags.presetDelete(index))
                            .semantics {
                                contentDescription = removeDescription
                            },
                    )
                }
            }

            val indexDescription = stringResource(R.string.fixed_volume_index)
            Text(
                text = stringResource(
                    R.string.fixed_volume_value_of_max,
                    candidate,
                    range.maxIndex,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(VolumeMapperTestTags.PRESET_VALUE)
                    .semantics {
                        contentDescription = indexDescription
                        stateDescription = candidate.toString()
                    },
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { candidate -= 1 },
                    enabled = canEdit && candidate > range.minIndex,
                    modifier = Modifier.testTag(VolumeMapperTestTags.PRESET_DECREMENT),
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = stringResource(R.string.fixed_volume_decrease),
                    )
                }
                Slider(
                    value = candidate.toFloat(),
                    onValueChange = { value -> candidate = value.roundToInt() },
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = indexDescription
                            stateDescription = candidate.toString()
                        },
                    enabled = canEdit,
                    valueRange = range.minIndex.toFloat()..range.maxIndex.toFloat(),
                    steps = (range.indexCount - 2).coerceAtLeast(0),
                )
                IconButton(
                    onClick = { candidate += 1 },
                    enabled = canEdit && candidate < range.maxIndex,
                    modifier = Modifier.testTag(VolumeMapperTestTags.PRESET_INCREMENT),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.fixed_volume_increase),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(VolumeMapperTestTags.PRESET_DONE),
                ) {
                    Text(stringResource(R.string.action_done))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val updated = FixedVolumePresets(
                            workingPresets.indices + candidate,
                        )
                        workingPresets = updated
                        onAddPreset(candidate)
                        candidate = nextAvailableFixedVolume(
                            start = candidate + 1,
                            range = range,
                            occupied = updated.indices.toSet(),
                        ) ?: candidate
                    },
                    enabled = canAdd,
                    modifier = Modifier
                        .testTag(VolumeMapperTestTags.PRESET_CONFIRM)
                        .semantics {
                            if (alreadyAdded) stateDescription = alreadyAddedDescription
                        },
                ) {
                    Text(stringResource(R.string.action_add))
                }
            }
        }
    }
}

private fun Modifier.fixedVolumeGlow(
    selected: Boolean,
    color: Color,
    shape: RoundedCornerShape,
): Modifier = if (selected) {
    graphicsLayer {
        shadowElevation = 9.dp.toPx()
        this.shape = shape
        clip = false
        ambientShadowColor = color
        spotShadowColor = color
    }
} else {
    this
}

private fun fixedVolumeIcon(index: Int, range: RouteVolumeRange?): ImageVector {
    if (index == 0) return Icons.AutoMirrored.Filled.VolumeOff
    val normalized = range?.let {
        if (it.minIndex == it.maxIndex) 1f else {
            (index - it.minIndex).toFloat() / (it.maxIndex - it.minIndex).toFloat()
        }
    } ?: 1f
    return if (normalized <= 0.5f) {
        Icons.AutoMirrored.Filled.VolumeDown
    } else {
        Icons.AutoMirrored.Filled.VolumeUp
    }
}

private fun nextAvailableFixedVolume(
    start: Int,
    range: RouteVolumeRange,
    occupied: Set<Int>,
): Int? {
    val clampedStart = start.coerceIn(range.minIndex, range.maxIndex)
    return (clampedStart..range.maxIndex).firstOrNull { index -> index !in occupied }
        ?: (range.minIndex until clampedStart).firstOrNull { index -> index !in occupied }
}
