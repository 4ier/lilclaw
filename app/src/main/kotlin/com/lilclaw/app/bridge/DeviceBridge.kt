package com.lilclaw.app.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Device Bridge: exposes phone hardware capabilities to the gateway agent via HTTP.
 * Runs on port 7334, called by agent in proot via curl http://localhost:7334/...
 */
class DeviceBridge(private val context: Context) {

    private var server: DeviceBridgeServer? = null

    fun start() {
        server = DeviceBridgeServer(7334, context)
        server?.start()
    }

    fun stop() {
        server?.stop()
    }

    private class DeviceBridgeServer(
        port: Int,
        private val context: Context,
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            return try {
                when {
                    method == Method.GET && uri == "/clipboard" -> {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        jsonResponse(JSONObject().put("text", text))
                    }
                    method == Method.POST && uri == "/clipboard" -> {
                        val body = parseBody(session)
                        val text = body.optString("text", "")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("LilClaw", text))
                        jsonResponse(JSONObject().put("success", true))
                    }
                    method == Method.GET && uri == "/location" -> {
                        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        try {
                            @Suppress("MissingPermission")
                            val location = locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                                ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                            if (location != null) {
                                jsonResponse(JSONObject().apply {
                                    put("lat", location.latitude)
                                    put("lng", location.longitude)
                                    put("accuracy", location.accuracy)
                                    put("time", location.time)
                                })
                            } else {
                                jsonResponse(JSONObject().put("error", "No location available"))
                            }
                        } catch (e: SecurityException) {
                            jsonResponse(JSONObject().put("error", "Location permission not granted"), Response.Status.FORBIDDEN)
                        }
                    }
                    method == Method.POST && uri == "/intent" -> {
                        val body = parseBody(session)
                        val action = body.optString("action", Intent.ACTION_VIEW)
                        val uriStr = body.optString("uri", "")
                        val pkg = body.optString("package", "")
                        val intent = Intent(action).apply {
                            if (uriStr.isNotEmpty()) data = Uri.parse(uriStr)
                            if (pkg.isNotEmpty()) setPackage(pkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        jsonResponse(JSONObject().put("success", true))
                    }
                    method == Method.GET && uri == "/info" -> {
                        jsonResponse(JSONObject().apply {
                            put("device", android.os.Build.MODEL)
                            put("manufacturer", android.os.Build.MANUFACTURER)
                            put("sdk", android.os.Build.VERSION.SDK_INT)
                            put("app_version", "0.1.0")
                        })
                    }
                    else -> {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                    }
                }
            } catch (e: Exception) {
                jsonResponse(JSONObject().put("error", e.message), Response.Status.INTERNAL_ERROR)
            }
        }

        private fun parseBody(session: IHTTPSession): JSONObject {
            val files = HashMap<String, String>()
            session.parseBody(files)
            return JSONObject(files["postData"] ?: "{}")
        }

        private fun jsonResponse(
            json: JSONObject,
            status: Response.Status = Response.Status.OK,
        ): Response {
            return newFixedLengthResponse(status, "application/json", json.toString())
        }
    }
}
