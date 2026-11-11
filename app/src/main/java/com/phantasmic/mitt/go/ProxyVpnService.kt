package com.phantasmic.mitt.go

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phantasmic.mitt.go.vpn.ConnectionManager
import com.phantasmic.mitt.go.vpn.ProxyVpnThread
import com.phantasmic.mitt.go.vpn.socket.ProtectSocket
import com.phantasmic.mitt.go.vpn.socket.ProtectSocketHolder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns the Android TUN interface and the Kotlin packet transport.
 *
 * HTTP(S) flows are forwarded to the local Go transparent ingress. The Go
 * process/library performs TLS MITM with the fixed Mitt CA and exposes captured
 * metadata to the Compose UI.
 */
class ProxyVpnService : VpnService(), ProtectSocket {
    companion object {
        private const val TAG = "MittGoVpnService"
        private const val CHANNEL_ID = "mitt-go-vpn"
        private const val NOTIFICATION_ID = 9527

        /** 抓包通知刷新间隔：只读两个累计计数，1 秒一次对性能没有可感知影响。 */
        private const val TRAFFIC_INTERVAL_MS = 1_000L

        const val ACTION_START = "com.phantasmic.mitt.go.START"
        const val ACTION_STOP = "com.phantasmic.mitt.go.STOP"

        /** 控制中心磁贴点「停止」时发的广播；比 stopService 稳。 */
        const val ACTION_STOP_CAPTURE = "com.phantasmic.mitt.go.STOP_CAPTURE"

        const val EXTRA_PROXY_MODE = "proxy_mode"
        const val EXTRA_HTTP_PORT = "http_port"
        const val EXTRA_TUNNEL_PORT = "tunnel_port"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_SOCKS_ENABLED = "socks_enabled"

        const val MAX_PACKET_LEN = 1500
        const val VIRTUAL_HOST = "10.0.0.2"

        fun startIntent(
            context: Context,
            proxyMode: ProxyMode,
            httpPort: Int,
            tunnelPort: Int,
            socksPort: Int,
            socksEnabled: Boolean,
        ): Intent = Intent(context, ProxyVpnService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_PROXY_MODE, proxyMode.name)
            putExtra(EXTRA_HTTP_PORT, httpPort)
            putExtra(EXTRA_TUNNEL_PORT, tunnelPort)
            putExtra(EXTRA_SOCKS_PORT, socksPort)
            putExtra(EXTRA_SOCKS_ENABLED, socksEnabled)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, ProxyVpnService::class.java).apply { action = ACTION_STOP }
    }

    private val stopCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP_CAPTURE) {
                MittLog.info("mitm", "控制中心磁贴：停止抓包")
                disconnect()
            }
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val trafficScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var trafficFuture: ScheduledFuture<*>? = null
    private var trafficUpBase = 0L
    private var trafficDownBase = 0L
    private var proxyOnlyMode = false
    private var proxyOnlyPort = 0
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: ProxyVpnThread? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var starting = false

    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            stopCaptureReceiver,
            IntentFilter(ACTION_STOP_CAPTURE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            disconnect()
            return START_NOT_STICKY
        }
        if (intent == null) {
            if (!ProxyRuntime.status.value.running) stopSelf()
            return START_STICKY
        }

        val proxyMode = runCatching {
            ProxyMode.valueOf(intent.getStringExtra(EXTRA_PROXY_MODE) ?: ProxyMode.VPN_TUN.name)
        }.getOrDefault(ProxyMode.VPN_TUN)
        // 抓包中要常驻一条带吞吐和「停止抓包」的通知：两种模式都以前台服务跑。
        try {
            startForegroundCompat(buildNotification(getString(R.string.vpn_notification_starting)))
        } catch (t: Throwable) {
            MittLog.warn("mitm", "抓包通知启动失败：${t.message ?: t.javaClass.simpleName}")
        }

        if (ProxyRuntime.status.value.running || starting) return START_STICKY
        starting = true
        stopRequested = false
        val httpPort = intent.getIntExtra(EXTRA_HTTP_PORT, 9099)
        val tunnelPort = intent.getIntExtra(EXTRA_TUNNEL_PORT, 9098)
        val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 1080)
        val socksEnabled = intent.getBooleanExtra(EXTRA_SOCKS_ENABLED, false)
        proxyOnlyMode = proxyMode == ProxyMode.PROXY_ONLY
        proxyOnlyPort = httpPort

        ProxyRuntime.markStarting()
        worker.execute {
            try {
                val filterSettings = AppSettings.load(applicationContext)
                ProxyCore.start(
                    context = applicationContext,
                    httpPort = httpPort,
                    tunnelPort = tunnelPort,
                    socksPort = socksPort,
                    socksEnabled = socksEnabled,
                    upstream = filterSettings.upstream,
                    domainMode = filterSettings.domainFilterMode,
                    domainWhite = filterSettings.domainWhitelist,
                    domainBlack = filterSettings.domainBlacklist,
                    certMode = filterSettings.certMode,
                )
                // 旁路上传：内核起来后再下发规则，没开就下发一份空配置清掉旧的。
                AsyncUploadRuntime.sync()
                if (filterSettings.upstream.enabled) {
                    MittLog.info(
                        "proxy",
                        "上游代理生效：${filterSettings.upstream.address}" +
                            if (filterSettings.upstream.username.isNotBlank()) "（带认证）" else "",
                    )
                }
                if (filterSettings.certMode == CertMode.INDIA) {
                    MittLog.info("cert", "证书模式：印度证书（免 root），使用 eMudhra 交叉签名中间证书签发")
                }
                if (filterSettings.domainFilterMode != FilterMode.OFF) {
                    MittLog.info(
                        "filter",
                        "域名过滤 ${filterSettings.domainFilterMode.label} · 白名单 " +
                            filterSettings.domainWhitelist.joinToString(",") +
                            " · 黑名单 " +
                            filterSettings.domainBlacklist.joinToString(","),
                    )
                }
                if (stopRequested) {
                    runCatching { ProxyCore.stop() }
                    return@execute
                }

                if (proxyMode == ProxyMode.VPN_TUN) {
                    val fd = createVpnInterface()
                        ?: error("系统未建立 VPN 接口")
                    vpnInterface = fd
                    if (stopRequested) {
                        closeVpnResources()
                        runCatching { ProxyCore.stop() }
                        return@execute
                    }

                    ProtectSocketHolder.setProtectSocket(this)
                    val packetThread = ProxyVpnThread(
                        vpnInterface = fd,
                        proxyHost = "127.0.0.1",
                        proxyPort = tunnelPort,
                    )
                    vpnThread = packetThread
                    packetThread.start()
                    registerNetworkCallback()
                }
                ProxyRuntime.markRunning()
                starting = false
                startTrafficTicker()
                CaptureStore.beginSession()
                if (proxyMode == ProxyMode.PROXY_ONLY) {
                    MittLog.info("mitm", "仅代理模式开始监听 127.0.0.1:$httpPort")
                } else {
                    MittLog.info("mitm", "VPN+Tun 模式建立 VPN service")
                }
                MittLog.debug("mitm") {
                    "http=$httpPort tunnel=$tunnelPort socks=$socksPort"
                }
                Log.i(TAG, "Go MITM started: mode=$proxyMode http=$httpPort tunnel=$tunnelPort socks=$socksPort")
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                starting = false
                MittLog.error("mitm", "启动失败：${t.message ?: t.javaClass.simpleName}")
                ProxyRuntime.markError(t.message ?: t.javaClass.simpleName)
                runCatching { ProxyCore.stop() }
                closeVpnResources()
                stopTrafficTicker()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        MittLog.warn("mitm", "VPN 被系统撤销")
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        disconnect()
        runCatching { unregisterReceiver(stopCaptureReceiver) }
        worker.shutdown()
        trafficScheduler.shutdownNow()
        super.onDestroy()
    }

    private fun createVpnInterface(): ParcelFileDescriptor? {
        val settings = AppSettings.load(this)
        val builder = Builder()
            .setMtu(MAX_PACKET_LEN)
            .addAddress(VIRTUAL_HOST, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("223.5.5.5")
            .addDnsServer("119.29.29.29")
            .setSession("Mitt-go")
            .setBlocking(true)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

        // Whitelist wins whenever it is active and non-empty, matching Mitt.
        // Otherwise the blacklist is applied. Our own package is always excluded
        // from the tunnel so upstream sockets cannot loop back into it.
        val white = if (settings.filterMode == FilterMode.WHITELIST) {
            settings.whitelist - packageName
        } else {
            emptySet()
        }
        if (white.isNotEmpty()) {
            white.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
            }
        } else {
            builder.addDisallowedApplication(packageName)
            if (settings.filterMode != FilterMode.OFF) {
                settings.blacklist
                    .filter { it != packageName }
                    .forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        return builder.establish()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { setUnderlyingNetworks(arrayOf(network)) }
            }

            override fun onLost(network: Network) {
                ConnectionManager.instance.closeAll()
            }
        }
        networkCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure {
                networkCallback = null
                Log.w(TAG, "registerDefaultNetworkCallback failed", it)
            }
    }

    private fun disconnect() {
        val wasRunning = ProxyRuntime.status.value.running
        if (wasRunning) MittLog.info("mitm", "停止抓包")
        // starting 也要清掉：启动过程中被停止时，UI 不能一直停在“启动中”。
        if (wasRunning || ProxyRuntime.status.value.starting) ProxyRuntime.markStopped()
        starting = false
        stopRequested = true
        runCatching { networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) } }
        networkCallback = null
        connectivityManager = null
        runCatching { vpnThread?.stopThread() }
        vpnThread = null
        ConnectionManager.instance.closeAll()
        closeVpnResources()
        if (wasRunning) {
            worker.execute {
                // 先把剩余变更拉回 Kotlin，再按策略落盘/归档，最后关内核
                runCatching { CaptureStore.flushFromCore() }
                runCatching { CaptureStore.endSession() }
                runCatching { ProxyCore.stop() }
            }
        }
        stopTrafficTicker()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeVpnResources() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(content: CharSequence): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mitt_go)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.vpn_notification_stop), stopIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 每秒算一次吞吐刷新通知；字节数取差值，成功/失败是内核的累计条数。 */
    private fun startTrafficTicker() {
        if (trafficFuture != null) return
        val initial = runCatching { ProxyCore.traffic() }.getOrDefault(TrafficStats())
        trafficUpBase = initial.up
        trafficDownBase = initial.down
        trafficFuture = trafficScheduler.scheduleAtFixedRate({
            runCatching {
                val stats = ProxyCore.traffic()
                val upRate = (stats.up - trafficUpBase).coerceAtLeast(0L)
                val downRate = (stats.down - trafficDownBase).coerceAtLeast(0L)
                trafficUpBase = stats.up
                trafficDownBase = stats.down
                updateNotification(statusText(upRate, downRate, stats))
            }
        }, TRAFFIC_INTERVAL_MS, TRAFFIC_INTERVAL_MS, TimeUnit.MILLISECONDS)
        updateNotification(statusText(0, 0, initial))
    }

    private fun stopTrafficTicker() {
        trafficFuture?.cancel(false)
        trafficFuture = null
    }

    /** 一行状态：`1.24 MB/s↑  86.0 KB/s↓  |  32 成功  1 失败`。 */
    private fun statusText(upRate: Long, downRate: Long, stats: TrafficStats): String =
        if (proxyOnlyMode) {
            getString(
                R.string.vpn_notification_proxy_only,
                proxyOnlyPort,
                stats.ok,
                stats.fail,
            )
        } else {
            getString(
                R.string.vpn_notification_line,
                formatSize(upRate),
                formatSize(downRate),
                stats.ok,
                stats.fail,
            )
        }

    private fun updateNotification(content: CharSequence) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(content)) }
    }
}