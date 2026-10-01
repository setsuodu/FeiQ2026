package com.setsuodu.feiq.push

import android.content.Context
import android.util.Log
import cn.jpush.android.api.CustomMessage
import cn.jpush.android.api.NotificationMessage
import cn.jpush.android.service.JPushMessageReceiver

class JPushReceiver : JPushMessageReceiver() {

    companion object {
        private const val TAG = "JPushReceiver"
    }

    override fun onNotifyMessageArrived(context: Context, message: NotificationMessage) {
        Log.i(TAG, "onNotifyMessageArrived: ${message.notificationTitle} / ${message.notificationContent}")
    }

    override fun onNotifyMessageOpened(context: Context, message: NotificationMessage) {
        Log.i(TAG, "onNotifyMessageOpened: ${message.notificationTitle}")
        // TODO: 点击通知后跳转
    }

    override fun onNotifyMessageDismiss(context: Context, message: NotificationMessage) {
        Log.i(TAG, "onNotifyMessageDismiss")
    }

    override fun onMessage(context: Context, customMessage: CustomMessage) {
        Log.i(TAG, "onMessage: ${customMessage.message}")
    }

    override fun onRegister(context: Context, registrationId: String) {
        Log.i(TAG, "onRegister: $registrationId")
    }

    override fun onConnected(context: Context, isConnected: Boolean) {
        Log.i(TAG, "onConnected: $isConnected")
    }
}
