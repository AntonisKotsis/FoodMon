package com.ak.foodmon.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val legacyApiKeyKey = stringPreferencesKey("anthropic_api_key")
private val encryptedApiKeyKey = stringPreferencesKey("anthropic_api_key_enc")

/** Earlier versions stored the API key in plain text; encrypt it and drop the plain copy. */
private object EncryptApiKeyMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = legacyApiKeyKey in currentData

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            val plain = remove(legacyApiKeyKey).orEmpty()
            if (plain.isNotBlank()) this[encryptedApiKeyKey] = SecretCipher.encrypt(plain)
        }.toPreferences()

    override suspend fun cleanUp() = Unit
}

private val Context.dataStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(EncryptApiKeyMigration) },
)

/** Vision models offered in Settings, with a rough per-photo cost to help choose. */
enum class VisionModel(val id: String, val label: String, val costHint: String) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5", "most accurate · ~\$0.02–0.04 per photo"),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", "very good · ~\$0.01–0.02 per photo"),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5", "cheapest · well under \$0.01 per photo");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: OPUS
    }
}

data class HealthCache(val profileId: Long, val date: String, val activeKcal: Double)

class SettingsStore(private val context: Context) {
    private val activeProfileKey = longPreferencesKey("active_profile_id")
    private val modelKey = stringPreferencesKey("vision_model")
    private val healthCacheKey = stringPreferencesKey("health_cache")

    val activeProfileId: Flow<Long?> = context.dataStore.data.map { it[activeProfileKey] }
    /** Decrypted API key; empty if unset or no longer decryptable (e.g. data moved to a new device). */
    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[encryptedApiKeyKey]?.let(SecretCipher::decrypt).orEmpty()
    }
    val model: Flow<VisionModel> = context.dataStore.data.map { VisionModel.fromId(it[modelKey]) }

    suspend fun setActiveProfile(id: Long?) = context.dataStore.edit {
        if (id == null) it.remove(activeProfileKey) else it[activeProfileKey] = id
    }

    suspend fun setApiKey(key: String) = context.dataStore.edit {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) it.remove(encryptedApiKeyKey) else it[encryptedApiKeyKey] = SecretCipher.encrypt(trimmed)
    }

    suspend fun setModel(model: VisionModel) = context.dataStore.edit { it[modelKey] = model.id }

    /**
     * Last Health Connect active-calorie reading, so the widget can show it without reading
     * health data in the background. Stored as "profileId|yyyy-MM-dd|kcal".
     */
    val healthCache: Flow<HealthCache?> = context.dataStore.data.map { prefs ->
        prefs[healthCacheKey]?.split('|')?.takeIf { it.size == 3 }?.let { (id, date, kcal) ->
            HealthCache(id.toLong(), date, kcal.toDouble())
        }
    }

    suspend fun setHealthCache(cache: HealthCache) = context.dataStore.edit {
        it[healthCacheKey] = "${cache.profileId}|${cache.date}|${cache.activeKcal}"
    }

    suspend fun currentApiKey(): String = apiKey.first()
    suspend fun currentModel(): VisionModel = model.first()
}