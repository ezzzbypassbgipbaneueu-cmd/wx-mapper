package com.wx.mapper.ui

import kotlinx.serialization.Serializable

/** Perfil de um jogo: resumo, autoclicker, macros, mouse e cópia de outro jogo. */
@Serializable
data class ProfileRoute(val packageName: String)

/** Autoclicker DESTE jogo. */
@Serializable
data class AutoclickerRoute(val packageName: String)

/** Macros DESTE jogo. */
@Serializable
data class MacrosRoute(val packageName: String)
