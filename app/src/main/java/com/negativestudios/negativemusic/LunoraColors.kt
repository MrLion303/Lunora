package com.negativestudios.negativemusic

import androidx.compose.ui.graphics.Color

/**
 * Paleta central de Lunora.
 *
 * Los componentes deben consumir estos tokens semánticos en lugar de definir
 * colores hexadecimales localmente. Esto deja preparada la futura sustitución
 * de la paleta sin implementar todavía selección de color ni persistencia.
 */
internal object LunoraColors {
    // Identidad de marca
    val accentPrimary = Color(0xFF9B7BFF)
    val accentLavender = Color(0xFFC4B5FD)
    val accentSky = Color(0xFF8EDCFF)
    val accentPink = Color(0xFFFFA9D6)

    // Superficies y fondos
    val backgroundDark = Color(0xFF070B10)
    val backgroundLight = Color(0xFFF7F7F7)
    val surfaceDark = Color(0xFF0B1118)
    val surfaceAccentDark = Color(0xFF171426)
    val surfaceAccentLight = Color(0xFFF2EFFA)
    val surfaceVariantLight = Color(0xFFF0EAFF)
    val secondaryContainerLight = Color(0xFFDDF5FF)
    val onSecondaryContainerLight = Color(0xFF15384A)
    val artworkPlaceholderDark = Color(0xFF201631)
    val artworkGradientStart = Color(0xFF30204D)
    val artworkGradientEnd = Color(0xFF100D18)
    val infoSurfaceDark = Color(0xFF102D4A)
    val playerGradientStart = Color(0xFF123C67)
    val playerGradientEnd = Color(0xFF071018)
    val dialogGradientEnd = Color(0xFF10202C)
    val inputSurfaceDark = Color(0xFF101923)

    // Texto
    val textSecondaryDark = Color(0xFFB3C0CE)
    val textPrimaryLight = Color(0xFF171717)
    val textSecondaryLight = Color(0xFF666666)
    val textPrimarySettingsLight = Color(0xFF111820)
    val textSecondarySettingsLight = Color(0xFF596675)

    // Datos y estados informativos
    val storageApp = Color(0xFFFFC107)
    val storageMusic = Color(0xFF39B54A)
}
