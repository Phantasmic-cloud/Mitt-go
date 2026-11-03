package com.phantasmic.mitt.go

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CertificateExporter {
    private const val HASH = "7dd0b8dd"
    private const val SUBDIR = "Mitt-go"

    fun exportSystemCert(context: Context): String {
        val pem = readAsset(context, "ca/ca.crt")
        val name = "$HASH.0"
        saveDownload(context, name, "application/octet-stream", pem.toByteArray())
        return "Download/$SUBDIR/$name"
    }

    /** 导出 .sh 一键安装脚本（证书已内嵌，su 执行即可）。 */
    fun exportInstallScript(context: Context): String {
        val pem = readAsset(context, "ca/ca.crt").trim()
        val b64 = android.util.Base64.encodeToString(
            pem.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val script = buildInstallScript(b64)
        val name = "mitt-go-cacert-install.sh"
        saveDownload(context, name, "text/x-shellscript", script.toByteArray(Charsets.UTF_8))
        return "Download/$SUBDIR/$name"
    }

    fun exportKernelSuModule(context: Context): String {
        val certPem = readAsset(context, "ca/ca.crt")
        val sha = certDerSha256(certPem)
        val bytes = buildModule(context, certPem, sha)
        val name = "Mitt-go-CA-KernelSU-${BuildConfig.VERSION_NAME}.zip"
        saveDownload(context, name, "application/zip", bytes)
        return "Download/$SUBDIR/$name"
    }


    private fun buildInstallScript(b64: String): String = """#!/system/bin/sh
# Mitt-go 固定 CA 安装脚本（需要 root）
# 用法：su -c 'sh mitt-go-cacert-install.sh'
# 证书：7dd0b8dd.0（固定 Mitt CA，不随版本变化）

if [ "${'$'}(id -u)" -ne 0 ]; then
  echo "需要 root：su -c 'sh ${'$'}0'"
  exit 1
fi

CERT=/data/local/tmp/7dd0b8dd.0
base64 -d > "${'$'}CERT" <<'MITT_CA_B64'
@@B64@@
MITT_CA_B64
chmod 644 "${'$'}CERT"

SDK=${'$'}(getprop ro.build.version.sdk)
case "${'$'}SDK" in
  ''|*[!0-9]*) SDK=0 ;;
esac

if [ "${'$'}SDK" -le 33 ]; then
  echo "[1/3] Android ${'$'}SDK：写入 /system/etc/security/cacerts"
  mount -o rw,remount /system 2>/dev/null || mount -o rw,remount / 2>/dev/null
  if [ -f "/system/etc/security/cacerts/7dd0b8dd.0" ]; then
    cp -f "/system/etc/security/cacerts/7dd0b8dd.0" "/data/local/tmp/7dd0b8dd.0.bak"
    echo "      已备份原证书到 /data/local/tmp/7dd0b8dd.0.bak"
  fi
  cp -f "${'$'}CERT" "/system/etc/security/cacerts/7dd0b8dd.0" || {
    echo "写入失败：系统分区不可写，请检查 root 或改用 KernelSU 模块"
    exit 1
  }
  chmod 644 "/system/etc/security/cacerts/7dd0b8dd.0"
  chown 0:0 "/system/etc/security/cacerts/7dd0b8dd.0" 2>/dev/null
  echo "[2/3] 证书已写入"
  echo "[3/3] 完成：重启手机后生效"
  exit 0
fi

echo "[1/3] Android ${'$'}SDK：系统证书在 APEX 里，尝试临时覆盖"
APEX=/apex/com.android.conscrypt/cacerts
STAGE=/data/local/tmp/mittgo-cacerts
rm -rf "${'$'}STAGE"
mkdir -p "${'$'}STAGE"
cp -f "${'$'}APEX"/* "${'$'}STAGE"/ 2>/dev/null
cp -f "${'$'}CERT" "${'$'}STAGE/7dd0b8dd.0"
chmod 644 "${'$'}STAGE"/* 2>/dev/null
mount -t tmpfs tmpfs "${'$'}APEX" 2>/dev/null || {
  echo "无法覆盖 ${'$'}APEX"
  echo "请改用导出的 KernelSU 模块，或手动挂载后重启手机"
  exit 1
}
cp -f "${'$'}STAGE"/* "${'$'}APEX"/ 2>/dev/null
chmod 644 "${'$'}APEX"/* 2>/dev/null
echo "[2/3] 已临时覆盖 APEX"
echo "[3/3] 注意：这种方式重启后会失效，长期使用请改用 KernelSU 模块"
""".replace("@@B64@@", b64)

    private fun buildModule(context: Context, certPem: String, sha256: String): ByteArray {
        val assets = context.assets.list("module").orEmpty()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name)
                entry.time = 0
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }

            assets.forEach { name ->
                val template = readAsset(context, "module/$name")
                val script = template
                    .replace("__HASH__", HASH)
                    .replace("__SHA256__", sha256)
                put(name, script.toByteArray(Charsets.UTF_8))
            }
            val moduleProp = buildString {
                appendLine("id=mitt_go_ca")
                appendLine("name=Mitt-go CA (APEX)")
                appendLine("version=${BuildConfig.VERSION_NAME}")
                appendLine("versionCode=${BuildConfig.VERSION_CODE}")
                appendLine("author=Phantasmic")
                appendLine("description=Mitt CA $HASH ($sha256); reboot required")
            }
            put("module.prop", moduleProp.toByteArray(Charsets.UTF_8))
            put(
                "README.txt",
                """
                Mitt-go 证书模块（KernelSU / Magisk 通用 cacerts 挂载格式）
                证书文件：$HASH.0
                SHA-256: $sha256
                模块只包含公开证书，不包含私钥。
                安装后重启；禁用或卸载后也必须重启才能移除挂载。
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            put("system/etc/security/cacerts/$HASH.0", certPem.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    private fun certDerSha256(pem: String): String {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.byteInputStream()) as X509Certificate
        return MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
    }

    private fun saveDownload(
        context: Context,
        displayName: String,
        mime: String,
        bytes: ByteArray,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建下载文件")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入下载文件")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}