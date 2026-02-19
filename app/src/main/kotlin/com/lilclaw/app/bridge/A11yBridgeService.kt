package com.lilclaw.app.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

class A11yBridgeService : AccessibilityService() {

    private var httpServer: BridgeHttpServer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        httpServer = BridgeHttpServer(7333, this)
        httpServer?.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events handled on-demand via HTTP
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        httpServer?.stop()
        super.onDestroy()
    }

    fun getScreenTree(): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().put("error", "No active window")
        return nodeToJson(root)
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val target = nodes.firstOrNull { it.isClickable } ?: nodes.firstOrNull()
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun clickById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        val target = nodes.firstOrNull()
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private fun nodeToJson(node: AccessibilityNodeInfo, depth: Int = 0): JSONObject {
        val obj = JSONObject()
        obj.put("class", node.className?.toString() ?: "")
        obj.put("text", node.text?.toString() ?: "")
        obj.put("desc", node.contentDescription?.toString() ?: "")
        obj.put("id", node.viewIdResourceName ?: "")
        obj.put("clickable", node.isClickable)
        obj.put("enabled", node.isEnabled)
        obj.put("focused", node.isFocused)

        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        obj.put("bounds", JSONObject().apply {
            put("left", rect.left)
            put("top", rect.top)
            put("right", rect.right)
            put("bottom", rect.bottom)
        })

        if (depth < 15 && node.childCount > 0) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { children.put(nodeToJson(it, depth + 1)) }
            }
            obj.put("children", children)
        }
        return obj
    }

    private class BridgeHttpServer(
        port: Int,
        private val service: A11yBridgeService,
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            return try {
                when {
                    method == Method.GET && uri == "/screen" -> {
                        jsonResponse(service.getScreenTree())
                    }
                    method == Method.POST && uri == "/click" -> {
                        val body = parseBody(session)
                        val text = body.optString("text", "")
                        val id = body.optString("id", "")
                        val success = when {
                            text.isNotEmpty() -> service.clickByText(text)
                            id.isNotEmpty() -> service.clickById(id)
                            else -> false
                        }
                        jsonResponse(JSONObject().put("success", success))
                    }
                    method == Method.POST && uri == "/type" -> {
                        val body = parseBody(session)
                        val text = body.optString("text", "")
                        val success = service.typeText(text)
                        jsonResponse(JSONObject().put("success", success))
                    }
                    method == Method.POST && uri == "/swipe" -> {
                        val body = parseBody(session)
                        val direction = body.optString("direction", "up")
                        val displayMetrics = service.resources.displayMetrics
                        val w = displayMetrics.widthPixels.toFloat()
                        val h = displayMetrics.heightPixels.toFloat()
                        val cx = w / 2f
                        val cy = h / 2f
                        val dist = h * 0.3f
                        when (direction) {
                            "up" -> service.performSwipe(cx, cy + dist, cx, cy - dist)
                            "down" -> service.performSwipe(cx, cy - dist, cx, cy + dist)
                            "left" -> service.performSwipe(cx + dist, cy, cx - dist, cy)
                            "right" -> service.performSwipe(cx - dist, cy, cx + dist, cy)
                        }
                        jsonResponse(JSONObject().put("success", true))
                    }
                    method == Method.POST && uri == "/navigate" -> {
                        val body = parseBody(session)
                        val action = body.optString("action", "back")
                        val success = when (action) {
                            "back" -> service.goBack()
                            "home" -> service.goHome()
                            "recents" -> service.goRecents()
                            else -> false
                        }
                        jsonResponse(JSONObject().put("success", success))
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
            val body = files["postData"] ?: "{}"
            return JSONObject(body)
        }

        private fun jsonResponse(
            json: JSONObject,
            status: Response.Status = Response.Status.OK,
        ): Response {
            return newFixedLengthResponse(status, "application/json", json.toString())
        }
    }
}
