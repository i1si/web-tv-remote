package io.github.webtvremote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import io.github.webtvremote.RemoteState.Adb
import io.github.webtvremote.input.RemoteAccessibilityService

/** TV screen: status and setup on the left, the link / QR code to open on a phone on the right. */
class MainActivity : Activity() {
    private val app by lazy { App.of(this) }
    private lateinit var url: TextView
    private lateinit var qr: ImageView
    private lateinit var pinPanel: View
    private lateinit var pin: TextView
    private lateinit var adbStatus: TextView
    private lateinit var adbHint: View
    private lateinit var a11yStatus: TextView
    private lateinit var a11yHint: View
    private lateinit var btnDev: Button
    private lateinit var btnA11y: Button
    private lateinit var btnForget: Button
    private var qrFor: String? = null

    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        url = findViewById(R.id.url)
        qr = findViewById(R.id.qr)
        pinPanel = findViewById(R.id.pin_panel)
        pin = findViewById(R.id.pin)
        adbStatus = findViewById(R.id.adb_status)
        adbHint = findViewById(R.id.adb_hint)
        a11yStatus = findViewById(R.id.a11y_status)
        a11yHint = findViewById(R.id.a11y_hint)
        btnDev = findViewById(R.id.btn_dev)
        btnA11y = findViewById(R.id.btn_a11y)

        btnDev.setOnClickListener { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        btnA11y.setOnClickListener { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btnForget = findViewById(R.id.btn_forget)
        btnForget.setOnClickListener {
            app.prefs.clearClients()
            Toast.makeText(this, R.string.forgotten, Toast.LENGTH_SHORT).show()
        }

        RemoteService.start(this)
    }

    override fun onResume() {
        super.onResume()
        RemoteState.addListener(listener)
        // Coming back from Developer options (or just opening the app) retries ADB right away
        // instead of waiting for the next background attempt. Not while a prompt is pending.
        if (RemoteState.adb == Adb.UNAVAILABLE) app.adb.ensureConnected()
        render()
    }

    override fun onPause() {
        RemoteState.removeListener(listener)
        super.onPause()
    }

    private fun render() {
        // The .local name survives IP changes, so both the text and the QR code use it. The IP is
        // only a fallback for the moment before the name is announced (or if mDNS fails).
        val link = RemoteState.url?.let { RemoteState.nameUrl ?: it }
        url.text = link ?: getString(R.string.no_network)
        if (link != qrFor) {
            qrFor = link
            qr.setImageBitmap(link?.let { Qr.bitmap(it, 512) })
        }

        // Normally the PIN is shown by the overlay; this panel is the fallback without it.
        val p = RemoteState.pin?.takeUnless { Settings.canDrawOverlays(this) }
        pinPanel.visibility = if (p != null) View.VISIBLE else View.GONE
        pin.text = p?.toCharArray()?.joinToString(" ")

        val adb = RemoteState.adb
        when (adb) {
            Adb.CONNECTING -> status(adbStatus, R.color.warn, R.string.adb_connecting)
            Adb.WAITING_AUTH -> status(adbStatus, R.color.warn, R.string.adb_waiting)
            Adb.CONNECTED -> if (RemoteState.injectorReady) {
                status(adbStatus, R.color.good, R.string.adb_connected)
            } else {
                status(adbStatus, R.color.warn, R.string.adb_connected_slow)
            }
            Adb.UNAVAILABLE -> status(adbStatus, R.color.bad, R.string.adb_unavailable)
        }
        val noAdb = adb == Adb.UNAVAILABLE
        adbHint.visible(noAdb)
        btnDev.visible(noAdb)

        val a11yOn = RemoteAccessibilityService.instance != null
        if (a11yOn) status(a11yStatus, R.color.good, R.string.a11y_on) else status(a11yStatus, R.color.text_dim, R.string.a11y_off)
        // With ADB the service is switched on automatically; only without it the user has to do it.
        a11yHint.visible(!a11yOn && noAdb)
        btnA11y.visible(!a11yOn && noAdb)

        // Keep something focused for the D-pad, also when the focused button was just hidden.
        if (currentFocus?.isShown != true) listOf(btnDev, btnA11y, btnForget).firstOrNull { it.isShown }?.requestFocus()
    }

    private fun status(view: TextView, color: Int, text: Int) {
        val s = SpannableString("●  " + getString(text))
        s.setSpan(ForegroundColorSpan(getColor(color)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.text = s
    }

    private fun View.visible(show: Boolean) {
        visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
