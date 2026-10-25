package com.mobileagent.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mobileagent.core.model.JsonCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

enum class ApprovalMode {
    /** Ask before every state-changing action. */
    ALWAYS_ASK,

    /** Ask for HIGH and CRITICAL actions only (default). */
    HIGH_RISK_ASK,

    /** Never ask, but still enforce hard denials and tool switches. */
    AUTO_APPROVE_SAFE,
}

@Serializable
data class AgentSettings(
    val activeProviderId: String? = null,
    val planningProviderId: String? = null,
    val executionProviderId: String? = null,
    val visionProviderId: String? = null,
    val summaryProviderId: String? = null,
    val approvalMode: ApprovalMode = ApprovalMode.HIGH_RISK_ASK,
    val allowLocalShell: Boolean = true,
    val allowShizuku: Boolean = false,
    val allowAccessibility: Boolean = false,
    val allowCloudScreenshots: Boolean = false,
    val maxIterations: Int = 12,
    val toolTimeoutMs: Long = 120_000L,
    val temperature: Double = 0.2,
    val maxOutputTokens: Int = 2_048,
    val contextWindowTokens: Int = 16_000,
    val retries: Int = 2,
    val themeMode: String = "system",
    val dynamicColor: Boolean = true,
    val memoryEnabled: Boolean = true,
    val backgroundEnabled: Boolean = true,
    val workspaceQuotaMb: Int = 256,
    val logRetentionDays: Int = 7,
    val diagnosticsVerbose: Boolean = false,
    /** First-run model configuration finished (or explicitly skipped). */
    val onboardingCompleted: Boolean = false,
    /** Empty = follow system, otherwise a BCP-47 tag such as zh-CN or en. */
    val languageTag: String = "",
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_settings")

private val SETTINGS_KEY = stringPreferencesKey("agent_settings_json")

class SettingsStore(private val context: Context) {

    val settings: Flow<AgentSettings> = context.settingsDataStore.data.map { prefs ->
        decode(prefs[SETTINGS_KEY])
    }

    suspend fun current(): AgentSettings = settings.first()

    suspend fun update(transform: (AgentSettings) -> AgentSettings) {
        context.settingsDataStore.edit { prefs ->
            val updated = transform(decode(prefs[SETTINGS_KEY]))
            prefs[SETTINGS_KEY] = JsonCodec.json.encodeToString(AgentSettings.serializer(), updated)
        }
    }

    suspend fun setActiveProvider(providerId: String?) = update { it.copy(activeProviderId = providerId) }

    suspend fun setLanguage(tag: String) = update { it.copy(languageTag = tag) }

    suspend fun completeOnboarding() = update { it.copy(onboardingCompleted = true) }

    suspend fun setRoleProvider(role: com.mobileagent.core.model.ModelRole, providerId: String?) = update {
        when (role) {
            com.mobileagent.core.model.ModelRole.CHAT -> it.copy(activeProviderId = providerId)
            com.mobileagent.core.model.ModelRole.PLANNING -> it.copy(planningProviderId = providerId)
            com.mobileagent.core.model.ModelRole.EXECUTION -> it.copy(executionProviderId = providerId)
            com.mobileagent.core.model.ModelRole.VISION -> it.copy(visionProviderId = providerId)
            com.mobileagent.core.model.ModelRole.SUMMARY -> it.copy(summaryProviderId = providerId)
            com.mobileagent.core.model.ModelRole.EMBEDDING -> it
        }
    }

    private fun decode(raw: String?): AgentSettings = if (raw.isNullOrBlank()) {
        AgentSettings()
    } else {
        runCatching { JsonCodec.json.decodeFromString(AgentSettings.serializer(), raw) }
            .getOrElse { AgentSettings() }
    }
}
