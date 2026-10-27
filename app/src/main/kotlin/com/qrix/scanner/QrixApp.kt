package com.qrix.scanner

import android.app.Application

class QrixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
    }
}
