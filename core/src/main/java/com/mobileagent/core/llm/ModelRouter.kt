package com.mobileagent.core.llm

import com.mobileagent.core.data.ProviderRepository
import com.mobileagent.core.model.ModelRole
import com.mobileagent.core.model.ProviderConfig
import com.mobileagent.core.security.SecretVault
import com.mobileagent.core.settings.SettingsStore

data class ResolvedModel(
    val provider: ProviderConfig,
    val apiKey: String?,
    val label: String,
)

/**
 * Resolves the model for each agent role from user settings, with a deterministic fallback chain.
 * Vision requests never fall back to a model that is not declared as vision capable.
 */
class ModelRouter(
    private val providers: ProviderRepository,
    private val settings: SettingsStore,
    private val secrets: SecretVault,
) {

    suspend fun resolve(role: ModelRole): ResolvedModel? {
        val current = settings.current()
        val all = providers.list().filter { it.enabled && it.isUsable() }
        if (all.isEmpty()) return null

        if (role == ModelRole.VISION) {
            val preferred = current.visionProviderId ?: current.activeProviderId
            val visionCandidate = all.firstOrNull { it.id == preferred && it.vision }
                ?: all.firstOrNull { it.vision }
                ?: return null
            return visionCandidate.toResolved()
        }

        val preferredId = when (role) {
            ModelRole.CHAT -> current.activeProviderId
            ModelRole.PLANNING -> current.planningProviderId ?: current.activeProviderId
            ModelRole.EXECUTION -> current.executionProviderId ?: current.activeProviderId
            ModelRole.SUMMARY -> current.summaryProviderId ?: current.activeProviderId
            ModelRole.EMBEDDING -> current.summaryProviderId ?: current.activeProviderId
            ModelRole.VISION -> null
        }
        val chosen = all.firstOrNull { it.id == preferredId } ?: all.first()
        return chosen.toResolved()
    }

    suspend fun activeLabel(): String? = resolve(ModelRole.CHAT)?.label

    private fun ProviderConfig.toResolved() = ResolvedModel(
        provider = this,
        apiKey = apiKeyId?.let { secrets.get(it) },
        label = "$name · $modelId",
    )
}
