package io.github.webtvremote.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import io.github.webtvremote.App
import io.github.webtvremote.BuildConfig
import io.github.webtvremote.R
import io.github.webtvremote.RemoteState
import io.github.webtvremote.input.Apps
import io.github.webtvremote.input.InputController
import io.github.webtvremote.input.InputController.Result
import io.github.webtvremote.input.RemoteAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class WebServer(private val context: Context, port: Int) : NanoHTTPD(port) {
    private val app = App.of(context)
    private val iconCache = HashMap<Int, ByteArray>()

    override fun serve(session: IHTTPSession): Response = try {
        if (!hostAllowed(session.headers["host"])) {
            text(Response.Status.FORBIDDEN, "Forbidden host")
        } else if (session.uri.startsWith("/api/")) {
            api(session)
        } else {
            static(session.uri)
        }
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.toString()))
    }.also {
        it.addHeader("X-Content-Type-Options", "nosniff")
    }

    // --- static files -----------------------------------------------------------------------

    private fun static(uri: String): Response {
        val pngSize = when (uri) {
            "/icon-192.png" -> 192
            "/icon-512.png" -> 512
            "/apple-touch-icon.png" -> 180
            else -> 0
        }
        if (pngSize > 0) {
            val bytes = synchronized(iconCache) {
                iconCache.getOrPut(pngSize) { Apps.toPng(context.getDrawable(R.drawable.web_icon)!!, pngSize) }
            }
            return bytes(bytes, "image/png").apply { addHeader("Cache-Control", "max-age=86400") }
        }
        val path = if (uri == "/") "index.html" else uri.trimStart('/')
        if (path.contains("..") || path !in STATIC) return text(Response.Status.NOT_FOUND, "Not found")
        val bytes = context.assets.open("web/$path").use { it.readBytes() }
        return bytes(bytes, STATIC.getValue(path)).apply { addHeader("Cache-Control", "no-cache") }
    }

    // --- API --------------------------------------------------------------------------------

    private fun api(session: IHTTPSession): Response {
        val route = "${session.method} ${session.uri}"
        val authorized = app.pairing.isAuthorized(session.cookies.read(COOKIE))

        when (route) {
            "GET /api/status" -> return json(status(authorized))
            "POST /api/pair/request" -> {
                app.pairing.request()
                return json(JSONObject().put("ok", true))
            }
            "POST /api/pair/confirm" -> {
                val body = body(session)
                val name = body.optString("name").ifBlank { session.headers["user-agent"].orEmpty() }
                return when (val r = app.pairing.confirm(body.optString("pin"), name)) {
                    is Pairing.Outcome.Paired -> json(JSONObject().put("ok", true)).apply {
                        // 10 years; HttpOnly so page scripts never see the token.
                        addHeader("Set-Cookie", "$COOKIE=${r.token}; Max-Age=315360000; Path=/; HttpOnly; SameSite=Strict")
                    }
                    is Pairing.Outcome.WrongPin -> {
                        Thread.sleep(1000) // slow down guessing
                        json(Response.Status.FORBIDDEN, JSONObject().put("error", "wrong_pin").put("attemptsLeft", r.attemptsLeft))
                    }
                    Pairing.Outcome.Expired -> json(Response.Status.FORBIDDEN, JSONObject().put("error", "expired"))
                }
            }
        }

        if (!authorized) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "not_paired"))

        return when (route) {
            "POST /api/key" -> {
                val body = body(session)
                result(app.input.key(body.getString("key"), body.optString("mode", "click")))
            }
            "POST /api/text" -> {
                val body = body(session)
                result(app.input.text(body.optString("full"), body.optInt("deleteCount"), body.optString("append")))
            }
            "POST /api/launch" -> result(app.input.launch(body(session).getString("pkg")))
            "GET /api/apps" -> json(JSONArray().apply {
                app.apps.list().forEach { put(JSONObject().put("pkg", it.pkg).put("label", it.label)) }
            })
            "GET /api/apps/icon" -> {
                val pkg = session.parameters["pkg"]?.firstOrNull().orEmpty()
                val png = app.apps.iconPng(pkg) ?: return text(Response.Status.NOT_FOUND, "No icon")
                bytes(png, "image/png").apply { addHeader("Cache-Control", "max-age=604800") }
            }
            "GET /api/volume" -> json(volume(app.input.volume()))
            "POST /api/volume" -> json(volume(app.input.setVolume(body(session).getInt("level"))))
            "GET /api/favorites" -> json(favorites())
            "POST /api/favorites" -> {
                val arr = body(session).getJSONArray("pkgs")
                app.prefs.favorites = (0 until arr.length()).map { arr.getString(it) }.distinct()
                json(favorites())
            }
            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "not_found"))
        }
    }

    private fun status(authorized: Boolean) = JSONObject()
        .put("paired", authorized)
        .put("device", app.deviceName)
        .put("version", BuildConfig.VERSION_NAME)
        .put("nameUrl", RemoteState.nameUrl ?: JSONObject.NULL)
        .apply {
            if (authorized) {
                put("adb", RemoteState.adb.name.lowercase())
                put("injector", RemoteState.injectorReady)
                put("accessibility", RemoteAccessibilityService.instance != null)
            }
        }

    private fun volume(v: InputController.Volume) =
        JSONObject().put("level", v.level).put("max", v.max).put("fixed", v.fixed)

    private fun favorites(): JSONArray {
        val pkgs = app.prefs.favorites ?: app.apps.defaultFavorites().also { app.prefs.favorites = it }
        return JSONArray().apply {
            pkgs.forEach { pkg -> app.apps.label(pkg)?.let { put(JSONObject().put("pkg", pkg).put("label", it)) } }
        }
    }

    private fun result(r: Result) = when (r) {
        Result.OK -> json(JSONObject().put("ok", true))
        Result.NEEDS_ADB -> json(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("error", "adb_required"))
        Result.FAILED -> json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "failed"))
    }

    private fun body(session: IHTTPSession): JSONObject {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return JSONObject()
        require(len <= MAX_BODY) { "body too large" }
        val buf = ByteArray(len)
        DataInputStream(session.inputStream).readFully(buf)
        return JSONObject(String(buf, Charsets.UTF_8))
    }

    /** Blocks DNS-rebinding: only accept requests addressed to an IP, localhost or a .local name. */
    private fun hostAllowed(host: String?): Boolean {
        if (host == null) return true
        val name = host.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        return name == "localhost" || name.endsWith(".local") || host.startsWith("[") ||
            name.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    }

    private fun json(obj: Any) = json(Response.Status.OK, obj)
    private fun json(status: Response.Status, obj: Any): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
            .apply { addHeader("Cache-Control", "no-store") }

    private fun text(status: Response.Status, msg: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    private fun bytes(data: ByteArray, mime: String): Response =
        newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(data), data.size.toLong())

    companion object {
        private const val COOKIE = "wtr_token"
        private const val MAX_BODY = 64 * 1024
        private val STATIC = mapOf(
            "index.html" to "text/html; charset=utf-8",
            "app.css" to "text/css; charset=utf-8",
            "app.js" to "text/javascript; charset=utf-8",
            "sw.js" to "text/javascript; charset=utf-8",
            "manifest.webmanifest" to "application/manifest+json",
            "icon.svg" to "image/svg+xml",
        )
    }
}
