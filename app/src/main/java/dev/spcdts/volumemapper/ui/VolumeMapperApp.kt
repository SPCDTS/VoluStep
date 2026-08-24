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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.spcdts.volumemapper.AppGraph
import dev.spcdts.volumemapper.core.MappingPreset
import dev.spcdts.volumemapper.core.StepVolumeMap
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import dev.spcdts.volumemapper.runtime.ControllerRuntimeState
import dev.spcdts.volumemapper.runtime.MappingControllerService
import java.util.Locale

private enum class AppPage(
    val label: String,
    val icon: ImageVector,
    val navigationTestTag: String,
) {
    CONTROL("控制", Icons.Default.Equalizer, VolumeMapperTestTags.NAV_CONTROL),
    CURVE("曲线", Icons.Default.Tune, VolumeMapperTestTags.NAV_CURVE),
    DIAGNOSTICS("诊断", Icons.Default.Info, VolumeMapperTestTags.NAV_DIAGNOSTICS),
}

@Composable
fun VolumeMapperApp(graph: AppGraph) {
    val runtime by graph.mappingCoordinator.runtime.collectAsState()
    val settings by graph.settingsRepository.settings.collectAsState()
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.CONTROL) }
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

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppPage.entries.forEach { page ->
                    NavigationBarItem(
                        selected = selectedPage == page,
                        onClick = { selectedPage = page },
                        icon = { Icon(page.icon, contentDescription = null) },
                        label = { Text(page.label) },
                        modifier = Modifier.testTag(page.navigationTestTag),
                    )
                }
            }
        },
    ) { innerPadding ->
        when (selectedPage) {
            AppPage.CONTROL -> ControlScreen(
                runtime = runtime,
                disclosureAccepted = settings.disclosureAccepted,
                onShowDisclosure = { showDisclosure = true },
                onOpenAccessibility = {
                    if (settings.disclosureAccepted) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else {
                        showDisclosure = true
                    }
                },
                onStart = {
                    if (!settings.disclosureAccepted) {
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
                modifier = Modifier.padding(innerPadding),
            )

            AppPage.CURVE -> CurveScreen(
                runtime = runtime,
                settings = settings,
                graph = graph,
                modifier = Modifier.padding(innerPadding),
            )

            AppPage.DIAGNOSTICS -> DiagnosticsScreen(
                runtime = runtime,
                onRefresh = graph.mappingCoordinator::refreshSnapshot,
                onOpenAppSettings = {
                    if (!AppDetailsSettingsLauncher.open(context)) {
                        showAppSettingsUnavailable = true
                    }
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    if (showDisclosure) {
        DisclosureDialog(
            onDismiss = { showDisclosure = false },
            onAccept = {
                graph.settingsRepository.acceptDisclosure()
                graph.settingsRepository.flushPendingWrite()
                showDisclosure = false
            },
        )
    }

    if (showNotificationPermissionRequired) {
        AlertDialog(
            onDismissRequest = { showNotificationPermissionRequired = false },
            title = { Text("需要显示控制器通知") },
            text = {
                Text("映射运行期间必须让你随时看见并停止前台控制器。请允许通知后再启动映射。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationPermissionRequired = false
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                ) { Text("打开通知设置") }
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
            text = { Text("系统没有可处理的应用详情或设置页面。请手动进入系统设置并找到本应用。") },
            confirmButton = {
                TextButton(onClick = { showAppSettingsUnavailable = false }) { Text("知道了") }
            },
        )
    }

    controllerStartError?.let { error ->
        AlertDialog(
            onDismissRequest = { controllerStartError = null },
            title = { Text("控制器启动失败") },
            text = { Text("系统未能启动前台控制器：$error。音量键仍由系统处理。") },
            confirmButton = {
                TextButton(onClick = { controllerStartError = null }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun ControlScreen(
    runtime: ControllerRuntimeState,
    disclosureAccepted: Boolean,
    onShowDisclosure: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(VolumeMapperTestTags.SCREEN_CONTROL),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "音量映射器",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "用自定义曲线把实体音量键映射到当前媒体路由的整数 index。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (!disclosureAccepted) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("启用前需要明确授权", fontWeight = FontWeight.Bold)
                        Text(
                            "本应用使用无障碍 API 接收实体音量键，但不会读取屏幕内容。请先查看数据用途和按键冲突说明。",
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                        Button(onClick = onShowDisclosure) { Text("查看并同意") }
                    }
                }
            }
        } else {
            item {
                StatusCard(runtime)
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (runtime.isForegroundServiceRunning) {
                        Button(
                            onClick = onStop,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("停止")
                        }
                    } else {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("启动映射")
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenAccessibility,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AccessibilityNew, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("无障碍设置")
                    }
                }
            }

            if (runtime.isFailOpen) {
                item {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("重新探测并重试")
                    }
                }
            }

            item {
                CapabilityCard()
            }
        }
    }
}

@Composable
private fun StatusCard(runtime: ControllerRuntimeState) {
    val ready = runtime.canInterceptKeys
    val title = when {
        runtime.isFailOpen -> "按键已交还系统"
        ready -> "映射运行中"
        runtime.isForegroundServiceRunning -> "等待接管按键"
        else -> "映射未启动"
    }
    val containerColor = when {
        runtime.isFailOpen -> MaterialTheme.colorScheme.errorContainer
        ready -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        runtime.isFailOpen -> MaterialTheme.colorScheme.onErrorContainer
        ready -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(runtime.statusMessage)
            runtime.snapshot?.let { snapshot ->
                Text(
                    "${snapshot.route.productName ?: snapshot.route.type.name} · " +
                        "index ${snapshot.currentIndex}/${snapshot.range.maxIndex}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                "控制器 ${if (runtime.isForegroundServiceRunning) "运行中" else "未运行"} · " +
                    "无障碍 ${if (runtime.isAccessibilityConnected) "已连接" else "未连接"} · " +
                    "媒体场景 ${if (runtime.isMediaContextSafe) "可接管" else "已放行"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CapabilityCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(
                "能力与限制",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "收起" else "查看")
            Spacer(Modifier.size(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            HorizontalDivider()
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("• 固定控制全局 STREAM_MUSIC；检测到系统通话音频模式时交还系统。")
                Text("• 公开 API 无法可靠识别所有闹钟、相机和 OEM 场景；发生冲突时请先停止映射。")
                Text("• 精度上限由手机整数音量档、蓝牙 AVRCP/VCS 量化与耳机固件共同决定。")
                Text("• 耳机自身触控若直接发送绝对音量，可能不产生 KeyEvent，因而无法重映射。")
                Text("• 电源键 + 音量下截图等组合键可能与按键消费冲突。")
            }
        }
    }
}

@Composable
private fun CurveScreen(
    runtime: ControllerRuntimeState,
    settings: VolumeMapperSettings,
    graph: AppGraph,
    modifier: Modifier = Modifier,
) {
    val editableSpan = runtime.snapshot?.range?.let { it.maxIndex - it.minIndex }
        ?.takeIf { it > 0 }
        ?: settings.outputMap.basisSpan
    val editablePressCount = settings.outputMap.pressCount.coerceAtMost(editableSpan)
    val editableMap = runtime.snapshot?.range
        ?.takeIf { it.maxIndex > it.minIndex }
        ?.let(settings.outputMap::rebase)
        ?: settings.outputMap
    val fullRangePresets = listOf(
        MappingPreset.LINEAR,
        MappingPreset.LOW_VOLUME_FINE,
        MappingPreset.S_CURVE,
    )
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag(VolumeMapperTestTags.SCREEN_CURVE),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("音量曲线", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "横轴是均匀的按键次数，纵轴是媒体音量 index。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (runtime.snapshot != null && editablePressCount < settings.outputMap.pressCount) {
                Text(
                    "当前路由只有 $editableSpan 个可区分区间，配置的 ${settings.outputMap.pressCount} 次已临时降为 $editablePressCount 次。",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            Column(
                modifier = Modifier.testTag(VolumeMapperTestTags.PRESET_SECTION),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("预设", style = MaterialTheme.typography.labelLarge)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(fullRangePresets) { preset ->
                        val presetMap = StepVolumeMap.fromCurve(
                            curve = preset.createCurve(),
                            basisSpan = editableSpan,
                            pressCount = editablePressCount,
                        )
                        FilterChip(
                            selected = editableMap == presetMap,
                            onClick = {
                                graph.settingsRepository.updateOutputMap(presetMap)
                                graph.settingsRepository.flushPendingWrite()
                            },
                            label = { Text(preset.displayName()) },
                        )
                    }
                }
            }
        }
        item {
            MappingCurveEditor(
                outputMap = settings.outputMap,
                snapshot = runtime.snapshot,
                currentLogicalPosition = runtime.logicalPosition,
                onMapCommitted = { outputMap ->
                    graph.settingsRepository.updateOutputMap(outputMap)
                    graph.settingsRepository.flushPendingWrite()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            KeyBehaviourCard(settings, graph)
        }
    }
}

@Composable
private fun KeyBehaviourCard(settings: VolumeMapperSettings, graph: AppGraph) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.testTag(VolumeMapperTestTags.KEY_BEHAVIOUR_CARD)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(VolumeMapperTestTags.KEY_BEHAVIOUR_TOGGLE),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(
                "按键响应",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "收起" else "展开")
            Spacer(Modifier.size(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            HorizontalDivider()
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("短按严格前进或后退一个曲线状态；总次数由上方 K 控制，每个状态直接对应一个整数 index。")
                Text("长按基础速度：${(settings.keyConfig.holdUnitsPerSecond * 100).format1()}% / 秒")
                Slider(
                    value = settings.keyConfig.holdUnitsPerSecond.toFloat(),
                    onValueChange = {
                        graph.settingsRepository.updateKeyConfig(
                            settings.keyConfig.copy(holdUnitsPerSecond = it.toDouble()),
                        )
                    },
                    onValueChangeFinished = graph.settingsRepository::flushPendingWrite,
                    valueRange = 0.02f..0.4f,
                )
                Text("长按起效延迟：${settings.keyConfig.holdDelayMillis} ms")
                Slider(
                    value = settings.keyConfig.holdDelayMillis.toFloat(),
                    onValueChange = {
                        graph.settingsRepository.updateKeyConfig(
                            settings.keyConfig.copy(holdDelayMillis = it.toLong()),
                        )
                    },
                    onValueChangeFinished = graph.settingsRepository::flushPendingWrite,
                    valueRange = 150f..800f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示系统音量浮层", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.showSystemVolumeUi,
                        onCheckedChange = { show ->
                            graph.settingsRepository.updateShowSystemUi(show)
                            graph.settingsRepository.flushPendingWrite()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(
    runtime: ControllerRuntimeState,
    onRefresh: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = runtime.snapshot
    val finiteDb = snapshot?.range?.decibelsByIndex?.filterNotNull().orEmpty()
    val duplicateDb = finiteDb.size - finiteDb.distinct().size
    val manufacturer = Build.MANUFACTURER
    var showDeviceDetails by rememberSaveable { mutableStateOf(false) }
    var showBackgroundAdvice by rememberSaveable { mutableStateOf(false) }
    val audioRows = buildList {
        add("输出路由" to (snapshot?.route?.type?.name ?: "未知"))
        add("设备名称" to (snapshot?.route?.productName ?: "系统未提供"))
        add("媒体档位" to snapshot?.let { "${it.range.minIndex}…${it.range.maxIndex}，当前 ${it.currentIndex}" }.orEmpty())
        add("媒体场景可接管" to if (runtime.isMediaContextSafe) "是" else "否，按键交还系统")
        add("连续写入失败" to runtime.consecutiveWriteFailures.toString())
    }
    val deviceRows = buildList {
        add("手机厂商" to manufacturer)
        add("品牌 / 型号" to "${Build.BRAND} / ${Build.MODEL}")
        add("Android API" to Build.VERSION.SDK_INT.toString())
        add("ROM 指纹" to Build.FINGERPRINT.take(72))
        add(
            "路由可信度" to when (snapshot?.route?.confidence?.name) {
                "CONFIRMED" -> "系统明确报告"
                "HEURISTIC" -> "启发式识别；dB 仅作诊断参考"
                else -> "未知"
            },
        )
        add("公开 dB 样本" to if (finiteDb.isEmpty()) "不可用；映射仍直接使用 index" else "${finiteDb.size} 个，${finiteDb.first().format1()}…${finiteDb.last().format1()} dB（仅诊断）")
        add("重复 dB 档位" to duplicateDb.toString())
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(VolumeMapperTestTags.SCREEN_DIAGNOSTICS),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("设备诊断", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("所有数值均在当前路由运行时探测，不按厂商品牌硬编码。")
                }
                IconButton(
                    onClick = onRefresh,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新诊断")
                }
            }
        }
        item {
            Text("当前音频", style = MaterialTheme.typography.labelLarge)
        }
        item {
            DiagnosticRows(audioRows)
        }
        item {
            Card {
                TextButton(
                    onClick = { showDeviceDetails = !showDeviceDetails },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text("设备详情", modifier = Modifier.weight(1f))
                    Text(if (showDeviceDetails) "收起" else "展开")
                    Spacer(Modifier.size(4.dp))
                    Icon(
                        imageVector = if (showDeviceDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                }
                if (showDeviceDetails) {
                    HorizontalDivider()
                    DiagnosticRowsContent(deviceRows)
                }
            }
        }
        item {
            val isXiaomiFamily = manufacturer.contains("xiaomi", ignoreCase = true) ||
                Build.BRAND.contains("redmi", ignoreCase = true) ||
                Build.BRAND.contains("poco", ignoreCase = true)
            Card {
                TextButton(
                    onClick = { showBackgroundAdvice = !showBackgroundAdvice },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("后台运行", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "仅在后台按键延迟时检查",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(if (showBackgroundAdvice) "收起" else "展开")
                    Spacer(Modifier.size(4.dp))
                    Icon(
                        imageVector = if (showBackgroundAdvice) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                }
                if (showBackgroundAdvice) {
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "部分手机厂商可能在媒体播放或后台场景延迟无障碍按键回调。这里仅打开系统应用详情，不会自动更改任何设置；调整省电策略只是排查建议，并非已证明的修复。",
                        )
                        Text(
                            if (isXiaomiFamily) {
                                "小米 / HyperOS 可在应用详情中检查“调节媒体音量”权限，并尝试将省电策略设为“无限制”。"
                            } else {
                                "其他厂商可在应用详情中查找对应的后台运行或电池策略。"
                            },
                        )
                        OutlinedButton(onClick = onOpenAppSettings) {
                            Text("打开应用详情")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRows(rows: List<Pair<String, String>>) {
    Card {
        DiagnosticRowsContent(rows)
    }
}

@Composable
private fun DiagnosticRowsContent(rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, (label, value) ->
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value.ifBlank { "未知" }, style = MaterialTheme.typography.bodyLarge)
            }
            if (index != rows.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DisclosureDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
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
                Text("影响：消费音量键可能干扰电源键 + 音量下截图、双音量键无障碍快捷方式和部分厂商快捷键。停止常驻通知中的控制器即可立即恢复系统默认行为。")
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
            Button(onClick = onAccept, enabled = confirmed) { Text("同意") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun MappingPreset.displayName(): String = when (this) {
    MappingPreset.LINEAR -> "线性"
    MappingPreset.LOW_VOLUME_FINE -> "低音精细"
    MappingPreset.S_CURVE -> "S 曲线"
    MappingPreset.NIGHT_CAP -> "夜间上限"
}

private fun Double.format1(): String = String.format(Locale.ROOT, "%.1f", this)

object VolumeMapperTestTags {
    const val NAV_CONTROL = "nav_control"
    const val NAV_CURVE = "nav_curve"
    const val NAV_DIAGNOSTICS = "nav_diagnostics"
    const val SCREEN_CONTROL = "screen_control"
    const val SCREEN_CURVE = "screen_curve"
    const val SCREEN_DIAGNOSTICS = "screen_diagnostics"
    const val CURVE_SCREEN_LIST = SCREEN_CURVE
    const val PRESET_SECTION = "preset_section"
    const val KEY_BEHAVIOUR_CARD = "key_behaviour_card"
    const val KEY_BEHAVIOUR_TOGGLE = "key_behaviour_toggle"
}
