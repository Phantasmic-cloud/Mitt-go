package com.phantasmic.mitt.go

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import android.system.OsConstants
import android.util.Log
import java.net.InetSocketAddress

data class AppIdentity(val uid: Int, val packageName: String)

/**
 * Resolves the Android UID/package that owned the socket which produced a TUN
 * packet. This uses the same ConnectivityManager owner-UID lookup that Mitt
 * used for its per-request app attribution.
 */
object AppIdentityResolver {
    private const val TAG = "MittGoAppIdentity"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun resolve(
        sourceIp: String,
        sourcePort: Int,
        destinationIp: String,
        destinationPort: Int,
    ): AppIdentity? {
        val context = appContext ?: return null
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val local = InetSocketAddress(sourceIp, sourcePort)
            val remote = InetSocketAddress(destinationIp, destinationPort)
            val uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, local, remote)
            if (uid == Process.INVALID_UID || uid < 0) return null
            val packageName = context.packageManager.getPackagesForUid(uid)
                ?.firstOrNull()
                ?: return null
            AppIdentity(uid, packageName)
        } catch (t: Throwable) {
            Log.w(TAG, "resolve app identity failed", t)
            null
        }
    }

    fun labelFor(context: Context, packageName: String): String {
        if (packageName.isBlank() || packageName == "-") return "未知应用"
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}