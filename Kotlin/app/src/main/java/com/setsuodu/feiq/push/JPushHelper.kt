package com.setsuodu.feiq.push

import android.app.Application
import android.content.Context
import android.util.Log
import cn.jpush.android.api.JPushInterface

object JPushHelper {

    private const val TAG = "JPushHelper"

    fun init(app: Application, debug: Boolean = true) {
        JPushInterface.setDebugMode(debug)
        JPushInterface.init(app)
        Log.i(TAG, "JPush init done, registrationId=${getRegistrationId(app)}")
    }

    fun getRegistrationId(context: Context): String {
        return JPushInterface.getRegistrationID(context) ?: ""
    }

    fun setAlias(context: Context, sequence: Int, alias: String) {
        JPushInterface.setAlias(context, sequence, alias)
    }

    fun deleteAlias(context: Context, sequence: Int) {
        JPushInterface.deleteAlias(context, sequence)
    }

    fun setTags(context: Context, sequence: Int, tags: Set<String>) {
        JPushInterface.setTags(context, sequence, tags)
    }
}
