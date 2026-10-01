package com.setsuodu.feiq.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.setsuodu.feiq.MainActivity
import com.setsuodu.feiq.NetworkKeepAlive

/**
 * 前台服务：持有网络/CPU 锁，减轻息屏后 UDP 广播/组播被系统丢掉的问题。
 * 不替代 IpMsgService 协议逻辑，只保活进程与 Wi‑Fi 组播通路。
 */
class FeiqKeepAliveService : Service() {

    private var keepAlive: NetworkKeepAlive? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        keepAlive = NetworkKeepAlive(this).also { it.acquire() }
        Log.i(TAG, "onCreate + locks")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: "udp"
        val notification = buildNotification(mode)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        // 再次确保锁（部分机型 startForeground 前后会丢）
        keepAlive?.acquire()
        return START_STICKY
    }

    override fun onDestroy() {
        keepAlive?.release()
        keepAlive = null
        Log.i(TAG, "onDestroy released")
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "飞秋保活",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持局域网 UDP/组播可收包"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(mode: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (mode == "ws") "中继已连接，后台保持在线" else "局域网 UDP 运行中，防止息屏丢包"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FeiQ 2026")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "FeiqKeepAlive"
        private const val CHANNEL_ID = "feiq_keepalive"
        private const val NOTIF_ID = 2026
        const val EXTRA_MODE = "mode"

        fun start(context: Context, mode: String) {
            val i = Intent(context, FeiqKeepAliveService::class.java).putExtra(EXTRA_MODE, mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FeiqKeepAliveService::class.java))
        }
    }
}
