package com.wx.mapper.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wx.mapper.model.AppConfig
import com.wx.mapper.model.CursorStyle
import com.wx.mapper.model.DraggableItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject

val Context.datastore by preferencesDataStore("app_configurations")

class DataStoreManager @Inject constructor(private val context: Context) {

    companion object {
        fun getButtonsConfigKey(packageName: String): Preferences.Key<String> =
            stringPreferencesKey("buttons_config_$packageName")

        val ADDED_PACKAGES = stringPreferencesKey("added_packages")
        val POINTER_SENSITIVITY = floatPreferencesKey("pointer_sensitivity")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val KEYS_CONFIG = stringPreferencesKey("keys_config")
    }
    
    fun getButtonsConfigKeys(): Flow<List<Preferences.Key<String>>> {
       return context.datastore.data.map { it.asMap().keys.filter { prefKey -> prefKey.name.startsWith("buttons_config") }.map { prefKey -> stringPreferencesKey(prefKey.name) } }
    }

    suspend fun remove(key: Preferences.Key<String>) {
        context.datastore.edit { pref ->
            pref.remove(key)
        }
    }

    suspend fun save(key: Preferences.Key<String>, value: List<DraggableItem>) {
        val json = Json.encodeToString(value)
        context.datastore.edit { pref ->
            pref[key] = json
        }
    }

    fun getButtons(key: Preferences.Key<String>): Flow<List<DraggableItem>> {
        return context.datastore.data.map { pref ->
            val json = pref[key] ?: return@map emptyList()
            Json.decodeFromString(json)
        }
    }

    suspend fun saveList(key: Preferences.Key<String>, value: List<String>) {
        val json = Json.encodeToString(value)
        context.datastore.edit { pref ->
            pref[key] = json
        }
    }

    fun getList(key: Preferences.Key<String>): Flow<List<String>> {
        return context.datastore.data.map { pref ->
            val json = pref[key] ?: return@map emptyList()
            Json.decodeFromString(json)
        }
    }

    suspend fun save(key: Preferences.Key<String>, value: AppConfig) {
        val json = Json.encodeToString(value)
        context.datastore.edit { pref ->
            pref[key] = json
        }
    }

    fun getKeyConfig(key: Preferences.Key<String>): Flow<AppConfig> {
        return context.datastore.data.map { pref ->
            val json = pref[key] ?: return@map AppConfig.Default
            Json.decodeFromString(json)
        }
    }

    /** Gera o texto JSON do backup com todas as configurações salvas. */
    suspend fun exportBackup(): String {
        val prefs = context.datastore.data.first()
        val config = prefs[KEYS_CONFIG]?.let { Json.decodeFromString<AppConfig>(it) } ?: AppConfig.Default
        // A imagem personalizada do cursor é um arquivo local do aparelho: não dá para levar junto.
        val portable = if (config.cursorImagePath != null || config.cursorStyle == CursorStyle.IMAGE)
            config.copy(
                cursorStyle = if (config.cursorStyle == CursorStyle.IMAGE) CursorStyle.ARROW else config.cursorStyle,
                cursorImagePath = null
            ) else config
        val prefix = "buttons_config_"
        val layouts = buildMap<String, List<DraggableItem>> {
            prefs.asMap().forEach { (key, value) ->
                if (key.name.startsWith(prefix) && value is String) {
                    put(key.name.removePrefix(prefix), Json.decodeFromString<List<DraggableItem>>(value))
                }
            }
        }
        val packages = prefs[ADDED_PACKAGES]?.let { Json.decodeFromString<List<String>>(it) } ?: emptyList()
        return WxBackup(
            config = portable,
            addedPackages = packages,
            layouts = layouts,
            pointerSensitivity = prefs[POINTER_SENSITIVITY],
            overlayOpacity = prefs[OVERLAY_OPACITY],
        ).toJsonText()
    }

    /** Aplica um backup: configurações gerais, macros, layouts de cada jogo e lista de jogos. */
    suspend fun importBackup(text: String) {
        val backup = parseBackup(text)
        context.datastore.edit { pref ->
            pref[KEYS_CONFIG] = Json.encodeToString(backup.config)
            backup.layouts.forEach { (pkg, items) ->
                pref[getButtonsConfigKey(pkg)] = Json.encodeToString(items)
            }
            val current = pref[ADDED_PACKAGES]?.let { Json.decodeFromString<List<String>>(it) } ?: emptyList()
            pref[ADDED_PACKAGES] = Json.encodeToString((current + backup.addedPackages + backup.layouts.keys).distinct())
            backup.pointerSensitivity?.let { pref[POINTER_SENSITIVITY] = it }
            backup.overlayOpacity?.let { pref[OVERLAY_OPACITY] = it }
        }
    }

    suspend fun save(key: Preferences.Key<String>, value: String) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }


    fun getString(key: Preferences.Key<String>): Flow<String?> {
        return context.datastore.data.map { pref ->
            pref[key]
        }
    }

    suspend fun save(key: Preferences.Key<Int>, value: Int) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }

    fun getInt(key: Preferences.Key<Int>): Flow<Int?> {
        return context.datastore.data.map { pref ->
            pref[key]
        }
    }

    suspend fun save(key: Preferences.Key<Float>, value: Float) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }

    fun getFloat(key: Preferences.Key<Float>): Flow<Float?> {
        return context.datastore.data.map { pref ->
            pref[key]
        }
    }


}