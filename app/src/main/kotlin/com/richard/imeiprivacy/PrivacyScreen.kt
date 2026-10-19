package com.richard.imeiprivacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Actual miuix 0.9.4 components, not Android widgets styled to resemble them. */
@Composable
internal fun PrivacyScreen(
    state: PrivacyUiState,
    version: String,
    confirmation: PrivacyConfirmation?,
    onRefresh: () -> Unit,
    onChangeEnabled: (Boolean) -> Unit,
    onRecheck: () -> Unit,
    onReboot: () -> Unit,
    onOpenLsposed: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (PrivacyConfirmation) -> Unit,
) {
    val scroll = MiuixScrollBehavior()
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = "IMEI Privacy",
                largeTitle = "隐私保护",
                subtitle = "设备标识，由你掌控",
                scrollBehavior = scroll,
            )
        },
    ) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "status") {
                    StatusCard(state, onRefresh)
                }
                item(key = "settings") {
                    Section("隐私保护") {
                        SwitchPreference(
                            checked = state.pendingEnabled,
                            onCheckedChange = onChangeEnabled,
                            title = "IMEI 伪装",
                            summary = "同时控制 IMEI / MEID Hook、属性覆盖和查找设备保护，完整重启生效。",
                            enabled = state.switchEnabled,
                        )
                        InsetDivider()
                        DetailRow("本次启动", state.currentValue)
                        DetailRow("下次启动", state.pendingValue)
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = state.settingSummary,
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (state.needsReboot) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            TextButton(
                                text = if (state.needsReboot) "重启并应用更改" else "重启设备",
                                onClick = onReboot,
                                enabled = state.rebootEnabled,
                                modifier = Modifier.fillMaxWidth(),
                                minHeight = 48.dp,
                                colors = if (state.needsReboot) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                            )
                        }
                    }
                }
                item(key = "scope") {
                    Section("LSPosed") {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.scopeTitle, style = MiuixTheme.textStyles.subtitle, color = toneColor(state.scopeTone))
                            Text(state.scopeSummary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            TextButton(
                                text = "前往 LSPosed 开启",
                                onClick = onOpenLsposed,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.busy,
                                minHeight = 48.dp,
                            )
                        }
                    }
                }
                item(key = "details") {
                    Section("运行详情") {
                        DetailRow("root 权限", state.rootValue)
                        InsetDivider()
                        DetailRow("完整模块版本", state.moduleVersion)
                        InsetDivider()
                        DetailRow("目标进程验证", state.verifiedValue)
                        InsetDivider()
                        BasicComponent(
                            title = "诊断详情",
                            summary = state.reason,
                            onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                            onClickLabel = if (diagnosticsExpanded) "收起诊断详情" else "展开诊断详情",
                            endActions = {
                                Text(
                                    if (diagnosticsExpanded) "收起" else "展开",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            },
                        )
                        if (diagnosticsExpanded) {
                            SelectionContainer {
                                Text(
                                    "原因码：${state.reasonCode}\n包名：com.richard.imeiprivacy\n作用域配置不等于运行注入，不能仅凭 APK 安装状态判断生效。",
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                        TextButton(
                            text = "重新自检",
                            onClick = onRecheck,
                            enabled = state.recheckEnabled,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            minHeight = 48.dp,
                        )
                    }
                }
                item(key = "footer") {
                    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "仅在页面前台自动刷新。自检只验证已覆盖路径，仍有多进程检测窗口，不代表所有个人信息绝不外泄。仅安装 APK 不能替代完整 root 模块。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            "IMEI Privacy $version · miuix 0.9.4",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            ConfirmationDialog(confirmation, state, onDismiss, onConfirm)
        }
    }
}

@Composable
private fun StatusCard(state: PrivacyUiState, onRefresh: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(24.dp),
        colors = when (state.statusTone) {
            PrivacyUiState.Tone.PRIMARY -> CardDefaults.defaultColors(color = colors.primaryContainer, contentColor = colors.onPrimaryContainer)
            PrivacyUiState.Tone.ERROR -> CardDefaults.defaultColors(color = colors.errorContainer, contentColor = colors.onErrorContainer)
            else -> CardDefaults.defaultColors()
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("运行状态", style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
            if (state.busy) InfiniteProgressIndicator(color = toneColor(state.statusTone), size = 20.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            state.statusTitle,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MiuixTheme.textStyles.title3,
            color = toneColor(state.statusTone),
        )
        Spacer(Modifier.height(12.dp))
        Text(state.statusSummary, style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
        Spacer(Modifier.height(16.dp))
        Text(state.snapshotLabel, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
        Spacer(Modifier.height(20.dp))
        TextButton(
            text = state.refreshLabel,
            onClick = onRefresh,
            enabled = state.refreshEnabled,
            modifier = Modifier.fillMaxWidth(),
            minHeight = 48.dp,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.padding(horizontal = 12.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Card(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun DetailRow(title: String, value: String) {
    BasicComponent(
        title = title,
        endActions = { Text(value, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions) },
    )
}

@Composable
private fun InsetDivider() = HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

@Composable
private fun toneColor(tone: PrivacyUiState.Tone): Color = when (tone) {
    PrivacyUiState.Tone.PRIMARY -> MiuixTheme.colorScheme.primary
    PrivacyUiState.Tone.ERROR -> MiuixTheme.colorScheme.error
    PrivacyUiState.Tone.WARNING, PrivacyUiState.Tone.NEUTRAL -> MiuixTheme.colorScheme.onSurface
}

@Composable
private fun ConfirmationDialog(
    confirmation: PrivacyConfirmation?,
    state: PrivacyUiState,
    onDismiss: () -> Unit,
    onConfirm: (PrivacyConfirmation) -> Unit,
) {
    // Retain closing content while miuix animates the overlay out.
    var previous by remember { mutableStateOf<PrivacyConfirmation?>(null) }
    SideEffect { if (confirmation != null) previous = confirmation }
    val shown = confirmation ?: previous
    OverlayDialog(
        show = confirmation != null,
        title = shown?.title,
        summary = if (shown == PrivacyConfirmation.SCOPE) state.scopeSummary else shown?.summary,
        onDismissRequest = onDismiss,
    ) {
        if (shown != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shown != PrivacyConfirmation.MANAGER_HELP) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = confirmation != null && !state.busy,
                        minHeight = 48.dp,
                    )
                }
                TextButton(
                    text = shown.confirmLabel,
                    onClick = { onConfirm(shown) },
                    modifier = Modifier.weight(1f),
                    enabled = confirmation != null && !state.busy,
                    minHeight = 48.dp,
                    colors = if (shown.caution) {
                        ButtonDefaults.textButtonColorsPrimary(color = MiuixTheme.colorScheme.error, textColor = MiuixTheme.colorScheme.onError)
                    } else ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
