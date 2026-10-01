package com.setsuodu.feiq

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * 系统「分享到 FeiQ 2026」的待发送文件队列（Compose 可观察）。
 */
object ShareInbox {
    val pending: SnapshotStateList<Uri> = mutableStateListOf()

    fun offerFromIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val list = mutableListOf<Uri>()
        when (action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) list.add(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val us = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (us != null) list.addAll(us)
            }
        }
        if (list.isNotEmpty()) {
            pending.clear()
            pending.addAll(list)
        }
    }

    fun clear() {
        pending.clear()
    }
}
