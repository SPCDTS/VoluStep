package dev.spcdts.volumemapper.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spcdts.volumemapper.AppGraph
import dev.spcdts.volumemapper.BuildConfig
import dev.spcdts.volumemapper.R
import dev.spcdts.volumemapper.core.AudioRouteType
import dev.spcdts.volumemapper.core.KeyMappingConfig
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import dev.spcdts.volumemapper.runtime.ControllerRuntimeState
import dev.spcdts.volumemapper.runtime.FixedVolumeRequestState
import dev.spcdts.volumemapper.runtime.MappingControllerService
import dev.spcdts.volumemapper.runtime.resolveLocalizedText

@Composable
fun VolumeMapperApp(graph: AppGraph) {
    val runtime by graph.mappingCoordinator.runtime.collectAsState()
    val fixedVolumeRequestState by graph.mappingCoordinator.fixedVolumeRequestState.collectAsState()
    val settingsState by graph.settingsRepository.state.collectAsState()
    val settings = settingsState.settings
    val settingsLoaded = settingsState.initialSettingsLoaded
    var showDisclosure by remember { mutableStateOf(false) }
    var showPrivacyPolicy by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showAppSettingsUnavailable by remember { mutableStateOf(false) }
    var controllerStartError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(fixedVolumeRequestState) {
        val rejected = fixedVolumeRequestState as? FixedVolumeRequestState.Rejected
            ?: return@LaunchedEffect
        try {
            snackbarHostState.showSnackbar(context.resolveLocalizedText(rejected.message))
        } finally {
            graph.mappingCoordinator.acknowledgeFixedVolumeRequest(rejected.requestId)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        MainScreen(
            runtime = runtime,
            settings = settings,
            settingsLoaded = settingsLoaded,
            graph = graph,
            onOpenAccessibility = {
                if (!settingsLoaded) {
                    Unit
                } else if (settings.disclosureAccepted) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    showDisclosure = true
                }
            },
            onStart = {
                if (!settingsLoaded) {
                    Unit
                } else if (!settings.disclosureAccepted) {
                    showDisclosure = true
                } else if (!runtime.isAccessibilityConnected) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    runCatching { MappingControllerService.start(context) }
                        .onFailure {
                            controllerStartError = it.message ?: it.javaClass.simpleName
                        }
                }
            },
            onStop = { MappingControllerService.stop(context) },
            onShowPrivacyPolicy = { showPrivacyPolicy = true },
            onShowAbout = { showAbout = true },
            onOpenAppSettings = {
                if (!AppDetailsSettingsLauncher.open(context)) {
                    showAppSettingsUnavailable = true
                }
            },
            modifier = Modifier.padding(innerPadding),
        )
    }

    if (showDisclosure) {
        DisclosureDialog(
            onDismiss = { showDisclosure = false },
            onAccept = {
                if (settingsLoaded) {
                    graph.settingsRepository.acceptDisclosure()
                    graph.settingsRepository.flushPendingWrite()
                }
                showDisclosure = false
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            enabled = settingsLoaded,
        )
    }

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    if (showAppSettingsUnavailable) {
        val appName = stringResource(R.string.app_name)
        AlertDialog(
            onDismissRequest = { showAppSettingsUnavailable = false },
            title = { Text(stringResource(R.string.error_open_settings_title)) },
            text = {
                Text(stringResource(R.string.error_open_settings_message, appName))
            },
            confirmButton = {
                TextButton(onClick = { showAppSettingsUnavailable = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }

    controllerStartError?.let { error ->
        AlertDialog(
            onDismissRequest = { controllerStartError = null },
            title = { Text(stringResource(R.string.error_controller_start_title)) },
            text = {
                Text(stringResource(R.string.error_controller_start_message, error))
            },
            confirmButton = {
                TextButton(onClick = { controllerStartError = null }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}

@Composable
private fun MainScreen(
    runtime: ControllerRuntimeState,
    settings: VolumeMapperSettings,
    settingsLoaded: Boolean,
    graph: AppGraph,
    onOpenAccessibility: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShowPrivacyPolicy: () -> Unit,
    onShowAbout: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderedSurfaceColor = MaterialTheme.colorScheme.outlineVariant
    var showFixedVolumeEditor by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(runtime.snapshot == null) {
        if (showFixedVolumeEditor && runtime.snapshot == null) {
            showFixedVolumeEditor = false
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag(VolumeMapperTestTags.SCREEN_MAIN),
        contentPadding = PaddingValues(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            BrandControlBar(
                runtime = runtime,
                disclosureAccepted = settings.disclosureAccepted,
                settingsLoaded = settingsLoaded,
                onStart = onStart,
                onStop = onStop,
                onShowPrivacyPolicy = onShowPrivacyPolicy,
                onShowAbout = onShowAbout,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                border = BorderStroke(1.dp, borderedSurfaceColor),
            ) {
                if (settingsLoaded) {
                    MappingCurveEditor(
                        outputMap = settings.outputMap,
                        snapshot = runtime.snapshot,
                        currentLogicalPosition = runtime.logicalPosition,
                        onMapCommitted = { outputMap ->
                            if (settingsLoaded) {
                                graph.settingsRepository.updateOutputMap(outputMap)
                                graph.settingsRepository.flushPendingWrite()
                            }
                        },
                        modifier = Modifier.padding(
                            start = 4.dp,
                            top = 10.dp,
                            end = 4.dp,
                            bottom = 8.dp,
                        ),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            }
        }

        item {
            FixedVolumeCard(
                presets = settings.fixedVolumePresets,
                snapshot = runtime.snapshot,
                isVolumeFixed = runtime.isVolumeFixed,
                isMediaContextSafe = runtime.isMediaContextSafe,
                settingsLoaded = settingsLoaded,
                onApply = graph.mappingCoordinator::requestFixedVolume,
                onOpenEditor = { showFixedVolumeEditor = true },
            )
        }

        item {
            LongPressIntervalCard(
                intervalMillis = settings.keyConfig.holdStepIntervalMillis,
                enabled = settingsLoaded,
                onIntervalChanged = { intervalMillis ->
                    if (settingsLoaded) {
                        graph.settingsRepository.updateKeyConfig(
                            settings.keyConfig.copy(holdStepIntervalMillis = intervalMillis),
                        )
                        graph.settingsRepository.flushPendingWrite()
                    }
                },
            )
        }

        item {
            DeviceCard(
                runtime = runtime,
                onOpenAccessibility = onOpenAccessibility,
                onOpenAppSettings = onOpenAppSettings,
            )
        }
    }

    val fixedVolumeSnapshot = runtime.snapshot
    if (showFixedVolumeEditor && fixedVolumeSnapshot != null) {
        FixedVolumeEditorSheet(
            presets = settings.fixedVolumePresets,
            snapshot = fixedVolumeSnapshot,
            settingsLoaded = settingsLoaded,
            onDismiss = { showFixedVolumeEditor = false },
            onAddPreset = { index ->
                if (settingsLoaded) {
                    graph.settingsRepository.addFixedVolumePreset(index)
                    graph.settingsRepository.flushPendingWrite()
                }
            },
            onRemovePreset = { index ->
                if (settingsLoaded) {
                    graph.settingsRepository.removeFixedVolumePreset(index)
                    graph.settingsRepository.flushPendingWrite()
                }
            },
        )
    }
}

@Composable
private fun BrandControlBar(
    runtime: ControllerRuntimeState,
    disclosureAccepted: Boolean,
    settingsLoaded: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShowPrivacyPolicy: () -> Unit,
    onShowAbout: () -> Unit,
) {
    val appName = stringResource(R.string.app_name)
    val snapshot = runtime.snapshot
    val routeStatus = if (snapshot != null) {
        stringResource(
            R.string.brand_control_status,
            stringResource(routeMediaLabelResource(snapshot.route.type)),
            snapshot.range.minIndex,
            snapshot.range.maxIndex,
        )
    } else {
        null
    }
    val status = when {
        !settingsLoaded -> stringResource(R.string.status_loading)
        !disclosureAccepted -> stringResource(R.string.status_authorization_required)
        !runtime.isAccessibilityConnected -> if (runtime.isForegroundServiceRunning) {
            stringResource(R.string.status_waiting_accessibility)
        } else {
            stringResource(R.string.status_enable_accessibility)
        }
        runtime.isFailOpen -> stringResource(R.string.status_paused)
        runtime.canInterceptKeys -> routeStatus ?: stringResource(R.string.status_running)
        runtime.isForegroundServiceRunning -> stringResource(R.string.status_waiting_media)
        else -> stringResource(R.string.status_not_started)
    }
    val switchContentDescription = stringResource(
        R.string.master_switch_content_description,
        appName,
    )
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .testTag(VolumeMapperTestTags.BRAND_CONTROL_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = appName,
            modifier = Modifier.weight(1f),
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Switch(
            checked = runtime.isForegroundServiceRunning,
            onCheckedChange = { enabled -> if (enabled) onStart() else onStop() },
            enabled = settingsLoaded,
            modifier = Modifier
                .size(width = 50.dp, height = 30.dp)
                .testTag(VolumeMapperTestTags.MASTER_SWITCH)
                .semantics {
                    contentDescription = switchContentDescription
                    stateDescription = status
                },
        )
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.testTag(VolumeMapperTestTags.APP_MENU),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.app_menu),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.privacy_policy)) },
                    onClick = {
                        menuExpanded = false
                        onShowPrivacyPolicy()
                    },
                    modifier = Modifier.testTag(VolumeMapperTestTags.PRIVACY_MENU_ITEM),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.about)) },
                    onClick = {
                        menuExpanded = false
                        onShowAbout()
                    },
                    modifier = Modifier.testTag(VolumeMapperTestTags.ABOUT_MENU_ITEM),
                )
            }
        }
    }
}

@Composable
private fun LongPressIntervalCard(
    intervalMillis: Long,
    enabled: Boolean,
    onIntervalChanged: (Long) -> Unit,
) {
    val value = intervalMillis.coerceIn(MINIMUM_HOLD_INTERVAL, MAXIMUM_HOLD_INTERVAL)
    val valueAsInt = value.toInt()
    val intervalContentDescription = stringResource(R.string.hold_interval_content_description)
    val intervalStateDescription = pluralStringResource(
        R.plurals.milliseconds_long,
        valueAsInt,
        valueAsInt,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.hold_interval),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            IconButton(
                onClick = {
                    onIntervalChanged(
                        (value - HOLD_INTERVAL_STEP).coerceAtLeast(MINIMUM_HOLD_INTERVAL),
                    )
                },
                enabled = enabled && value > MINIMUM_HOLD_INTERVAL,
                modifier = Modifier
                    .size(40.dp)
                    .testTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_DECREMENT),
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.hold_interval_decrease),
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = stringResource(R.string.milliseconds_short, valueAsInt),
                modifier = Modifier
                    .width(52.dp)
                    .testTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_VALUE)
                    .semantics {
                        contentDescription = intervalContentDescription
                        stateDescription = intervalStateDescription
                    },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
            IconButton(
                onClick = {
                    onIntervalChanged(
                        (value + HOLD_INTERVAL_STEP).coerceAtMost(MAXIMUM_HOLD_INTERVAL),
                    )
                },
                enabled = enabled && value < MAXIMUM_HOLD_INTERVAL,
                modifier = Modifier
                    .size(40.dp)
                    .testTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_INCREMENT),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.hold_interval_increase),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    runtime: ControllerRuntimeState,
    onOpenAccessibility: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val manufacturer = manufacturerLabel()
    val summary = when {
        runtime.isFailOpen -> stringResource(R.string.status_needs_attention)
        runtime.canInterceptKeys -> stringResource(R.string.status_ready)
        runtime.isForegroundServiceRunning -> stringResource(R.string.status_waiting)
        else -> stringResource(R.string.status_not_started)
    }
    val snapshot = runtime.snapshot
    val deviceLabel = stringResource(R.string.device)
    val deviceInformation = stringResource(R.string.device_information)
    val deviceExpansionState = stringResource(
        if (expanded) R.string.state_expanded else R.string.state_collapsed,
    )
    val deviceSummary = stringResource(R.string.device_summary, manufacturer, summary)
    val phoneLabel = stringResource(R.string.device_phone)
    val outputLabel = stringResource(R.string.device_output)
    val volumeRangeLabel = stringResource(R.string.device_volume_range)
    val accessibilityLabel = stringResource(R.string.device_accessibility)
    val backgroundLabel = stringResource(R.string.device_background)
    val outputName = snapshot?.route?.productName ?: stringResource(R.string.device_not_detected)
    val volumeRange = snapshot?.let {
        "${it.range.minIndex}–${it.range.maxIndex}"
    } ?: stringResource(R.string.device_unknown)
    val accessibilityState = stringResource(
        if (runtime.isAccessibilityConnected) R.string.device_enabled else R.string.device_disabled,
    )
    val backgroundState = stringResource(
        if (runtime.isForegroundServiceRunning) R.string.status_running else R.string.status_not_started,
    )
    val openAccessibilitySettings = stringResource(R.string.open_accessibility_settings)
    val openAppSettings = stringResource(R.string.open_app_settings)
    val borderedSurfaceColor = MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(1.dp, borderedSurfaceColor),
    ) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag(VolumeMapperTestTags.DEVICE_TOGGLE)
                .semantics {
                    contentDescription = deviceInformation
                    stateDescription = deviceExpansionState
                },
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(
                    deviceLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    deviceSummary,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                    .testTag(VolumeMapperTestTags.DEVICE_DETAILS),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DeviceSettingRow(label = phoneLabel, value = manufacturer)
                DeviceSettingRow(
                    label = outputLabel,
                    value = outputName,
                )
                DeviceSettingRow(
                    label = volumeRangeLabel,
                    value = volumeRange,
                )
                DeviceSettingRow(
                    label = accessibilityLabel,
                    value = accessibilityState,
                    onClick = onOpenAccessibility,
                    onClickLabel = openAccessibilitySettings,
                    testTag = VolumeMapperTestTags.DEVICE_ACCESSIBILITY_ROW,
                )
                DeviceSettingRow(
                    label = backgroundLabel,
                    value = backgroundState,
                    onClick = onOpenAppSettings,
                    onClickLabel = openAppSettings,
                    testTag = VolumeMapperTestTags.DEVICE_BACKGROUND_ROW,
                )
            }
        }
    }
}

@Composable
private fun DeviceSettingRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    testTag: String? = null,
) {
    val valueColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .heightIn(min = if (onClick == null) 42.dp else 48.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = valueColor,
            fontSize = 13.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DisclosureDialog(
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
    enabled: Boolean,
) {
    var confirmed by remember { mutableStateOf(false) }
    val appName = stringResource(R.string.app_name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.accessibility_disclosure_purpose, appName))
                Text(stringResource(R.string.accessibility_disclosure_processed, appName))
                Text(stringResource(R.string.accessibility_disclosure_not_processed, appName))
                Text(stringResource(R.string.accessibility_disclosure_conflicts, appName))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = confirmed,
                            role = Role.Checkbox,
                            onValueChange = { confirmed = it },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = confirmed, onCheckedChange = null)
                    Text(stringResource(R.string.accessibility_disclosure_consent))
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept, enabled = enabled && confirmed) {
                Text(stringResource(R.string.action_agree))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    val appName = stringResource(R.string.app_name)
    val context = LocalContext.current
    val fullPolicyUrl = BuildConfig.PRIVACY_POLICY_URL
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(VolumeMapperTestTags.PRIVACY_DIALOG),
        title = { Text(stringResource(R.string.privacy_policy)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.privacy_on_device_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.privacy_on_device_body, appName))
                Text(stringResource(R.string.privacy_not_collected_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.privacy_not_collected_body, appName))
                Text(stringResource(R.string.privacy_retention_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.privacy_retention_body, appName))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = if (fullPolicyUrl.isNotBlank()) {
            {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(fullPolicyUrl)).apply {
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                },
                            )
                        }
                    },
                    modifier = Modifier.testTag(VolumeMapperTestTags.PRIVACY_FULL_POLICY),
                ) {
                    Text(stringResource(R.string.privacy_full_policy))
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(VolumeMapperTestTags.ABOUT_DIALOG),
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
                Text(stringResource(R.string.about_summary))
                Text(
                    text = stringResource(R.string.about_compatibility),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@Composable
private fun manufacturerLabel(): String {
    val manufacturer = Build.MANUFACTURER.trim()
    return when {
        manufacturer.equals("xiaomi", ignoreCase = true) ->
            stringResource(R.string.manufacturer_xiaomi)
        manufacturer.isBlank() -> stringResource(R.string.manufacturer_unknown)
        else -> manufacturer.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
    }
}

@StringRes
private fun routeMediaLabelResource(type: AudioRouteType): Int = when (type) {
    AudioRouteType.BLUETOOTH_A2DP,
    AudioRouteType.BLUETOOTH_LE,
    -> R.string.route_bluetooth
    AudioRouteType.BUILT_IN_SPEAKER -> R.string.route_phone_speaker
    AudioRouteType.WIRED_HEADSET -> R.string.route_wired
    AudioRouteType.USB -> R.string.route_usb
    AudioRouteType.HDMI -> R.string.route_hdmi
    AudioRouteType.REMOTE -> R.string.route_remote
    AudioRouteType.UNKNOWN -> R.string.route_unknown
}

private const val MINIMUM_HOLD_INTERVAL = KeyMappingConfig.MIN_HOLD_STEP_INTERVAL_MILLIS
private const val MAXIMUM_HOLD_INTERVAL = KeyMappingConfig.MAX_HOLD_STEP_INTERVAL_MILLIS
private const val HOLD_INTERVAL_STEP = KeyMappingConfig.HOLD_STEP_INTERVAL_GRID_MILLIS

object VolumeMapperTestTags {
    const val SCREEN_MAIN = "screen_main"
    const val BRAND_CONTROL_BAR = "brand_control_bar"
    const val MASTER_SWITCH = "master_switch"
    const val APP_MENU = "app_menu"
    const val PRIVACY_MENU_ITEM = "privacy_menu_item"
    const val ABOUT_MENU_ITEM = "about_menu_item"
    const val PRIVACY_DIALOG = "privacy_dialog"
    const val PRIVACY_FULL_POLICY = "privacy_full_policy"
    const val ABOUT_DIALOG = "about_dialog"
    const val LONG_PRESS_INTERVAL_VALUE = "long_press_interval_value"
    const val LONG_PRESS_INTERVAL_DECREMENT = "long_press_interval_decrement"
    const val LONG_PRESS_INTERVAL_INCREMENT = "long_press_interval_increment"
    const val PRESET_SECTION = "preset_section"
    const val PRESET_LIST = "preset_list"
    const val PRESET_ADD = "preset_add"
    const val PRESET_SHEET = "preset_sheet"
    const val PRESET_VALUE = "preset_value"
    const val PRESET_DECREMENT = "preset_decrement"
    const val PRESET_INCREMENT = "preset_increment"
    const val PRESET_CONFIRM = "preset_confirm"
    const val PRESET_DONE = "preset_done"
    const val DEVICE_TOGGLE = "device_toggle"
    const val DEVICE_DETAILS = "device_details"
    const val DEVICE_ACCESSIBILITY_ROW = "device_accessibility_row"
    const val DEVICE_BACKGROUND_ROW = "device_background_row"

    // Source-compatible names for test clients compiled against the former tabbed UI.
    const val NAV_CONTROL = "nav_control"
    const val NAV_CURVE = "nav_curve"
    const val NAV_DIAGNOSTICS = "nav_diagnostics"
    const val SCREEN_CONTROL = SCREEN_MAIN
    const val SCREEN_CURVE = SCREEN_MAIN
    const val SCREEN_DIAGNOSTICS = "screen_diagnostics"
    const val CURVE_SCREEN_LIST = SCREEN_MAIN
    const val KEY_BEHAVIOUR_CARD = "key_behaviour_card"
    const val KEY_BEHAVIOUR_TOGGLE = "key_behaviour_toggle"

    fun presetButton(index: Int): String = "preset_button_$index"

    fun presetDelete(index: Int): String = "preset_delete_$index"
}
