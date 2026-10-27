package io.github.webtvremote.input

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

class Apps(private val context: Context) {
    data class AppInfo(val pkg: String, val label: String)

    private val iconCache = ConcurrentHashMap<String, ByteArray>()

    private fun launcherActivities(category: String) =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    fun list(): List<AppInfo> {
        val pm = context.packageManager
        return (launcherActivities(Intent.CATEGORY_LEANBACK_LAUNCHER) + launcherActivities(Intent.CATEGORY_LAUNCHER))
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    /** Label of the launcher entry, so it matches [list]; null if the app is gone. */
    fun label(pkg: String): String? {
        val pm = context.packageManager
        val intent = launchIntent(pkg) ?: return null
        return pm.resolveActivity(intent, 0)?.loadLabel(pm)?.toString()
    }

    fun launchIntent(pkg: String): Intent? {
        val pm = context.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    /** A sensible initial favourites list: well-known TV apps that are installed, else the first few. */
    fun defaultFavorites(): List<String> {
        val installed = list().map { it.pkg }
        val known = KNOWN.filter { it in installed }
        return (known + installed).distinct().take(6)
    }

    fun iconPng(pkg: String): ByteArray? = iconCache[pkg] ?: runCatching {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        toPng(drawable, 128).also { iconCache[pkg] = it }
    }.getOrNull()

    companion object {
        private val KNOWN = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.google.android.youtube.tvmusic",
            "com.spotify.tv.android",
            "com.plexapp.android",
            "org.jellyfin.androidtv",
            "org.xbmc.kodi",
            "com.google.android.videos",
            "ru.kinopoisk.tv",
            "com.yandex.tv.home",
            "ru.ivi.client",
            "ru.more.play",
            "com.android.vending",
        )

        fun toPng(drawable: Drawable, size: Int): ByteArray {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bmp))
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
