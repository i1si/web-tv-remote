package io.github.webtvremote

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.webtvremote.input.AdbBridge
import io.github.webtvremote.input.Apps
import io.github.webtvremote.input.InputController
import io.github.webtvremote.server.Pairing

class App : Application() {
    val prefs by lazy { Prefs(this) }
    val adb by lazy { AdbBridge(this) }
    val apps by lazy { Apps(this) }
    val input by lazy { InputController(this, adb) }
    val pairing by lazy { Pairing(prefs) }

    /** The name the user gave the box in Android settings, or its model. */
    val deviceName: String
        get() = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    companion object {
        fun of(context: Context) = context.applicationContext as App
    }
}
