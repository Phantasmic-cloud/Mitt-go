package com.phantasmic.mitt.go

import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/** 证书现状检测：内置 CA 是否已装进系统库、免 root 用的根是否仍被信任。 */
object CertStatus {
    private val BUILTIN_PATHS = listOf(
        "/system/etc/security/cacerts/7dd0b8dd.0",
        "/apex/com.android.conscrypt/cacerts/7dd0b8dd.0",
        "/data/misc/user/0/cacerts-added/7dd0b8dd.0",
        "/data/misc/keychain/cacerts-added/7dd0b8dd.0",
    )

    /**
     * 内置 CA 是否已装入当前系统。
     * 注意：App 装在真机、证书装在虚拟机时这里会显示未安装，属正常现象。
     */
    fun builtinInstalled(): Boolean =
        BUILTIN_PATHS.any { path -> runCatching { File(path).exists() }.getOrDefault(false) }

    /**
     * 印度证书模式是否可用：它用 AAA 交叉签名的 eMudhra 中间证书签发，
     * 所以设备必须还信任 AAA Certificate Services；emSign ECC Root CA - G3
     * 上的交叉链同样有效。
     */
    fun indiaAvailable(): Boolean {
        val names = systemRootSubjects()
        return names.any { dn ->
            val lower = dn.lowercase()
            lower.contains("aaa certificate services") || lower.contains("emsign ecc root ca - g3")
        }
    }

    fun systemRootSubjects(): List<String> = runCatching {
        val store = KeyStore.getInstance("AndroidCAStore")
        store.load(null)
        val out = ArrayList<String>()
        val aliases = store.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val cert = runCatching { store.getCertificate(alias) as? X509Certificate }.getOrNull()
                ?: continue
            out += cert.subjectX500Principal.name
        }
        out
    }.getOrDefault(emptyList())
}