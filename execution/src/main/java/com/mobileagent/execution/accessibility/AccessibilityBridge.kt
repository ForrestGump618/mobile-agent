package com.mobileagent.execution.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mobileagent.core.model.ScreenFrame
import com.mobileagent.core.model.UiNode
import com.mobileagent.core.model.UiSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class NodeFingerprint(
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val className: String,
    val bounds: String,
)

data class UiActionResult(
    val ok: Boolean,
    val message: String,
    val verified: Boolean = false,
    val snapshot: UiSnapshot? = null,
)

/**
 * Thin, process-wide bridge to the accessibility service. The service is the only component that
 * may read the screen or inject actions; this object never fakes a connection.
 */
object AccessibilityBridge {

    @Volatile
    private var service: AgentAccessibilityService? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val lastFingerprints = ConcurrentHashMap<String, NodeFingerprint>()
    private val _lastPackage = MutableStateFlow<String?>(null)
    val lastPackage: StateFlow<String?> = _lastPackage.asStateFlow()

    private val _lastActivity = MutableStateFlow<String?>(null)
    val lastActivity: StateFlow<String?> = _lastActivity.asStateFlow()

    private val capturing = AtomicBoolean(false)

    fun attach(service: AgentAccessibilityService) {
        this.service = service
        _connected.value = true
    }

    fun detach(service: AgentAccessibilityService) {
        if (this.service === service) {
            this.service = null
            _connected.value = false
        }
    }

    fun onWindowEvent(packageName: String?, className: String?) {
        if (!packageName.isNullOrBlank()) _lastPackage.value = packageName
        if (!className.isNullOrBlank() && className.contains('.')) _lastActivity.value = className
    }

    fun isConnected(): Boolean = service != null

    fun serviceInfo(): AccessibilityServiceInfo? = service?.serviceInfo

    // ------------------------------------------------------------- observation

    fun snapshot(maxNodes: Int = 400): UiSnapshot? {
        val svc = service ?: return null
        val root = svc.rootInActiveWindow ?: svc.windows.firstOrNull { it.isActive }?.root ?: return null
        return try {
            buildSnapshot(svc, root, maxNodes)
        } catch (t: Throwable) {
            null
        }
    }

    private fun buildSnapshot(svc: AccessibilityService, root: AccessibilityNodeInfo, maxNodes: Int): UiSnapshot {
        val metrics = svc.resources.displayMetrics
        var counter = 0
        var truncated = false
        val fingerprints = ConcurrentHashMap<String, NodeFingerprint>()

        fun build(node: AccessibilityNodeInfo, depth: Int): UiNode? {
            if (counter >= maxNodes) {
                truncated = true
                return null
            }
            if (depth > 40) {
                truncated = true
                return null
            }
            val id = "n${counter++}"
            val text = node.text?.toString()?.trim().orEmpty().take(300)
            val description = node.contentDescription?.toString()?.trim().orEmpty().take(300)
            val resourceId = node.viewIdResourceName.orEmpty()
            val className = node.className?.toString().orEmpty()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"

            fingerprints[id] = NodeFingerprint(text, description, resourceId, className, bounds)

            val children = mutableListOf<UiNode>()
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val built = build(child, depth + 1) ?: continue
                children += built
            }

            return UiNode(
                nodeId = id,
                text = text,
                contentDescription = description,
                className = className,
                resourceId = resourceId,
                bounds = bounds,
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                checkable = node.isCheckable,
                checked = node.isChecked,
                depth = depth,
                children = children,
            )
        }

        val nodes = buildList {
            for (index in 0 until root.childCount) {
                val child = root.getChild(index) ?: continue
                build(child, 0)?.let { add(it) }
            }
            if (isEmpty()) {
                build(root, 0)?.let { add(it) }
            }
        }

        lastFingerprints.clear()
        lastFingerprints.putAll(fingerprints)

        val packageName = root.packageName?.toString().orEmpty()
        _lastPackage.value = packageName.ifBlank { _lastPackage.value }

        return UiSnapshot(
            packageName = packageName,
            activityName = _lastActivity.value,
            capturedAt = System.currentTimeMillis(),
            nodes = nodes,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            truncated = truncated,
        )
    }

    fun screenSignature(): String {
        val snapshot = snapshot(260) ?: return "no-screen"
        return snapshot.allNodes()
            .filter { it.text.isNotBlank() || it.contentDescription.isNotBlank() }
            .joinToString("|") { it.label().take(40) }
            .hashCode()
            .toString()
    }

    // ---------------------------------------------------------------- actions

    fun click(nodeId: String): UiActionResult {
        val fingerprint = lastFingerprints[nodeId]
            ?: return UiActionResult(false, "Unknown node id $nodeId. Take a fresh ui.snapshot first.")
        return performOnFingerprint(fingerprint) { node ->
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                // Walk up to the nearest clickable ancestor, which is what a user tap would hit.
                var current: AccessibilityNodeInfo? = node
                var clicked = false
                var guard = 0
                while (current != null && guard < 6) {
                    if (current.isClickable) {
                        clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        break
                    }
                    current = current.parent
                    guard++
                }
                clicked
            }
        }
    }

    fun setText(nodeId: String, text: String): UiActionResult {
        val fingerprint = lastFingerprints[nodeId]
            ?: return UiActionResult(false, "Unknown node id $nodeId. Take a fresh ui.snapshot first.")
        return performOnFingerprint(fingerprint) { node ->
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }

    fun scroll(nodeId: String, forward: Boolean): UiActionResult {
        val fingerprint = lastFingerprints[nodeId]
            ?: return UiActionResult(false, "Unknown node id $nodeId. Take a fresh ui.snapshot first.")
        return performOnFingerprint(fingerprint) { node ->
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            node.performAction(action)
        }
    }

    fun back(): UiActionResult {
        val svc = service ?: return UiActionResult(false, "Accessibility service is not connected.")
        val before = screenSignature()
        val performed = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return UiActionResult(
            ok = performed,
            message = if (performed) "Back dispatched; wait for the UI to settle." else "The system refused the back action.",
            verified = performed && screenSignature() != before,
        )
    }

    fun tap(x: Int, y: Int): Boolean {
        val svc = service ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 350): Boolean {
        val svc = service ?: return false
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun findTextNode(text: String): UiNode? = snapshot()?.findText(text)?.firstOrNull()

    private fun performOnFingerprint(
        fingerprint: NodeFingerprint,
        action: (AccessibilityNodeInfo) -> Boolean,
    ): UiActionResult {
        val svc = service ?: return UiActionResult(false, "Accessibility service is not connected.")
        val root = svc.rootInActiveWindow ?: return UiActionResult(false, "No active window could be read.")
        val node = findMatchingNode(root, fingerprint)
            ?: return UiActionResult(false, "The screen changed: node not found anymore. Take a fresh snapshot.")
        val before = screenSignature()
        val performed = try {
            action(node)
        } catch (t: Throwable) {
            false
        }
        if (!performed) {
            return UiActionResult(false, "The target node rejected the action (disabled or not editable).")
        }
        val after = screenSignature()
        return UiActionResult(
            ok = true,
            message = "Action dispatched.",
            verified = after != before,
            snapshot = snapshot(200),
        )
    }

    private fun findMatchingNode(
        root: AccessibilityNodeInfo,
        fingerprint: NodeFingerprint,
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 800) {
            val node = stack.removeFirst()
            visited++
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
            val matches = node.text?.toString()?.trim().orEmpty() == fingerprint.text &&
                node.contentDescription?.toString()?.trim().orEmpty() == fingerprint.contentDescription &&
                node.viewIdResourceName.orEmpty() == fingerprint.resourceId &&
                bounds == fingerprint.bounds &&
                node.className?.toString().orEmpty() == fingerprint.className
            if (matches) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { stack.add(it) }
            }
        }
        return null
    }

    // ------------------------------------------------------------- screenshot

    suspend fun screenshot(): ScreenFrame? = suspendCancellableCoroutine { continuation ->
        val svc = service
        if (svc == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        if (!capturing.compareAndSet(false, true)) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        try {
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                svc.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val copy = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            if (copy == null) {
                                continuation.resume(null)
                            } else {
                                val bytes = ByteArrayOutputStream().use { stream ->
                                    copy.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                    stream.toByteArray()
                                }
                                continuation.resume(
                                    ScreenFrame(bytes, copy.width, copy.height, System.currentTimeMillis()),
                                )
                            }
                        } catch (t: Throwable) {
                            continuation.resume(null)
                        } finally {
                            capturing.set(false)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        capturing.set(false)
                        continuation.resume(null)
                    }
                },
            )
        } catch (t: Throwable) {
            capturing.set(false)
            if (continuation.isActive) continuation.resume(null)
        }
    }

    suspend fun screenshotScaled(maxDimension: Int, jpegQuality: Int = 75): ByteArray? =
        withContext(Dispatchers.Default) {
            val frame = screenshot() ?: return@withContext null
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(frame.pngBytes, 0, frame.pngBytes.size)
                ?: return@withContext null
            val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
            val target = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else {
                bitmap
            }
            ByteArrayOutputStream().use { stream ->
                target.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
                stream.toByteArray()
            }
        }

    fun canCapture(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && service != null
}

/** Receives accessibility events and exposes them through [AccessibilityBridge]. */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        AccessibilityBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityBridge.onWindowEvent(
                event.packageName?.toString(),
                event.className?.toString(),
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }
}
