package com.alivpn.app

import android.app.Application
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.util.Locale

class AliVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_"))
            val base = filesDir.apply { mkdirs() }
            val working = (getExternalFilesDir(null) ?: filesDir).apply { mkdirs() }
            val temp = cacheDir.apply { mkdirs() }
            Libbox.setup(SetupOptions().also {
                it.basePath = base.absolutePath
                it.workingPath = working.absolutePath
                it.tempPath = temp.absolutePath
                it.fixAndroidStack = true
                it.logMaxLines = 2000
                it.debug = BuildConfig.DEBUG
            })
            Libbox.redirectStderr(File(working, "libbox-stderr.log").absolutePath)
        } catch (t: Throwable) {
            Log.e("AliVPN", "libbox setup failed", t)
        }
    }
}
