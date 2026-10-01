package minifeiq

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * 飞秋 UDP/组播保活：持有 MulticastLock + WifiLock + Partial WakeLock。
 * 必须在前台服务里调用，否则息屏/Doze 后组播收不到。
 */
class NetworkKeepAlive(context: Context) {
    private val app = context.applicationContext
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire() {
        try {
            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (multicastLock == null) {
                multicastLock = wifi.createMulticastLock("feiq2026-multicast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "MulticastLock acquired")
            }
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                wifiLock = wifi.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "feiq2026-wifi"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "WifiLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifi/multicast lock failed: ${e.message}")
        }
        try {
            if (wakeLock == null) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "feiq2026:udp"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "Partial WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakelock failed: ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        multicastLock = null
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        wifiLock = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        wakeLock = null
        Log.i(TAG, "locks released")
    }

    companion object {
        private const val TAG = "FeiqKeepAlive"
    }
}
