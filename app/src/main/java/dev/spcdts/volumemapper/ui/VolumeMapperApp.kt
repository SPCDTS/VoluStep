package dev.spcdts.volumemapper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import dev.spcdts.volumemapper.core.AudioRouteType
import dev.spcdts.volumemapper.core.KeyMappingConfig
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import dev.spcdts.volumemapper.runtime.ControllerRuntimeState
import dev.spcdts.volumemapper.runtime.MappingControllerService

@Composable
fun VolumeMapperApp(graph: AppGraph) {
    val runtime by graph.mappingCoordinator.runtime.collectAsState()
    val settingsState by graph.settingsRepository.state.collectAsState()
    val settings = settingsState.settings
    val settingsLoaded = settingsState.initialSettingsLoaded
    var showDisclosure by remember { mutableStateOf(false) }
    var showAppSettingsUnavailable by remember { mutableStateOf(false) }
    var controllerStartError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Scaffold { innerPadding ->
        MainScreen(
            runtime = runtime,
            settings = settings,
            settingsLoaded = settingsLoaded,
            graph = graph,
            onShowDisclosure = { showDisclosure = true },
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
            onRetry = graph.mappingCoordinator::retry,
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
            },
            enabled = settingsLoaded,
        )
    }

    if (showAppSettingsUnavailable) {
        AlertDialog(
            onDismissRequest = { showAppSettingsUnavailable = false },
            title = { Text("无法打开系统设置") },
            text = { Text("请手动进入系统设置并找到本应用。") },
            confirmButton = {
                TextButton(onClick = { showAppSettingsUnavailable = false }) { Text("知道了") }
            },
        )
    }

    controllerStartError?.let { error ->
        AlertDialog(
            onDismissRequest = { controllerStartError = null },
            title = { Text("控制器启动失败") },
            text = { Text("系统未能启动前台控制器：$error") },
            confirmButton = {
                TextButton(onClick = { controllerStartError = null }) { Text("知道了") }
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
    onShowDisclosure: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderedSurfaceColor = if (isSystemInDarkTheme()) {
        Color(0xFF3C3941)
    } else {
        Color(0xFFE1DEE6)
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
            Text(
                text = "音量映射",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp, top = 11.dp, bottom = 8.dp),
            )
        }

        item {
            ControllerCard(
                runtime = runtime,
                disclosureAccepted = settings.disclosureAccepted,
                settingsLoaded = settingsLoaded,
                onShowDisclosure = onShowDisclosure,
                onOpenAccessibility = onOpenAccessibility,
                onStart = onStart,
                onStop = onStop,
                onRetry = onRetry,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
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
                        text = "正在加载…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            }
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
}

@Composable
private fun ControllerCard(
    runtime: ControllerRuntimeState,
    disclosureAccepted: Boolean,
    settingsLoaded: Boolean,
    onShowDisclosure: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    val routeStatus = runtime.snapshot?.let { snapshot ->
        "${routeMediaLabel(snapshot.route.type)} · " +
            "${snapshot.range.minIndex}–${snapshot.range.maxIndex}"
    }
    val status = when {
        !settingsLoaded -> "正在加载"
        !disclosureAccepted -> "需要授权"
        !runtime.isAccessibilityConnected -> if (runtime.isForegroundServiceRunning) {
            "等待无障碍"
        } else {
            "开启无障碍"
        }
        runtime.isFailOpen -> "已暂停"
        runtime.canInterceptKeys -> routeStatus ?: "运行中"
        runtime.isForegroundServiceRunning -> "等待媒体"
        else -> "未启动"
    }
    val statusAction = when {
        !settingsLoaded -> null
        !disclosureAccepted -> onShowDisclosure
        !runtime.isAccessibilityConnected -> onOpenAccessibility
        runtime.isFailOpen -> onRetry
        else -> null
    }
    val statusActionLabel = when {
        !settingsLoaded -> null
        !disclosureAccepted -> "查看授权说明"
        !runtime.isAccessibilityConnected -> "打开无障碍设置"
        runtime.isFailOpen -> "重试精细控制"
        else -> null
    }
    val darkTheme = isSystemInDarkTheme()
    val activeDotColor = if (darkTheme) Color(0xFF88CE9E) else Color(0xFF2D6D45)
    val activeStatusColor = if (darkTheme) Color(0xFFA9D9B8) else Color(0xFF315C42)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag(VolumeMapperTestTags.CONTROLLER_STATUS_ACTION)
                    .then(
                        if (statusAction != null && statusActionLabel != null) {
                            Modifier.clickable(
                                onClickLabel = statusActionLabel,
                                onClick = statusAction,
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Text("精细控制", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (runtime.canInterceptKeys) {
                                    activeDotColor
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                },
                            ),
                    )
                    Text(
                        status,
                        color = if (runtime.canInterceptKeys) {
                            activeStatusColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 13.sp,
                    )
                }
            }
            Switch(
                checked = runtime.isForegroundServiceRunning,
                onCheckedChange = { enabled -> if (enabled) onStart() else onStop() },
                enabled = settingsLoaded,
                modifier = Modifier
                    .size(width = 50.dp, height = 30.dp)
                    .testTag(VolumeMapperTestTags.MASTER_SWITCH)
                    .semantics {
                        contentDescription = "启用精细音量控制"
                        stateDescription = status
                    },
            )
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
                text = "长按间隔",
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
                Icon(Icons.Default.Remove, contentDescription = "长按间隔减少", modifier = Modifier.size(18.dp))
            }
            Text(
                text = "$value ms",
                modifier = Modifier
                    .width(52.dp)
                    .testTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_VALUE)
                    .semantics {
                        contentDescription = "长按连续步进间隔"
                        stateDescription = "$value 毫秒"
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
                Icon(Icons.Default.Add, contentDescription = "长按间隔增加", modifier = Modifier.size(18.dp))
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
    var copied by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val manufacturer = manufacturerLabel()
    val summary = when {
        runtime.isFailOpen -> "需处理"
        runtime.canInterceptKeys -> "正常"
        runtime.isForegroundServiceRunning -> "等待"
        else -> "未启动"
    }
    val snapshot = runtime.snapshot
    val borderedSurfaceColor = if (isSystemInDarkTheme()) {
        Color(0xFF3C3941)
    } else {
        Color(0xFFE1DEE6)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
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
                    contentDescription = "设备信息"
                    stateDescription = if (expanded) "已展开" else "已折叠"
                },
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(
                    "设备",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "$manufacturer · $summary",
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
                DeviceSettingRow(label = "手机", value = manufacturer)
                DeviceSettingRow(
                    label = "耳机名称",
                    value = snapshot?.route?.productName ?: "未检测到",
                )
                DeviceSettingRow(
                    label = "音量范围",
                    value = snapshot?.let { "${it.range.minIndex}–${it.range.maxIndex}" } ?: "未知",
                )
                DeviceSettingRow(
                    label = "无障碍",
                    value = if (runtime.isAccessibilityConnected) "开启" else "关闭",
                    onClick = onOpenAccessibility,
                    testTag = VolumeMapperTestTags.DEVICE_ACCESSIBILITY_ROW,
                )
                DeviceSettingRow(
                    label = "后台",
                    value = if (runtime.isForegroundServiceRunning) "运行中" else "未启动",
                    onClick = onOpenAppSettings,
                    testTag = VolumeMapperTestTags.DEVICE_BACKGROUND_ROW,
                )
                TextButton(
                    onClick = {
                        val statusText = buildString {
                            appendLine("手机：$manufacturer")
                            appendLine("耳机名称：${snapshot?.route?.productName ?: "未检测到"}")
                            appendLine(
                                "音量范围：${snapshot?.let { "${it.range.minIndex}–${it.range.maxIndex}" } ?: "未知"}",
                            )
                            appendLine("无障碍：${if (runtime.isAccessibilityConnected) "开启" else "关闭"}")
                            appendLine("后台：${if (runtime.isForegroundServiceRunning) "运行中" else "未启动"}")
                            append("状态：${runtime.statusMessage}")
                        }
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("音量映射状态", statusText))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text(if (copied) "已复制" else "复制状态") }
            }
        }
    }
}

@Composable
private fun DeviceSettingRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val valueColor = if (isSystemInDarkTheme()) Color(0xFFB8C7E5) else Color(0xFF515E77)
    val rowActionLabel = when (label) {
        "无障碍" -> "打开无障碍设置"
        "后台" -> "打开应用后台设置"
        else -> "打开设置"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .heightIn(min = if (onClick == null) 42.dp else 48.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = rowActionLabel, onClick = onClick)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("无障碍 API 显著披露") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("为了在其他应用、桌面和锁屏场景接收手机实体音量键，本应用需要启用无障碍服务。")
                Text("会处理：音量上/下键的键码与时间，并使用 AudioManager 修改全局媒体音量。")
                Text("不会处理：屏幕文字、窗口内容、触摸、密码、账号信息；本应用不上传任何按键或音量数据。")
                Text("消费音量键可能干扰截图、无障碍快捷方式和部分厂商快捷键；停止控制器即可恢复系统默认行为。")
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
                    Text("我理解上述用途与按键冲突，并同意继续")
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept, enabled = enabled && confirmed) { Text("同意") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun manufacturerLabel(): String {
    val manufacturer = Build.MANUFACTURER.trim()
    return when {
        manufacturer.equals("xiaomi", ignoreCase = true) -> "小米"
        manufacturer.isBlank() -> "未知厂商"
        else -> manufacturer.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
    }
}

private fun routeMediaLabel(type: AudioRouteType): String = when (type) {
    AudioRouteType.BLUETOOTH_A2DP,
    AudioRouteType.BLUETOOTH_LE,
    -> "蓝牙媒体"
    AudioRouteType.BUILT_IN_SPEAKER -> "手机媒体"
    AudioRouteType.WIRED_HEADSET -> "有线媒体"
    AudioRouteType.USB -> "USB 媒体"
    AudioRouteType.HDMI -> "HDMI 媒体"
    AudioRouteType.REMOTE -> "远程媒体"
    AudioRouteType.UNKNOWN -> "媒体"
}

private const val MINIMUM_HOLD_INTERVAL = KeyMappingConfig.MIN_HOLD_STEP_INTERVAL_MILLIS
private const val MAXIMUM_HOLD_INTERVAL = KeyMappingConfig.MAX_HOLD_STEP_INTERVAL_MILLIS
private const val HOLD_INTERVAL_STEP = KeyMappingConfig.HOLD_STEP_INTERVAL_GRID_MILLIS

object VolumeMapperTestTags {
    const val SCREEN_MAIN = "screen_main"
    const val MASTER_SWITCH = "master_switch"
    const val CONTROLLER_STATUS_ACTION = "controller_status_action"
    const val LONG_PRESS_INTERVAL_VALUE = "long_press_interval_value"
    const val LONG_PRESS_INTERVAL_DECREMENT = "long_press_interval_decrement"
    const val LONG_PRESS_INTERVAL_INCREMENT = "long_press_interval_increment"
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
    const val PRESET_SECTION = "preset_section"
    const val KEY_BEHAVIOUR_CARD = "key_behaviour_card"
    const val KEY_BEHAVIOUR_TOGGLE = "key_behaviour_toggle"
}
