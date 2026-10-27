package io.github.webtvremote.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import io.github.webtvremote.App
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Turns remote commands into actions on the TV, trying the best available backend first:
 * ADB injector -> ADB shell -> accessibility service -> plain Android APIs.
 */
class InputController(private val context: Context, private val adb: AdbBridge) {

    enum class Result { OK, NEEDS_ADB, FAILED }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    fun key(name: String, mode: String): Result {
        if (name == "settings") return startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val code = KEYS[name] ?: return Result.FAILED
        if (adb.connected && adb.key(code, mode)) return Result.OK
        if (mode == "up") return Result.OK
        val a11y = RemoteAccessibilityService.instance
        if (a11y != null) {
            if (name == "enter" && a11y.imeEnter()) return Result.OK
            if (a11y.key(name)) return Result.OK
        }
        when (name) {
            "volume_up" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            else -> return Result.NEEDS_ADB
        }
        return Result.OK
    }

    data class Volume(val level: Int, val max: Int, val fixed: Boolean)

    /** Media volume. [Volume.fixed] means the box cannot change it (e.g. HDMI with fixed output). */
    fun volume() = Volume(
        audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        audio.isVolumeFixed,
    )

    fun setVolume(level: Int): Volume {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
        return volume()
    }

    /**
     * The web keyboard mirrors its whole input field. [full] is the new content; [deleteCount] and
     * [append] describe the same change as an edit at the end of the field, for key-based backends.
     */
    fun text(full: String, deleteCount: Int, append: String): Result {
        RemoteAccessibilityService.instance?.let { if (it.setText(full)) return Result.OK }
        if (!adb.connected) return Result.NEEDS_ADB
        repeat(deleteCount.coerceAtMost(500)) { adb.key(KeyEvent.KEYCODE_DEL, "click") }
        if (append.isEmpty() || adb.text(append)) return Result.OK
        // Non-ASCII (e.g. Cyrillic) cannot be typed with key events: paste it instead.
        return if (setClipboard(append) && adb.key(KeyEvent.KEYCODE_PASTE, "click")) Result.OK else Result.FAILED
    }

    fun launch(pkg: String): Result {
        val intent = App.of(context).apps.launchIntent(pkg) ?: return Result.FAILED
        return startActivity(intent)
    }

    private fun startActivity(intent: Intent): Result {
        // Background activity starts are allowed for apps holding the overlay permission.
        // Without it the start is silently blocked, so prefer `am start` through ADB.
        if (Settings.canDrawOverlays(context) || !adb.connected) {
            return if (runCatching { context.startActivity(intent) }.isSuccess) Result.OK else Result.FAILED
        }
        val component = intent.component?.flattenToShortString()
        val cmd = if (component != null) "am start -n $component" else "am start -a ${intent.action}"
        return if (adb.shell(cmd)) Result.OK else Result.FAILED
    }

    private fun setClipboard(text: String): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        main.post {
            ok = runCatching {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("remote", text))
            }.isSuccess
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        return ok
    }

    companion object {
        val KEYS = mapOf(
            "power" to KeyEvent.KEYCODE_POWER,
            "up" to KeyEvent.KEYCODE_DPAD_UP,
            "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "ok" to KeyEvent.KEYCODE_DPAD_CENTER,
            "back" to KeyEvent.KEYCODE_BACK,
            "home" to KeyEvent.KEYCODE_HOME,
            "menu" to KeyEvent.KEYCODE_MENU,
            "volume_up" to KeyEvent.KEYCODE_VOLUME_UP,
            "volume_down" to KeyEvent.KEYCODE_VOLUME_DOWN,
            "mute" to KeyEvent.KEYCODE_VOLUME_MUTE,
            "enter" to KeyEvent.KEYCODE_ENTER,
            "del" to KeyEvent.KEYCODE_DEL,
            "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )
    }
}
