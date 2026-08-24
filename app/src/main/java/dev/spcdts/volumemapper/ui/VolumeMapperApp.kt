package dev.spcdts.volumemapper.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.spcdts.volumemapper.AppGraph
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
    var showNotificationPermissionRequired by remember { mutableStateOf(false) }
    var showAppSettingsUnavailable by remember { mutableStateOf(false) }
    var controllerStartError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            runCatching { MappingControllerService.start(context) }
                .onFailure { controllerStartError = it.message ?: it.javaClass.simpleName }
        } else {
            showNotificationPermissionRequired = true
        }
    }

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
                } else if (
                    Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    runCatching { MappingControllerService.start(context) }
                        .onFailure {
                            controllerStartError = it.message ?: it.javaClass.simpleName
                        }
                }
            },
            onStop = { MappingControllerService.stop(context) },
            onRetry = graph.mappingCoordinator::retry,
            onRefresh = graph.mappingCoordinator::refreshSnapshot,
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

    if (showNotificationPermissionRequired) {
        AlertDialog(
            onDismissRequest = { showNotificationPermissionRequired = false },
            title = { Text("需要显示控制器通知") },
            text = { Text("映射运行期间必须显示可随时停止的前台控制器通知。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationPermissionRequired = false
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                ) { Text("通知设置") }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationPermissionRequired = false }) {
                    Text("稍后")
                }
            },
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
    onRefresh: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag(VolumeMapperTestTags.SCREEN_MAIN),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "音量映射",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
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
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
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
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
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
                onRefresh = onRefresh,
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
    val status = when {
        !settingsLoaded -> "正在加载"
        !disclosureAccepted -> "需要授权"
        runtime.isFailOpen -> "已暂停"
        runtime.canInterceptKeys -> "运行中"
        runtime.isForegroundServiceRunning && !runtime.isAccessibilityConnected -> "等待无障碍"
        runtime.isForegroundServiceRunning -> "等待媒体"
        else -> "未启动"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (runtime.canInterceptKeys) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("控制", style = MaterialTheme.typography.titleMedium)
                    Text(
                        status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = runtime.isForegroundServiceRunning,
                    onCheckedChange = { enabled -> if (enabled) onStart() else onStop() },
                    enabled = settingsLoaded,
                    modifier = Modifier
                        .testTag(VolumeMapperTestTags.MASTER_SWITCH)
                        .semantics {
                            contentDescription = "音量映射开关"
                            stateDescription = status
                        },
                )
            }

            when {
                !settingsLoaded -> Unit
                !disclosureAccepted -> {
                    TextButton(
                        onClick = onShowDisclosure,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) { Text("查看授权") }
                }
                !runtime.isAccessibilityConnected -> {
                    TextButton(
                        onClick = onOpenAccessibility,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Icon(
                            Icons.Default.AccessibilityNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("开启无障碍")
                    }
                }
                runtime.isFailOpen -> {
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) { Text("重试") }
                }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "长按间隔",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
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
                    .testTag(VolumeMapperTestTags.LONG_PRESS_INTERVAL_VALUE)
                    .semantics {
                        contentDescription = "长按连续步进间隔"
                        stateDescription = "$value 毫秒"
                    }
                    .padding(horizontal = 6.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
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
    onRefresh: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val manufacturer = manufacturerLabel()
    val summary = when {
        runtime.isFailOpen -> "需处理"
        runtime.canInterceptKeys -> "正常"
        runtime.isForegroundServiceRunning -> "等待"
        else -> "未启动"
    }
    val snapshot = runtime.snapshot

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(VolumeMapperTestTags.DEVICE_TOGGLE)
                .semantics {
                    contentDescription = "设备信息"
                    stateDescription = if (expanded) "已展开" else "已折叠"
                },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("设备", color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "$manufacturer · $summary",
                    style = MaterialTheme.typography.bodySmall,
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
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag(VolumeMapperTestTags.DEVICE_DETAILS),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    snapshot?.route?.productName ?: "未检测到输出设备",
                    style = MaterialTheme.typography.titleSmall,
                )
                snapshot?.let {
                    Text(
                        "媒体 index ${it.range.minIndex}…${it.range.maxIndex} · 当前 ${it.currentIndex}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    runtime.statusMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("刷新")
                    }
                    TextButton(onClick = onOpenAccessibility) { Text("无障碍") }
                    TextButton(onClick = onOpenAppSettings) { Text("应用设置") }
                }
            }
        }
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

private const val MINIMUM_HOLD_INTERVAL = KeyMappingConfig.MIN_HOLD_STEP_INTERVAL_MILLIS
private const val MAXIMUM_HOLD_INTERVAL = KeyMappingConfig.MAX_HOLD_STEP_INTERVAL_MILLIS
private const val HOLD_INTERVAL_STEP = KeyMappingConfig.HOLD_STEP_INTERVAL_GRID_MILLIS

object VolumeMapperTestTags {
    const val SCREEN_MAIN = "screen_main"
    const val MASTER_SWITCH = "master_switch"
    const val LONG_PRESS_INTERVAL_VALUE = "long_press_interval_value"
    const val LONG_PRESS_INTERVAL_DECREMENT = "long_press_interval_decrement"
    const val LONG_PRESS_INTERVAL_INCREMENT = "long_press_interval_increment"
    const val DEVICE_TOGGLE = "device_toggle"
    const val DEVICE_DETAILS = "device_details"

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
