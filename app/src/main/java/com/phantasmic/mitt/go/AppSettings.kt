package com.phantasmic.mitt.go

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ProxyMode(val label: String) {
    VPN_TUN("VPN + Tun"),
    PROXY_ONLY("仅代理"),
}

enum class FilterMode(val label: String) {
    OFF("关闭"),
    WHITELIST("白名单"),
    BLACKLIST("黑名单"),
}

enum class CertMode(val label: String) {
    BUILTIN("内置证书(root设备)"),
    INDIA("印度证书(免root)"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式"),
}

/** 关键词高亮规则：命中正则的整条 URL 用这个颜色。 */
data class HighlightRule(val colorArgb: Int, val pattern: String)

/**
 * 上游代理：抓到的流量在 MITM 之后先送到这个 HTTP 代理再出网
 * （典型用法：链到手机/局域网里已有的代理端口）。
 *
 * 地址和端口对上以后可以带 Basic 认证；用户名留空就不带认证头。
 */
data class UpstreamProxy(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 7890,
    val username: String = "",
    val password: String = "",
) {
    /** 摘要里显示的 `host:port`。 */
    val address: String get() = "$host:$port"

    /** 保存前的基本校验：地址非空且不含空白，端口在合法范围。 */
    val isValid: Boolean
        get() = host.isNotBlank() && host.none { it.isWhitespace() } && port in 1..65535
}

data class MittSettings(
    val proxyMode: ProxyMode = ProxyMode.VPN_TUN,
    val socksEnabled: Boolean = false,
    val httpPort: Int = 9099,
    val tunnelPort: Int = 9098,
    val socksPort: Int = 1080,
    /** 上游代理：抓到的请求先送到它再出网。 */
    val upstream: UpstreamProxy = UpstreamProxy(),
    val filterMode: FilterMode = FilterMode.OFF,
    val whitelist: Set<String> = emptySet(),
    val blacklist: Set<String> = emptySet(),
    /** 域名过滤：和不抓的 App 过滤互相独立，可以同时生效。 */
    val domainFilterMode: FilterMode = FilterMode.OFF,
    val domainWhitelist: Set<String> = emptySet(),
    val domainBlacklist: Set<String> = emptySet(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val advancedEffects: Boolean = true,
    /** 抓包记录在 Kotlin 侧的内存上限，超出后丢最旧的。 */
    val memoryLimitMb: Int = 128,
    /** 历史自动保存天数：0 = 关闭。 */
    val autoSaveDays: Int = 0,
    /** DEBUG 模式：日志页记录详细日志。 */
    val debugMode: Boolean = false,
    /** 冷启动 App 时自动开始抓包（切后台再回来不触发）。 */
    val autoStartOnLaunch: Boolean = false,
    /** 签发证书用的 CA：内置 Mitt CA 或 eMudhra 泄露中间证书（免 root）。 */
    val certMode: CertMode = CertMode.BUILTIN,
    /** 旁路上传总开关：命中规则的请求/响应体会异步 POST 到用户服务器。 */
    val asyncUploadEnabled: Boolean = false,
    val highlightEnabled: Boolean = false,
    val highlightRules: List<HighlightRule> = emptyList(),
)

object AppSettings {
    private const val PREFS = "mitt_go_settings"
    private const val KEY_PROXY_MODE = "proxy_mode"
    private const val KEY_SOCKS_ENABLED = "socks_enabled"
    private const val KEY_HTTP_PORT = "http_port"
    private const val KEY_TUNNEL_PORT = "tunnel_port"
    private const val KEY_SOCKS_PORT = "socks_port"
    private const val KEY_UPSTREAM_ENABLED = "upstream_enabled"
    private const val KEY_UPSTREAM_HOST = "upstream_host"
    private const val KEY_UPSTREAM_PORT = "upstream_port"
    private const val KEY_UPSTREAM_USER = "upstream_user"
    private const val KEY_UPSTREAM_PASSWORD = "upstream_password"
    private const val KEY_FILTER_MODE = "filter_mode"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_BLACKLIST = "blacklist"
    private const val KEY_DOMAIN_FILTER_MODE = "domain_filter_mode"
    private const val KEY_DOMAIN_WHITELIST = "domain_whitelist"
    private const val KEY_DOMAIN_BLACKLIST = "domain_blacklist"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_ADVANCED_EFFECTS = "advanced_effects"
    private const val KEY_MEMORY_LIMIT = "memory_limit_mb"
    private const val KEY_AUTO_SAVE_DAYS = "auto_save_days"
    private const val KEY_AUTO_START_ON_LAUNCH = "auto_start_on_launch"
    private const val KEY_CERT_MODE = "cert_mode"
    private const val KEY_ASYNC_UPLOAD = "async_upload_enabled"
    private const val KEY_DEBUG_MODE = "debug_mode"
    private const val KEY_HIGHLIGHT_ENABLED = "highlight_enabled"
    private const val KEY_HIGHLIGHT_RULES = "highlight_rules"

    fun load(context: Context): MittSettings {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun enumOf(name: String, fallback: String): String =
            sp.getString(name, fallback) ?: fallback
        return MittSettings(
            proxyMode = runCatching {
                ProxyMode.valueOf(enumOf(KEY_PROXY_MODE, ProxyMode.VPN_TUN.name))
            }.getOrDefault(ProxyMode.VPN_TUN),
            socksEnabled = sp.getBoolean(KEY_SOCKS_ENABLED, false),
            httpPort = sp.getInt(KEY_HTTP_PORT, 9099),
            tunnelPort = sp.getInt(KEY_TUNNEL_PORT, 9098),
            socksPort = sp.getInt(KEY_SOCKS_PORT, 1080),
            upstream = UpstreamProxy(
                enabled = sp.getBoolean(KEY_UPSTREAM_ENABLED, false),
                host = sp.getString(KEY_UPSTREAM_HOST, null)?.takeIf { it.isNotBlank() }
                    ?: "127.0.0.1",
                port = sp.getInt(KEY_UPSTREAM_PORT, 7890).coerceIn(1, 65535),
                username = sp.getString(KEY_UPSTREAM_USER, "").orEmpty(),
                password = sp.getString(KEY_UPSTREAM_PASSWORD, "").orEmpty(),
            ),
            filterMode = runCatching {
                FilterMode.valueOf(enumOf(KEY_FILTER_MODE, FilterMode.OFF.name))
            }.getOrDefault(FilterMode.OFF),
            whitelist = sp.getStringSet(KEY_WHITELIST, emptySet())?.toSet().orEmpty(),
            blacklist = sp.getStringSet(KEY_BLACKLIST, emptySet())?.toSet().orEmpty(),
            domainFilterMode = runCatching {
                FilterMode.valueOf(enumOf(KEY_DOMAIN_FILTER_MODE, FilterMode.OFF.name))
            }.getOrDefault(FilterMode.OFF),
            domainWhitelist = sp.getStringSet(KEY_DOMAIN_WHITELIST, emptySet())?.toSet().orEmpty(),
            domainBlacklist = sp.getStringSet(KEY_DOMAIN_BLACKLIST, emptySet())?.toSet().orEmpty(),
            themeMode = runCatching {
                ThemeMode.valueOf(enumOf(KEY_THEME_MODE, ThemeMode.SYSTEM.name))
            }.getOrDefault(ThemeMode.SYSTEM),
            advancedEffects = sp.getBoolean(KEY_ADVANCED_EFFECTS, true),
            memoryLimitMb = sp.getInt(KEY_MEMORY_LIMIT, 128).coerceIn(16, 2048),
            autoSaveDays = sp.getInt(KEY_AUTO_SAVE_DAYS, 0).coerceIn(0, 30),
            debugMode = sp.getBoolean(KEY_DEBUG_MODE, false),
            autoStartOnLaunch = sp.getBoolean(KEY_AUTO_START_ON_LAUNCH, false),
            certMode = runCatching {
                CertMode.valueOf(enumOf(KEY_CERT_MODE, CertMode.BUILTIN.name))
            }.getOrDefault(CertMode.BUILTIN),
            asyncUploadEnabled = sp.getBoolean(KEY_ASYNC_UPLOAD, false),
            highlightEnabled = sp.getBoolean(KEY_HIGHLIGHT_ENABLED, false),
            highlightRules = parseRules(sp.getString(KEY_HIGHLIGHT_RULES, null)),
        )
    }

    fun save(context: Context, settings: MittSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROXY_MODE, settings.proxyMode.name)
            .putBoolean(KEY_SOCKS_ENABLED, settings.socksEnabled)
            .putInt(KEY_HTTP_PORT, settings.httpPort)
            .putInt(KEY_TUNNEL_PORT, settings.tunnelPort)
            .putInt(KEY_SOCKS_PORT, settings.socksPort)
            .putBoolean(KEY_UPSTREAM_ENABLED, settings.upstream.enabled)
            .putString(KEY_UPSTREAM_HOST, settings.upstream.host)
            .putInt(KEY_UPSTREAM_PORT, settings.upstream.port)
            .putString(KEY_UPSTREAM_USER, settings.upstream.username)
            .putString(KEY_UPSTREAM_PASSWORD, settings.upstream.password)
            .putString(KEY_FILTER_MODE, settings.filterMode.name)
            .putStringSet(KEY_WHITELIST, settings.whitelist)
            .putStringSet(KEY_BLACKLIST, settings.blacklist)
            .putString(KEY_DOMAIN_FILTER_MODE, settings.domainFilterMode.name)
            .putStringSet(KEY_DOMAIN_WHITELIST, settings.domainWhitelist)
            .putStringSet(KEY_DOMAIN_BLACKLIST, settings.domainBlacklist)
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .putBoolean(KEY_ADVANCED_EFFECTS, settings.advancedEffects)
            .putInt(KEY_MEMORY_LIMIT, settings.memoryLimitMb)
            .putInt(KEY_AUTO_SAVE_DAYS, settings.autoSaveDays)
            .putBoolean(KEY_DEBUG_MODE, settings.debugMode)
            .putBoolean(KEY_AUTO_START_ON_LAUNCH, settings.autoStartOnLaunch)
            .putString(KEY_CERT_MODE, settings.certMode.name)
            .putBoolean(KEY_ASYNC_UPLOAD, settings.asyncUploadEnabled)
            .putBoolean(KEY_HIGHLIGHT_ENABLED, settings.highlightEnabled)
            .putString(KEY_HIGHLIGHT_RULES, rulesToJson(settings.highlightRules))
            .apply()
    }

    private fun parseRules(raw: String?): List<HighlightRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val pattern = item.optString("pattern")
                    if (pattern.isEmpty()) continue
                    add(HighlightRule(item.optInt("color"), pattern))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun rulesToJson(rules: List<HighlightRule>): String {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("color", rule.colorArgb)
                    .put("pattern", rule.pattern),
            )
        }
        return array.toString()
    }
}