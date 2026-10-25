package com.mobileagent.execution.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.model.ToolSpec
import com.mobileagent.execution.accessibility.AccessibilityBridge
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Locale

class DeviceInfoTool(private val context: Context) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "device.info",
        title = "Device information",
        description = "Read real device facts: Android version, SDK level, model, ABIs, storage, memory, battery, locale, uptime, app version and root status.",
        parametersSchema = Schemas.obj(required = emptyList(), props = emptyMap()),
        risk = RiskLevel.LOW,
        category = ToolCategory.DEVICE,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        return try {
            val runtime = Runtime.getRuntime()
            val activityManager = this.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            val stat = StatFs(this.context.filesDir.absolutePath)
            val storageTotal = stat.blockCountLong * stat.blockSizeLong
            val storageFree = stat.availableBlocksLong * stat.blockSizeLong
            val batteryManager = this.context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val metrics = this.context.resources.displayMetrics
            val appInfo = this.context.packageManager.getApplicationInfo(this.context.packageName, 0)
            val appVersion = runCatching {
                this.context.packageManager.getPackageInfo(this.context.packageName, 0).versionName
            }.getOrNull().orEmpty()

            val rooted = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
                .any { File(it).exists() }

            val output = buildJsonObject {
                put("androidVersion", JsonPrimitive(Build.VERSION.RELEASE))
                put("sdkInt", JsonPrimitive(Build.VERSION.SDK_INT))
                put("manufacturer", JsonPrimitive(Build.MANUFACTURER))
                put("model", JsonPrimitive(Build.MODEL))
                put("device", JsonPrimitive(Build.DEVICE))
                put("abis", JsonPrimitive(Build.SUPPORTED_ABIS.joinToString(",")))
                put("locale", JsonPrimitive(Locale.getDefault().toString()))
                put("timezone", JsonPrimitive(java.util.TimeZone.getDefault().id))
                put("uptimeMs", JsonPrimitive(SystemClock.elapsedRealtime()))
                put("storageTotalMb", JsonPrimitive(storageTotal / (1024 * 1024)))
                put("storageFreeMb", JsonPrimitive(storageFree / (1024 * 1024)))
                put("heapMaxMb", JsonPrimitive(runtime.maxMemory() / (1024 * 1024)))
                put("ramTotalMb", JsonPrimitive(memoryInfo.totalMem / (1024 * 1024)))
                put("ramAvailableMb", JsonPrimitive(memoryInfo.availMem / (1024 * 1024)))
                put("batteryPercent", JsonPrimitive(batteryLevel))
                put("screenWidthPx", JsonPrimitive(metrics.widthPixels))
                put("screenHeightPx", JsonPrimitive(metrics.heightPixels))
                put("densityDpi", JsonPrimitive(metrics.densityDpi))
                put("appPackage", JsonPrimitive(this@DeviceInfoTool.context.packageName))
                put("appVersion", JsonPrimitive(appVersion))
                put("debuggable", JsonPrimitive((appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0))
                put("rooted", JsonPrimitive(rooted))
                put("accessibilityConnected", JsonPrimitive(AccessibilityBridge.isConnected()))
            }
            success(
                context = context,
                toolName = spec.name,
                summary = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) on ${Build.MANUFACTURER} ${Build.MODEL}; " +
                    "${storageFree / (1024 * 1024)} MB free; battery ${batteryLevel}%",
                startedAt = startedAt,
                output = output,
            )
        } catch (t: Throwable) {
            failure(context, spec.name, "Could not read device info: ${t.message}", startedAt)
        }
    }
}

class AppListTool(private val context: Context) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "app.list",
        title = "List installed apps",
        description = "List installed applications visible to this app, with package name, label and version. " +
            "Third-party apps are listed by default; package visibility rules still apply.",
        parametersSchema = Schemas.obj(
            required = emptyList(),
            props = mapOf(
                "includeSystem" to Schemas.bool("Include system apps (default false)"),
                "limit" to Schemas.int("Maximum entries to return", 1, 400),
                "query" to Schemas.str("Optional case-insensitive filter on name or package"),
            ),
        ),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.DEVICE,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val includeSystem = arguments["includeSystem"]?.jsonPrimitive?.content == "true"
        val limit = arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 400) ?: 120
        val query = arguments["query"]?.jsonPrimitive?.content?.lowercase()?.takeIf { it.isNotBlank() }

        return try {
            val pm = this.context.packageManager
            val packages = pm.getInstalledPackages(0)
            val entries = packages.asSequence()
                .mapNotNull { info ->
                    val app = info.applicationInfo ?: return@mapNotNull null
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (isSystem && !includeSystem) return@mapNotNull null
                    val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                    if (query != null && !label.lowercase().contains(query) && !info.packageName.lowercase().contains(query)) {
                        return@mapNotNull null
                    }
                    buildJsonObject {
                        put("package", JsonPrimitive(info.packageName))
                        put("label", JsonPrimitive(label))
                        put("version", JsonPrimitive(info.versionName.orEmpty()))
                        put("system", JsonPrimitive(isSystem))
                    }
                }
                .sortedBy { it["label"]?.jsonPrimitive?.content?.lowercase().orEmpty() }
                .take(limit)
                .toList()

            val output = buildJsonObject {
                put("apps", JsonArray(entries))
                put("count", JsonPrimitive(entries.size))
                put("installedPackageCount", JsonPrimitive(packages.size))
                put("includeSystem", JsonPrimitive(includeSystem))
                put("limit", JsonPrimitive(limit))
            }
            success(
                context = context,
                toolName = spec.name,
                summary = "${entries.size} app(s) listed (of ${packages.size} visible packages).",
                startedAt = startedAt,
                output = output,
            )
        } catch (t: Throwable) {
            failure(context, spec.name, "Package listing failed: ${t.message}", startedAt)
        }
    }
}

class AppLaunchTool(private val context: Context) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "app.launch",
        title = "Launch an app",
        description = "Launch an installed app by package name and verify that it became the foreground package when accessibility is connected.",
        parametersSchema = Schemas.obj(
            required = listOf("package"),
            props = mapOf(
                "package" to Schemas.str("Android package name, e.g. com.android.settings", maxLength = 200),
            ),
        ),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.DEVICE,
        source = "execution",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val packageName = arguments["package"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (packageName.isBlank()) return failure(context, spec.name, "package is required", startedAt)

        val intent = this.context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return failure(context, spec.name, "No launchable activity for $packageName (not installed or no launcher intent).", startedAt)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            this.context.startActivity(intent)
            var foreground: String? = null
            if (AccessibilityBridge.isConnected()) {
                repeat(6) {
                    delay(400)
                    foreground = AccessibilityBridge.snapshot()?.packageName
                    if (foreground == packageName) return@repeat
                }
            }
            val verified = foreground == packageName
            val output = buildJsonObject {
                put("package", JsonPrimitive(packageName))
                put("component", JsonPrimitive(intent.component?.flattenToShortString().orEmpty()))
                put("launched", JsonPrimitive(true))
                put("verified", JsonPrimitive(verified))
                put("foregroundPackage", JsonPrimitive(foreground.orEmpty()))
            }
            ToolResult(
                callId = context.callId,
                toolName = spec.name,
                ok = true,
                output = output,
                summary = if (verified) {
                    "$packageName is in the foreground."
                } else if (foreground == null) {
                    "Launch intent sent to $packageName; accessibility is not connected so the result is unverified."
                } else {
                    "Launch intent sent to $packageName, but the foreground package is $foreground."
                },
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } catch (t: Throwable) {
            failure(context, spec.name, "Launch failed: ${t.message}", startedAt)
        }
    }
}
