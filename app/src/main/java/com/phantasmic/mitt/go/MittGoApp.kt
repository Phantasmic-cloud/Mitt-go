package com.phantasmic.mitt.go

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MittGoApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppIdentityResolver.init(this)
        val settings = AppSettings.load(this)
        SettingsRuntime.applyInitial(settings)
        CaptureStore.attach(this)
        AsyncUploadRuntime.attach(this)
        CaptureStore.startPolling(appScope)
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val text = buildCrashReport(thread, error)
            Log.e("MittGoCrash", text, error)
            runCatching {
                File(filesDir, "last-crash.txt").writeText(text, Charsets.UTF_8)
            }
            runCatching {
                val dir = getExternalFilesDir("logs")
                dir?.mkdirs()
                File(dir, "last-crash.txt").writeText(text, Charsets.UTF_8)
            }

            val inCrashProcess = runCatching {
                Application.getProcessName().endsWith(":crash")
            }.getOrDefault(false)
            if (!inCrashProcess) {
                runCatching {
                    startActivity(
                        Intent(this, CrashActivity::class.java)
                            .putExtra(CrashActivity.EXTRA_REPORT, text.take(100_000))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                }
                // Let the separate :crash process render the report before the
                // broken main process is terminated.
                runCatching { Thread.sleep(700) }
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(1)
            }
        }
    }

    private fun buildCrashReport(thread: Thread, error: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        val time = TimeFmt.stampDefault(System.currentTimeMillis())
        return buildString {
            appendLine("Mitt-go ${BuildConfig.VERSION_NAME} 异常")
            appendLine("时间: $time")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("线程: ${thread.name}")
            appendLine()
            append(sw.toString())
        }
    }
}