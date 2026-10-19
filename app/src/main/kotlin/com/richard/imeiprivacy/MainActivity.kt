package com.richard.imeiprivacy

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** UI only: root commands still use the existing fixed, deny-suppressed client. */
class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var snapshot by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var clock by mutableLongStateOf(SystemClock.elapsedRealtime())
    private var dialog by mutableStateOf<PrivacyConfirmation?>(null)
    private var autoRefresh by mutableStateOf(false)
    private var readAt = 0L
    private var requestedAt = 0L
    private var resumed = false
    private var scopePrompted = false
    private var legacyInstalled = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            clock = SystemClock.elapsedRealtime()
            if (autoRefresh && RootUiClient.authorized() && !busy && dialog == null && clock - requestedAt >= 4000) {
                request("status")
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scopePrompted = savedInstanceState?.getBoolean("scopePrompted") ?: false
        dialog = savedInstanceState?.getString("confirmation")?.let {
            runCatching { PrivacyConfirmation.valueOf(it) }.getOrNull()
        }
        legacyInstalled = runCatching {
            packageManager.getPackageInfo("com.dsh.imeiprivacy", 0)
        }.isSuccess
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1.4.7"
        setContent {
            val dark = isSystemInDarkTheme()
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
                onDispose { }
            }
            val status = currentStatus()
            val ui = PrivacyUiState(
                status, busy, RootUiClient.authorized(), RootUiClient.denied(), legacyInstalled,
                error, (clock - readAt).coerceAtLeast(0) / 1000, autoRefresh,
            )
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                PrivacyScreen(
                    state = ui,
                    version = version,
                    confirmation = dialog,
                    onRefresh = { request("status") },
                    onChangeEnabled = { enabled ->
                        if (ui.switchEnabled && enabled != ui.pendingEnabled) {
                            dialog = if (enabled) PrivacyConfirmation.ENABLE else PrivacyConfirmation.DISABLE
                        }
                    },
                    onRecheck = { if (ui.recheckEnabled) dialog = PrivacyConfirmation.RECHECK },
                    onReboot = { if (ui.rebootEnabled) dialog = PrivacyConfirmation.REBOOT },
                    onOpenLsposed = ::openLsposed,
                    onDismiss = { dialog = null },
                    onConfirm = ::confirm,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        clock = SystemClock.elapsedRealtime()
        if (RootUiClient.authorized() && !busy && dialog == null) request("status")
        handler.post(ticker)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("scopePrompted", scopePrompted)
        outState.putString("confirmation", dialog?.name)
        super.onSaveInstanceState(outState)
    }

    private fun currentStatus(): ModuleStatus? = snapshot?.let {
        ModuleStatus.parse(it, clock / 1000, android.os.Process.myUid() / 100000)
    }

    private fun confirm(expected: PrivacyConfirmation) {
        // Ignore callbacks from a closing/outdated dialog, including double taps.
        if (dialog != expected || busy) return
        dialog = null
        if (expected.command != null) request(expected.command)
        else if (expected == PrivacyConfirmation.SCOPE) openLsposed()
    }

    private fun request(command: String) {
        if (busy || isDestroyed) return
        busy = true
        requestedAt = SystemClock.elapsedRealtime()
        worker.execute {
            val result = RootUiClient.run(command)
            handler.post {
                if (isDestroyed) return@post
                clock = SystemClock.elapsedRealtime()
                busy = false
                error = result.error
                if (command == "reboot-confirmed") {
                    autoRefresh = false
                    notify(if (error == null) "已提交重启请求，请等待设备重启。" else "重启未确认成功，请检查 root 授权或使用系统电源菜单。")
                    return@post
                }
                if (error == null) {
                    val parsed = ModuleStatus.parse(result.text, clock / 1000, android.os.Process.myUid() / 100000)
                    if (!parsed.available) error = "STATUS_INVALID"
                    else if (command.startsWith("set-enabled ") && parsed.pendingEnabled != command.endsWith("1")) {
                        error = "COMMAND_FAILED"
                    }
                }
                snapshot = result.text.takeIf { error == null }
                readAt = clock
                autoRefresh = error == null
                if (resumed && dialog == null && !scopePrompted && currentStatus()?.scope?.requiresAction() == true) {
                    scopePrompted = true
                    dialog = PrivacyConfirmation.SCOPE
                }
                if (command != "status") {
                    notify(when {
                        error != null -> "操作未确认成功，请检查状态。"
                        command == "recheck" -> "已请求复检，请查看当前结果。"
                        else -> "设置已保存，完整重启后生效。"
                    })
                }
            }
        }
    }

    private fun openLsposed() {
        val launched = runCatching {
            val launch = packageManager.getLaunchIntentForPackage("org.lsposed.manager") ?: return@runCatching false
            startActivity(launch)
            true
        }.getOrDefault(false)
        if (!launched) dialog = PrivacyConfirmation.MANAGER_HELP
    }

    private fun notify(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
