package com.mobileagent.app.ui.vm

import android.app.Application
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileagent.app.MobileAgentApplication
import com.mobileagent.core.model.UiSnapshot
import com.mobileagent.execution.accessibility.AccessibilityBridge
import com.mobileagent.execution.shell.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class DeviceSnapshot(
    val androidVersion: String,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
    val abis: String,
    val freeStorageMb: Long,
    val totalStorageMb: Long,
    val batteryPercent: Int,
    val locale: String,
    val appVersion: String,
    val rooted: Boolean,
    val screen: String,
)

class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as MobileAgentApplication).graph

    val snapshot = MutableStateFlow<DeviceSnapshot?>(null)
    val shizukuStatus = MutableStateFlow<ShizukuStatus?>(null)
    val accessibilityConnected = MutableStateFlow(false)
    val uiSnapshot = MutableStateFlow<UiSnapshot?>(null)
    val screenshot = MutableStateFlow<ImageBitmap?>(null)
    val message = MutableStateFlow<String?>(null)

    init {
        refresh()
        viewModelScope.launch {
            AccessibilityBridge.connected.collect { accessibilityConnected.value = it }
        }
    }

    fun refresh() {
        val context = getApplication<Application>()
        val stat = StatFs(context.filesDir.absolutePath)
        val battery = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val metrics = context.resources.displayMetrics
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        snapshot.value = DeviceSnapshot(
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            abis = Build.SUPPORTED_ABIS.joinToString(", "),
            freeStorageMb = stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024),
            totalStorageMb = stat.blockCountLong * stat.blockSizeLong / (1024 * 1024),
            batteryPercent = battery,
            locale = Locale.getDefault().toString(),
            appVersion = version,
            rooted = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").any { java.io.File(it).exists() },
            screen = "${metrics.widthPixels}×${metrics.heightPixels} @${metrics.densityDpi}dpi",
        )
        shizukuStatus.value = runCatching { graph.shizuku.status() }.getOrNull()
        accessibilityConnected.value = AccessibilityBridge.isConnected()
    }

    fun captureTree() {
        uiSnapshot.value = AccessibilityBridge.snapshot()
        if (uiSnapshot.value == null) message.value = "No screen could be read. Is the accessibility service connected?"
    }

    fun captureScreen() {
        viewModelScope.launch {
            val frame = AccessibilityBridge.screenshot()
            if (frame == null) {
                message.value = "Screen capture failed (service not connected, protected window, or capture denied)."
                return@launch
            }
            val bitmap = withContext(Dispatchers.Default) {
                android.graphics.BitmapFactory.decodeByteArray(frame.pngBytes, 0, frame.pngBytes.size)?.asImageBitmap()
            }
            screenshot.value = bitmap
            message.value = if (bitmap == null) "Could not decode the screenshot." else "Captured ${frame.width}×${frame.height}."
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
