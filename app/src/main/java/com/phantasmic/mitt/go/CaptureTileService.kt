package com.phantasmic.mitt.go

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 控制中心磁贴：图标固定西兰花（🥦），字样跟着状态走——
 * 关着显示 `Mitt-go`，开着显示 `VPN`（VPN+TUN 模式）或 `仅代理`（仅代理模式）。
 *
 * 关：广播给正在跑的服务 + stopService，不经过界面。
 * 开：已经授权过 VPN 就直接 startForegroundService；没授权或者系统不让后台起
 * 前台服务，就打开 App 走一次正常的启动流程（VPN 授权弹窗只能在 Activity 里弹）。
 */
class CaptureTileService : TileService() {
    private var listening: Job? = null
    private var broccoli: Icon? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        listening?.cancel()
        listening = CoroutineScope(Dispatchers.Main.immediate).launch {
            ProxyRuntime.status.collect { updateTile() }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val status = ProxyRuntime.status.value
        if (status.running || status.starting) {
            stopCapture()
        } else {
            startCapture()
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val status = ProxyRuntime.status.value
        val active = status.running || status.starting
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = broccoli ?: emojiIcon("🥦").also { broccoli = it }
        tile.label = when {
            !active -> getString(R.string.tile_label_idle)
            AppSettings.load(this).proxyMode == ProxyMode.PROXY_ONLY ->
                getString(R.string.tile_label_proxy)
            else -> getString(R.string.tile_label_vpn)
        }
        tile.updateTile()
    }

    /** 把 emoji 直接画成位图当磁贴图标：走系统自己的 emoji 字体，不自己画图形。 */
    private fun emojiIcon(emoji: String): Icon {
        val size = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.82f
            textAlign = Paint.Align.CENTER
        }
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(emoji, size / 2f, baseline, paint)
        return Icon.createWithBitmap(bitmap)
    }

    private fun stopCapture() {
        // 日志由服务里的广播接收器记（避免同一次停止出现两行）。
        sendBroadcast(
            Intent(ProxyVpnService.ACTION_STOP_CAPTURE).setPackage(packageName),
        )
        runCatching { stopService(Intent(this, ProxyVpnService::class.java)) }
    }

    private fun startCapture() {
        val settings = AppSettings.load(this)
        MittLog.info("mitm", "控制中心磁贴：开始抓包（${settings.proxyMode.label}）")
        if (settings.proxyMode != ProxyMode.PROXY_ONLY) {
            val consent = runCatching { VpnService.prepare(this) }.getOrNull()
            if (consent != null) {
                // 没授权过 VPN：必须让用户在一个 Activity 里点同意。
                openApp()
                return
            }
        }
        val intent = ProxyVpnService.startIntent(
            context = this,
            proxyMode = settings.proxyMode,
            httpPort = settings.httpPort,
            tunnelPort = settings.tunnelPort,
            socksPort = settings.socksPort,
            socksEnabled = settings.socksEnabled,
        )
        val started = runCatching { startForegroundService(intent) }.isSuccess
        if (!started) openApp()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_START_CAPTURE, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                5,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            runCatching { startActivityAndCollapse(pending) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { startActivityAndCollapse(intent) }
        }
    }
}
