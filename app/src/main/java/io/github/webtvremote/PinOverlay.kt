package io.github.webtvremote

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Shows the pairing PIN on top of whatever is on screen. */
class PinOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var shownPin: String? = null

    fun update(pin: String?) {
        if (pin == shownPin) return
        shownPin = pin
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        if (pin == null) return
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, context.getString(R.string.pin_toast, pin), Toast.LENGTH_LONG).show()
            return
        }
        val v = build(pin)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        runCatching { wm.addView(v, params); view = v }
    }

    private fun build(pin: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(32), dp(20), dp(32), dp(24))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xF0141821.toInt())
            setStroke(dp(2), 0xFF4F8CFF.toInt())
        }
        addView(TextView(context).apply {
            text = context.getString(R.string.pin_overlay_title)
            setTextColor(0xFFB8C0D0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        addView(TextView(context).apply {
            text = pin.toCharArray().joinToString(" ")
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
