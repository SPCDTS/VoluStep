package dev.spcdts.volumemapper.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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

private enum class AppPage(val label: String, val icon: ImageVector) {
    CONTROL("控制", Icons.Default.Equalizer),
    CURVE("曲线", Icons.Default.Tune),
    DIAGNOSTICS("诊断", Icons.Default.Info),
}

@Composable
fun VolumeMapperApp(graph: AppGraph) {
    val runtime by graph.mappingCoordinator.runtime.collectAsState()
    val settings by graph.settingsRepository.settings.collectAsState()
    var selectedPage by remember { mutableStateOf(AppPage.CONTROL) }
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
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "音量映射器",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "把手机实体音量键映射到连续曲线，再量化为当前设备真正支持的媒体音量档位。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        item {
            StatusCard(runtime)
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
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenAccessibility, enabled = disclosureAccepted) {
                    Icon(Icons.Default.AccessibilityNew, contentDescription = null)
                    Text(" 无障碍设置")
                }
                if (runtime.isForegroundServiceRunning) {
                    Button(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Text(" 停止")
                    }
                } else {
                    Button(onClick = onStart, enabled = disclosureAccepted) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(" 启动映射")
                    }
                }
            }
        }

        if (runtime.isFailOpen) {
            item {
                Button(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(" 重新探测并重试")
                }
            }
        }

        item {
            CapabilityCard()
        }
    }
}

@Composable
private fun StatusCard(runtime: ControllerRuntimeState) {
    val ready = runtime.canInterceptKeys
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready) Color(0xFF12372D) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(
                            color = if (ready) Color(0xFF4ADE80) else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    if (ready) "已接管媒体音量键" else "尚未接管按键",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(runtime.statusMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            StatusLine("前台控制器", runtime.isForegroundServiceRunning)
            StatusLine("无障碍按键过滤", runtime.isAccessibilityConnected)
            StatusLine("媒体音频场景", runtime.isMediaContextSafe)
            StatusLine("故障自动放行", runtime.isFailOpen, trueMeansWarning = true)
            runtime.snapshot?.let { snapshot ->
                Text(
                    "${snapshot.route.productName ?: snapshot.route.type.name} · " +
                        "${snapshot.currentIndex}/${snapshot.range.maxIndex}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: Boolean, trueMeansWarning: Boolean = false) {
    val text = if (trueMeansWarning) {
        if (value) "已触发" else "未触发"
    } else {
        if (value) "已就绪" else "未就绪"
    }
    Text("$label：$text", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CapabilityCard() {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("公开 API 能力边界", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("• 固定控制全局 STREAM_MUSIC；检测到系统通话音频模式时交还系统。")
            Text("• 公开 API 无法可靠识别所有闹钟、相机和 OEM 场景；发生冲突时请先停止映射。")
            Text("• 精度上限由手机整数音量档、蓝牙 AVRCP/VCS 量化与耳机固件共同决定。")
            Text("• 耳机自身触控若直接发送绝对音量，可能不产生 KeyEvent，因而无法重映射。")
            Text("• 电源键 + 音量下截图等组合键可能与按键消费冲突。")
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
            .testTag(VolumeMapperTestTags.CURVE_SCREEN_LIST),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("按键次数 → 音量 index", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "横轴均匀排列：从最小到最大需要 K 次短按，因此包含 K+1 个音量状态；纵轴是当前媒体路由的真实整数 index。",
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
            Card {
                MappingCurveEditor(
                    outputMap = settings.outputMap,
                    snapshot = runtime.snapshot,
                    currentLogicalPosition = runtime.logicalPosition,
                    onMapCommitted = { outputMap ->
                        graph.settingsRepository.updateOutputMap(outputMap)
                        graph.settingsRepository.flushPendingWrite()
                    },
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        item {
            Column(
                modifier = Modifier.testTag(VolumeMapperTestTags.PRESET_SECTION),
            ) {
                Text("预设", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
            KeyBehaviourCard(settings, graph)
        }
    }
}

@Composable
private fun KeyBehaviourCard(settings: VolumeMapperSettings, graph: AppGraph) {
    Card(modifier = Modifier.testTag(VolumeMapperTestTags.KEY_BEHAVIOUR_CARD)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("按键响应", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
    val rows = buildList {
        add("手机厂商" to manufacturer)
        add("品牌 / 型号" to "${Build.BRAND} / ${Build.MODEL}")
        add("Android API" to Build.VERSION.SDK_INT.toString())
        add("媒体场景可接管" to if (runtime.isMediaContextSafe) "是" else "否，按键交还系统")
        add("ROM 指纹" to Build.FINGERPRINT.take(72))
        add("输出路由" to (snapshot?.route?.type?.name ?: "未知"))
        add(
            "路由可信度" to when (snapshot?.route?.confidence?.name) {
                "CONFIRMED" -> "系统明确报告"
                "HEURISTIC" -> "启发式识别；dB 仅作诊断参考"
                else -> "未知"
            },
        )
        add("设备名称" to (snapshot?.route?.productName ?: "系统未提供"))
        add("媒体档位" to snapshot?.let { "${it.range.minIndex}…${it.range.maxIndex}，当前 ${it.currentIndex}" }.orEmpty())
        add("公开 dB 样本" to if (finiteDb.isEmpty()) "不可用；映射仍直接使用 index" else "${finiteDb.size} 个，${finiteDb.first().format1()}…${finiteDb.last().format1()} dB（仅诊断）")
        add("重复 dB 档位" to duplicateDb.toString())
        add("连续写入失败" to runtime.consecutiveWriteFailures.toString())
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("设备诊断", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("所有数值均在当前路由运行时探测，不按厂商品牌硬编码。")
                }
                AssistChip(
                    onClick = onRefresh,
                    label = { Text("刷新") },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                )
            }
        }
        items(rows) { (label, value) ->
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value.ifBlank { "未知" }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item {
            val isXiaomiFamily = manufacturer.contains("xiaomi", ignoreCase = true) ||
                Build.BRAND.contains("redmi", ignoreCase = true) ||
                Build.BRAND.contains("poco", ignoreCase = true)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "后台运行与省电策略",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
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
                    Button(onClick = onOpenAppSettings) {
                        Text("打开应用详情")
                    }
                }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("为了在其他应用、桌面和锁屏场景接收手机实体音量键，本应用需要启用无障碍服务。")
                Text("会处理：音量上/下键的键码与时间，并使用 AudioManager 修改全局媒体音量。")
                Text("不会处理：屏幕文字、窗口内容、触摸、密码、账号信息；本应用不上传任何按键或音量数据。")
                Text("影响：消费音量键可能干扰电源键 + 音量下截图、双音量键无障碍快捷方式和部分厂商快捷键。停止常驻通知中的控制器即可立即恢复系统默认行为。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
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
    const val CURVE_SCREEN_LIST = "curve_screen_list"
    const val PRESET_SECTION = "preset_section"
    const val KEY_BEHAVIOUR_CARD = "key_behaviour_card"
}
