package com.setsuodu.feiq

import android.app.Application
import com.setsuodu.feiq.push.JPushHelper

class FeiQApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        JPushHelper.init(this, debug = true)
    }
}
