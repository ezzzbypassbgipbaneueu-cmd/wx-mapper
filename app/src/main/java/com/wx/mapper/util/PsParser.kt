package com.wx.mapper.util

/** Uma linha do `ps` que pertence a um app instalado (usuário u0_aXXX). */
data class PsRow(val rssKb: Int, val name: String)

/**
 * Lê a saída do `ps` do Android (toybox) sem depender de colunas fixas: acha as colunas
 * USER, RSS e NAME/CMD pelo cabeçalho, então funciona tanto com `ps -A -o USER,PID,RSS,NAME`
 * quanto com o `ps -A` padrão. Só devolve processos de apps (usuário `u<N>_a<N>`).
 */
object PsParser {
    private val spaces = Regex("\\s+")

    // O toybox pode cortar o usuário com "+" no fim (ex.: u10_a123+).
    private val appUser = Regex("^u\\d+_a\\d+\\+?$")
    private val nameHeaders = setOf("NAME", "CMD", "COMMAND", "ARGS", "CMDLINE")

    fun parse(output: String): List<PsRow> {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val headerPos = lines.indexOfFirst { line ->
            val tokens = line.split(spaces)
            "USER" in tokens && "RSS" in tokens && tokens.any { it in nameHeaders }
        }
        if (headerPos < 0) return emptyList()

        val header = lines[headerPos].split(spaces)
        val userIdx = header.indexOf("USER")
        val rssIdx = header.indexOf("RSS")
        val nameIdx = header.indexOfFirst { it in nameHeaders }
        val needed = maxOf(userIdx, rssIdx, nameIdx)

        val rows = ArrayList<PsRow>()
        for (i in headerPos + 1 until lines.size) {
            val parts = lines[i].split(spaces)
            if (parts.size <= needed) continue
            if (!appUser.matches(parts[userIdx])) continue
            val rss = parts[rssIdx].toIntOrNull() ?: continue
            rows += PsRow(rss, parts[nameIdx])
        }
        return rows
    }
}
