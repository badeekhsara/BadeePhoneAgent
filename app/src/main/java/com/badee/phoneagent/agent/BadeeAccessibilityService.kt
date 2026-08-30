package com.badee.phoneagent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.badee.phoneagent.BuildConfig
import com.badee.phoneagent.audit.AuditLog
import com.badee.phoneagent.protocol.AgentCommand
import com.badee.phoneagent.protocol.AgentResult
import com.badee.phoneagent.security.AuthTokenStore
import com.badee.phoneagent.server.LocalCommandServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BadeeAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var commandServer: LocalCommandServer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        commandServer = LocalCommandServer(
            port = BuildConfig.COMMAND_PORT,
            tokenStore = AuthTokenStore(this),
            auditLog = AuditLog(this),
            execute = ::executeCommand,
        ).also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        commandServer?.stop()
        commandServer = null
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    private fun executeCommand(command: AgentCommand): AgentResult = when (command.action) {
        "status" -> AgentResult.success(
            command.id,
            "Agent is ready",
            JSONObject()
                .put("service", "running")
                .put("port", BuildConfig.COMMAND_PORT)
                .put("package", currentPackageName()),
        )

        "back" -> global(command, GLOBAL_ACTION_BACK)
        "home" -> global(command, GLOBAL_ACTION_HOME)
        "recents" -> global(command, GLOBAL_ACTION_RECENTS)
        "notifications" -> global(command, GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> global(command, GLOBAL_ACTION_QUICK_SETTINGS)
        "tap" -> tap(command, longPress = false)
        "long_press" -> tap(command, longPress = true)
        "swipe" -> swipe(command)
        "type_text" -> typeText(command)
        "click_text" -> clickText(command)
        "scroll_forward" -> scroll(command, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        "scroll_backward" -> scroll(command, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        "open_app" -> openApp(command)
        "screen_tree" -> screenTree(command)
        "screenshot" -> screenshot(command)
        else -> AgentResult.failure(command.id, "Unsupported action: ${command.action}")
    }

    private fun global(command: AgentCommand, action: Int): AgentResult = onMain(command.id) {
        if (performGlobalAction(action)) {
            AgentResult.success(command.id, "Global action performed")
        } else {
            AgentResult.failure(command.id, "Global action was rejected")
        }
    }

    private fun tap(command: AgentCommand, longPress: Boolean): AgentResult {
        val x = command.args.requireCoordinate("x")
        val y = command.args.requireCoordinate("y")
        val duration = if (longPress) {
            command.args.optLong("duration_ms", 800).coerceIn(500, 3_000)
        } else {
            command.args.optLong("duration_ms", 80).coerceIn(50, 500)
        }

        return onMain(command.id) {
            val metrics = resources.displayMetrics
            if (x !in 0f..metrics.widthPixels.toFloat() || y !in 0f..metrics.heightPixels.toFloat()) {
                return@onMain AgentResult.failure(command.id, "Coordinates are outside the screen")
            }
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            if (dispatchGesture(gesture, null, null)) {
                AgentResult.success(command.id, if (longPress) "Long press dispatched" else "Tap dispatched")
            } else {
                AgentResult.failure(command.id, "Gesture dispatch failed")
            }
        }
    }

    private fun swipe(command: AgentCommand): AgentResult {
        val fromX = command.args.requireCoordinate("from_x")
        val fromY = command.args.requireCoordinate("from_y")
        val toX = command.args.requireCoordinate("to_x")
        val toY = command.args.requireCoordinate("to_y")
        val duration = command.args.optLong("duration_ms", 350).coerceIn(100, 3_000)

        return onMain(command.id) {
            val metrics = resources.displayMetrics
            val valid = listOf(fromX, toX).all { it in 0f..metrics.widthPixels.toFloat() } &&
                listOf(fromY, toY).all { it in 0f..metrics.heightPixels.toFloat() }
            if (!valid) return@onMain AgentResult.failure(command.id, "Coordinates are outside the screen")

            val path = Path().apply {
                moveTo(fromX, fromY)
                lineTo(toX, toY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            if (dispatchGesture(gesture, null, null)) {
                AgentResult.success(command.id, "Swipe dispatched")
            } else {
                AgentResult.failure(command.id, "Swipe dispatch failed")
            }
        }
    }

    private fun typeText(command: AgentCommand): AgentResult {
        val text = command.args.optString("text")
        require(text.length <= MAX_TEXT_LENGTH) { "Text is too long" }
        return onMain(command.id) {
            val root = rootInActiveWindow
                ?: return@onMain AgentResult.failure(command.id, "No active window")
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return@onMain AgentResult.failure(command.id, "No focused input field")
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                AgentResult.success(command.id, "Text entered")
            } else {
                AgentResult.failure(command.id, "The focused element rejected text input")
            }
        }
    }

    private fun clickText(command: AgentCommand): AgentResult {
        val text = command.args.optString("text").trim()
        require(text.isNotEmpty() && text.length <= MAX_QUERY_LENGTH) { "Invalid text query" }
        return onMain(command.id) {
            val root = rootInActiveWindow
                ?: return@onMain AgentResult.failure(command.id, "No active window")
            val matches = root.findAccessibilityNodeInfosByText(text)
            val target = matches.firstOrNull { node ->
                node.text?.toString()?.contains(text, ignoreCase = true) == true ||
                    node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
            } ?: return@onMain AgentResult.failure(command.id, "Text was not found")

            var clickable: AccessibilityNodeInfo? = target
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                AgentResult.success(command.id, "Text element clicked")
            } else {
                AgentResult.failure(command.id, "The matching element is not clickable")
            }
        }
    }

    private fun scroll(command: AgentCommand, action: Int): AgentResult = onMain(command.id) {
        val root = rootInActiveWindow
            ?: return@onMain AgentResult.failure(command.id, "No active window")
        val target = breadthFirst(root, MAX_TREE_NODES).firstOrNull { it.isScrollable }
            ?: return@onMain AgentResult.failure(command.id, "No scrollable element found")
        if (target.performAction(action)) {
            AgentResult.success(command.id, "Scroll performed")
        } else {
            AgentResult.failure(command.id, "Scroll was rejected")
        }
    }

    private fun openApp(command: AgentCommand): AgentResult {
        val packageName = command.args.optString("package").trim()
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
            "Invalid package name"
        }
        return onMain(command.id) {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return@onMain AgentResult.failure(command.id, "App is not installed or has no launcher")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            AgentResult.success(command.id, "App opened")
        }
    }

    private fun screenTree(command: AgentCommand): AgentResult = onMain(command.id) {
        val root = rootInActiveWindow
            ?: return@onMain AgentResult.failure(command.id, "No active window")
        val nodes = JSONArray()
        breadthFirst(root, MAX_TREE_NODES).forEachIndexed { index, node ->
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            nodes.put(
                JSONObject()
                    .put("index", index)
                    .put("class", node.className?.toString().orEmpty())
                    .put("text", if (node.isPassword) "[REDACTED]" else node.text?.toString().orEmpty())
                    .put("description", node.contentDescription?.toString().orEmpty())
                    .put("view_id", node.viewIdResourceName.orEmpty())
                    .put("clickable", node.isClickable)
                    .put("editable", node.isEditable)
                    .put("scrollable", node.isScrollable)
                    .put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"),
            )
        }
        AgentResult.success(
            command.id,
            "Screen tree captured",
            JSONObject().put("package", currentPackageName()).put("nodes", nodes),
        )
    }

    private fun screenshot(command: AgentCommand): AgentResult {
        val result = AtomicReference<AgentResult>()
        val latch = CountDownLatch(1)
        mainHandler.post {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        runCatching {
                            val source = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace,
                            ) ?: error("Could not decode screenshot")
                            val width = source.width
                            val height = source.height
                            val bitmap = source.copy(Bitmap.Config.ARGB_8888, false)
                            screenshot.hardwareBuffer.close()
                            val bytes = ByteArrayOutputStream().use { output ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, output)
                                output.toByteArray()
                            }
                            bitmap.recycle()
                            JSONObject()
                                .put("mime", "image/jpeg")
                                .put("width", width)
                                .put("height", height)
                                .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }.fold(
                            onSuccess = { data -> result.set(AgentResult.success(command.id, "Screenshot captured", data)) },
                            onFailure = { error -> result.set(AgentResult.failure(command.id, error.message ?: "Screenshot failed")) },
                        )
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        result.set(AgentResult.failure(command.id, "Screenshot failed with code $errorCode"))
                        latch.countDown()
                    }
                },
            )
        }
        if (!latch.await(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return AgentResult.failure(command.id, "Screenshot timed out")
        }
        return result.get() ?: AgentResult.failure(command.id, "Screenshot failed")
    }

    private fun onMain(id: String, operation: () -> AgentResult): AgentResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return safeResult(id, operation)
        val result = AtomicReference<AgentResult>()
        val latch = CountDownLatch(1)
        mainHandler.post {
            result.set(safeResult(id, operation))
            latch.countDown()
        }
        if (!latch.await(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return AgentResult.failure(id, "Command timed out")
        }
        return result.get() ?: AgentResult.failure(id, "Command failed")
    }

    private fun safeResult(id: String, operation: () -> AgentResult): AgentResult = runCatching(operation)
        .getOrElse { AgentResult.failure(id, it.message ?: "Command failed") }

    private fun breadthFirst(root: AccessibilityNodeInfo, limit: Int): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < limit) {
            val node = queue.removeFirst()
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return result
    }

    private fun currentPackageName(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    private fun JSONObject.requireCoordinate(name: String): Float {
        require(has(name)) { "Missing $name" }
        return getDouble(name).toFloat()
    }

    companion object {
        @Volatile
        var activeInstance: BadeeAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = activeInstance != null

        private val screenshotExecutor = Executors.newSingleThreadExecutor()
        private const val MAX_TEXT_LENGTH = 4_000
        private const val MAX_QUERY_LENGTH = 200
        private const val MAX_TREE_NODES = 500
        private const val SCREENSHOT_QUALITY = 72
        private const val COMMAND_TIMEOUT_SECONDS = 8L
    }
}
