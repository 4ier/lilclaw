package com.lilclaw.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val PROVIDER = stringPreferencesKey("provider")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val GATEWAY_PORT = intPreferencesKey("gateway_port")
    }

    val isSetupComplete: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SETUP_COMPLETE] ?: false }

    val provider: Flow<String> = context.dataStore.data
        .map { it[Keys.PROVIDER] ?: "" }

    val model: Flow<String> = context.dataStore.data
        .map { it[Keys.MODEL] ?: "" }

    val apiKey: Flow<String> = context.dataStore.data
        .map { it[Keys.API_KEY] ?: "" }

    val gatewayPort: Flow<Int> = context.dataStore.data
        .map { it[Keys.GATEWAY_PORT] ?: 3000 }

    // Suspend one-shot getters for quick access
    suspend fun providerValue(): String = provider.first()
    suspend fun apiKeyValue(): String = apiKey.first()
    suspend fun modelValue(): String = model.first()

    suspend fun completeSetup(provider: String, apiKey: String, model: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SETUP_COMPLETE] = true
            prefs[Keys.PROVIDER] = provider
            prefs[Keys.API_KEY] = apiKey
            prefs[Keys.MODEL] = model
        }
    }

    suspend fun updateProvider(provider: String, apiKey: String, model: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PROVIDER] = provider
            prefs[Keys.API_KEY] = apiKey
            prefs[Keys.MODEL] = model
        }
    }

    suspend fun setGatewayPort(port: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GATEWAY_PORT] = port
        }
    }
}
