package com.phantasmic.mitt.go

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    private var currentSettings = MittSettings()
    private var pendingStart = false

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && pendingStart) {
                pendingStart = false
                launchVpnService()
            } else {
                pendingStart = false
                ProxyRuntime.markError("未授权 VPN，无法抓包")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 系统启动画面负责显示图标和底色；这里接管它的退场：只淡出，不缩放。
        val splash = installSplashScreen()
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { provider.remove() }
                .start()
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        currentSettings = AppSettings.load(this)
        MittLog.debugEnabled = currentSettings.debugMode
        MittLog.info(
            "App",
            "Mitt-go ${BuildConfig.VERSION_NAME} 启动（${if (currentSettings.debugMode) "DEBUG" else "普通"}模式）",
        )
        if (ProxyCore.isRunning()) ProxyRuntime.markRunning()

        // 冷启动自动开始抓包：只认 savedInstanceState == null（进程被系统回收后重进也算冷启动），
        // 切后台再回来不会触发。延迟一点，等启动画面和权限流程都就位。
        if (savedInstanceState == null &&
            currentSettings.autoStartOnLaunch &&
            !ProxyVpnService.isRunning
        ) {
            window.decorView.postDelayed(
                { runCatching { startProxy() } },
                700,
            )
        }

        setContent {
            var settings by remember { mutableStateOf(currentSettings) }
            val colorMode = when (settings.themeMode) {
                ThemeMode.LIGHT -> ColorSchemeMode.Light
                ThemeMode.DARK -> ColorSchemeMode.Dark
                ThemeMode.SYSTEM -> ColorSchemeMode.System
            }
            val controller = remember(settings.themeMode) { ThemeController(colorMode) }
            MiuixTheme(controller = controller) {
                // Miuix 默认尺寸偏大，整体缩到 0.86，接近 KernelSU 的观感。
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density * 0.86f, base.fontScale),
                ) {
                    MittGoRoot(
                        settings = settings,
                        onSettingsChange = { updated ->
                            val previous = currentSettings
                            settings = updated
                            currentSettings = updated
                            AppSettings.save(this, updated)
                            CaptureStore.setMemoryLimitMb(updated.memoryLimitMb)
                            CaptureStore.setAutoSaveDays(updated.autoSaveDays)
                            if (updated.debugMode != previous.debugMode) {
                                // 先切换开关，后面的 debug 日志才记得下来
                                MittLog.debugEnabled = updated.debugMode
                                MittLog.info(
                                    "setting",
                                    "DEBUG 模式已${if (updated.debugMode) "开启" else "关闭"}",
                                )
                            }
                            if (updated.proxyMode != previous.proxyMode) {
                                MittLog.debug("setting") {
                                    "代理模式成功更改为 ${updated.proxyMode.label} 模式"
                                }
                            }
                            if (updated.themeMode != previous.themeMode) {
                                MittLog.debug("setting") {
                                    "主题模式更改为 ${updated.themeMode.label}"
                                }
                            }
                            if (updated.advancedEffects != previous.advancedEffects) {
                                MittLog.debug("setting") {
                                    "高级效果已${if (updated.advancedEffects) "开启" else "关闭"}"
                                }
                            }
                            if (updated.memoryLimitMb != previous.memoryLimitMb) {
                                MittLog.info("setting", "内存上限改为 ${updated.memoryLimitMb} MB")
                            }
                            if (updated.autoSaveDays != previous.autoSaveDays) {
                                val days = if (updated.autoSaveDays == 0) "关闭" else "${updated.autoSaveDays} 天"
                                MittLog.info("setting", "历史自动保存改为 $days")
                            }
                            if (updated.socksEnabled != previous.socksEnabled) {
                                MittLog.info(
                                    "setting",
                                    "SOCKS5 已${if (updated.socksEnabled) "开启" else "关闭"}",
                                )
                            }
                            if (ProxyCore.isRunning() &&
                                (updated.domainFilterMode != previous.domainFilterMode ||
                                    updated.domainWhitelist != previous.domainWhitelist ||
                                    updated.domainBlacklist != previous.domainBlacklist)
                            ) {
                                Thread {
                                    runCatching {
                                        ProxyCore.setDomainFilter(
                                            updated.domainFilterMode,
                                            updated.domainWhitelist,
                                            updated.domainBlacklist,
                                        )
                                    }
                                }.start()
                            }
                            if (updated.socksEnabled != previous.socksEnabled &&
                                ProxyCore.isRunning()
                            ) {
                                Thread {
                                    runCatching {
                                        ProxyCore.setSocksEnabled(updated.socksEnabled)
                                    }
                                }.start()
                            }
                        },
                        onStart = ::startProxy,
                        onStop = ::stopProxy,
                    )
                }
            }
        }
    }

    private fun startProxy() {
        if (ProxyVpnService.isRunning) return
        if (currentSettings.proxyMode == ProxyMode.PROXY_ONLY) {
            launchVpnService()
            return
        }
        val prepare = try {
            VpnService.prepare(this)
        } catch (t: Throwable) {
            ProxyRuntime.markError(t.message ?: "VPN 准备失败")
            return
        }
        if (prepare != null) {
            pendingStart = true
            vpnPermissionLauncher.launch(prepare)
        } else {
            launchVpnService()
        }
    }

    private fun launchVpnService() {
        val intent = ProxyVpnService.startIntent(
            context = this,
            proxyMode = currentSettings.proxyMode,
            httpPort = currentSettings.httpPort,
            tunnelPort = currentSettings.tunnelPort,
            socksPort = currentSettings.socksPort,
            socksEnabled = currentSettings.socksEnabled,
        )
        try {
            // VpnService owns the system VPN notification. Starting it as an
            // ordinary service matches the tested Mitt path and avoids the
            // Android 14+ strict foreground-service-type checks.
            startService(intent)
        } catch (t: Throwable) {
            ProxyRuntime.markError(t.message ?: "启动 VPN 服务失败")
        }
    }

    private fun stopProxy() {
        startService(ProxyVpnService.stopIntent(this))
    }

}