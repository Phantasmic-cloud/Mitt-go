package com.phantasmic.mitt.go

import android.content.Context

/**
 * 设置变更的唯一落地入口。
 *
 * 页面里只负责把新的 [MittSettings] 交回来（`onSettingsChange`），落盘、
 * 同步到各个运行时、写变更日志都在这里做——不要再在页面里单独补
 * `CaptureStore.setXxx` 或 `AsyncUploadRuntime.sync()`。
 */
object SettingsRuntime {

    /** 冷启动时把设置灌进运行时（此时没有「上一版设置」，所以不写变更日志）。 */
    fun applyInitial(settings: MittSettings) {
        CaptureStore.setMemoryLimitMb(settings.memoryLimitMb)
        CaptureStore.setAutoSaveDays(settings.autoSaveDays)
    }

    fun apply(context: Context, previous: MittSettings, updated: MittSettings) {
        AppSettings.save(context, updated)
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
        if (updated.asyncUploadEnabled != previous.asyncUploadEnabled) {
            // 开关变了要重新下发配置；内核没起时 sync() 自己会跳过。
            AsyncUploadRuntime.sync()
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
        if (updated.upstream != previous.upstream) {
            MittLog.info(
                "proxy",
                if (updated.upstream.enabled) {
                    "上游代理已开启 ${updated.upstream.address}" +
                        if (updated.upstream.username.isNotBlank()) "（带认证）" else ""
                } else {
                    "上游代理已关闭"
                },
            )
            if (ProxyCore.isRunning()) {
                Thread {
                    runCatching { ProxyCore.setUpstream(updated.upstream) }
                }.start()
            }
        }
        if (updated.socksEnabled != previous.socksEnabled && ProxyCore.isRunning()) {
            Thread {
                runCatching { ProxyCore.setSocksEnabled(updated.socksEnabled) }
            }.start()
        }
    }
}
