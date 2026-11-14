package com.wx.mapper.data.local

import com.wx.mapper.model.AppConfig
import com.wx.mapper.model.DraggableItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Arquivo .json de backup: tudo que o usuário configurou, para levar para outro aparelho. */
@Serializable
data class WxBackup(
    val app: String = APP_ID,
    val version: Int = CURRENT_VERSION,
    val config: AppConfig,
    val addedPackages: List<String> = emptyList(),
    /** Layout de botões de cada jogo, pela chave do pacote. */
    val layouts: Map<String, List<DraggableItem>> = emptyMap(),
    val pointerSensitivity: Float? = null,
    val overlayOpacity: Float? = null,
) {
    companion object {
        const val APP_ID = "wx-mapper"
        const val CURRENT_VERSION = 1
    }
}

val BackupJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

fun WxBackup.toJsonText(): String = BackupJson.encodeToString(this)

/** Lê e valida um backup; lança exceção com mensagem clara se o arquivo não servir. */
fun parseBackup(text: String): WxBackup {
    val backup = BackupJson.decodeFromString<WxBackup>(text)
    require(backup.app == WxBackup.APP_ID) { "Este arquivo não é um backup do WX Mapper." }
    require(backup.version <= WxBackup.CURRENT_VERSION) { "Backup de uma versão mais nova do app." }
    return backup.copy(config = backup.config.copy(autoClicker = backup.config.autoClicker.sanitized()))
}
