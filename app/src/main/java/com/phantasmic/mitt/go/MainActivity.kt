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

    /** 页面真的画出来之前按住启动画面，避免中间露出一帧空白。 */
    private val contentReady = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 系统启动画面负责显示图标和底色；这里接管它的退场：只淡出，不缩放。
        val splash = installSplashScreen()
        // 页面没画出来就不放走启动画面（带超时兜底，绝不会卡在启动画面）。
        splash.setKeepOnScreenCondition { !contentReady.get() }
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
            !ProxyRuntime.status.value.running
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
                            SettingsRuntime.apply(this, previous, updated)
                        },
                        onStart = ::startProxy,
                        onStop = ::stopProxy,
                        // 第一帧画完再放走启动画面：两个动画（icon 淡出 / 页面揭开）就是并行的。
                        onContentReady = { contentReady.set(true) },
                    )
                }
            }
        }
    }

    private fun startProxy() {
        if (ProxyRuntime.status.value.running) return
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