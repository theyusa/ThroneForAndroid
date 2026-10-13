package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

object ThemeM3 {

    // Theme variant constants matching original Theme.kt
    const val MONET = 0
    const val RED = 1
    const val PINK_SSR = 2
    const val PINK = 3
    const val PURPLE = 4
    const val DEEP_PURPLE = 5
    const val INDIGO = 6
    const val BLUE = 7
    const val LIGHT_BLUE = 8
    const val CYAN = 9
    const val TEAL = 10
    const val GREEN = 11
    const val LIGHT_GREEN = 12
    const val LIME = 13
    const val YELLOW = 14
    const val AMBER = 15
    const val ORANGE = 16
    const val DEEP_ORANGE = 17
    const val BROWN = 18
    const val GREY = 19
    const val BLUE_GREY = 20
    const val BLACK = 21
    const val VERDANT_MINT = 22
    const val WHITE = 23

    private fun defaultTheme() = PINK_SSR

    fun apply(context: Context) {
        context.setTheme(getTheme())
        applyAmoledOverlay(context)
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
        applyAmoledOverlay(context)
    }

    // Night mode with AMOLED black: the black overlay goes on top of the theme
    private fun applyAmoledOverlay(context: Context) {
        if (DataStore.amoledTheme && usingNightMode()) {
            context.theme.applyStyle(R.style.Theme_Throne_M3_Amoled, true)
            // White falls back to Black at night, whose app bar (colorPrimary) is dark grey; OLED makes it black.
            // Not under Monet, so the Monet colours are not flattened to black
            if (DataStore.appTheme == WHITE && !usingMonetTheme()) {
                context.theme.applyStyle(R.style.Theme_Throne_M3_Amoled_White, true)
            }
        }
    }

    // Whether system dynamic colour (Monet) is in effect; it disables every White-theme adjustment
    fun usingMonetTheme(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme
    }

    fun getTheme(): Int {
        return if (usingMonetTheme()) {
            getTheme(MONET)
        } else {
            getTheme(DataStore.appTheme)
        }
    }

    fun getDialogTheme(): Int {
        return if (usingMonetTheme()) {
            getDialogTheme(MONET)
        } else {
            getDialogTheme(DataStore.appTheme)
        }
    }

    fun getTheme(theme: Int): Int {
        return when (theme) {
            MONET -> R.style.Theme_Throne_M3_Monet
            RED -> R.style.Theme_Throne_M3_Red
            PINK -> R.style.Theme_Throne_M3
            PINK_SSR -> R.style.Theme_Throne_M3_Pink_SSR
            PURPLE -> R.style.Theme_Throne_M3_Purple
            DEEP_PURPLE -> R.style.Theme_Throne_M3_DeepPurple
            INDIGO -> R.style.Theme_Throne_M3_Indigo
            BLUE -> R.style.Theme_Throne_M3_Blue
            LIGHT_BLUE -> R.style.Theme_Throne_M3_LightBlue
            CYAN -> R.style.Theme_Throne_M3_Cyan
            TEAL -> R.style.Theme_Throne_M3_Teal
            GREEN -> R.style.Theme_Throne_M3_Green
            LIGHT_GREEN -> R.style.Theme_Throne_M3_LightGreen
            LIME -> R.style.Theme_Throne_M3_Lime
            YELLOW -> R.style.Theme_Throne_M3_Yellow
            AMBER -> R.style.Theme_Throne_M3_Amber
            ORANGE -> R.style.Theme_Throne_M3_Orange
            DEEP_ORANGE -> R.style.Theme_Throne_M3_DeepOrange
            BROWN -> R.style.Theme_Throne_M3_Brown
            GREY -> R.style.Theme_Throne_M3_Grey
            BLUE_GREY -> R.style.Theme_Throne_M3_BlueGrey
            BLACK -> R.style.Theme_Throne_M3_Black
            VERDANT_MINT -> R.style.Theme_Throne_M3_VerdantMint
            WHITE ->
                // White only applies outside night mode; at night it falls back to Black
                if (usingNightMode()) R.style.Theme_Throne_M3_White_Night else R.style.Theme_Throne_M3_White
            else -> getTheme(defaultTheme())
        }
    }

    fun getDialogTheme(theme: Int): Int {
        return when (theme) {
            MONET -> R.style.Theme_Throne_M3_Dialog_Monet
            RED -> R.style.Theme_Throne_M3_Dialog_Red
            PINK -> R.style.Theme_Throne_M3_Dialog
            PINK_SSR -> R.style.Theme_Throne_M3_Dialog_Pink_SSR
            PURPLE -> R.style.Theme_Throne_M3_Dialog_Purple
            DEEP_PURPLE -> R.style.Theme_Throne_M3_Dialog_DeepPurple
            INDIGO -> R.style.Theme_Throne_M3_Dialog_Indigo
            BLUE -> R.style.Theme_Throne_M3_Dialog_Blue
            LIGHT_BLUE -> R.style.Theme_Throne_M3_Dialog_LightBlue
            CYAN -> R.style.Theme_Throne_M3_Dialog_Cyan
            TEAL -> R.style.Theme_Throne_M3_Dialog_Teal
            GREEN -> R.style.Theme_Throne_M3_Dialog_Green
            LIGHT_GREEN -> R.style.Theme_Throne_M3_Dialog_LightGreen
            LIME -> R.style.Theme_Throne_M3_Dialog_Lime
            YELLOW -> R.style.Theme_Throne_M3_Dialog_Yellow
            AMBER -> R.style.Theme_Throne_M3_Dialog_Amber
            ORANGE -> R.style.Theme_Throne_M3_Dialog_Orange
            DEEP_ORANGE -> R.style.Theme_Throne_M3_Dialog_DeepOrange
            BROWN -> R.style.Theme_Throne_M3_Dialog_Brown
            GREY -> R.style.Theme_Throne_M3_Dialog_Grey
            BLUE_GREY -> R.style.Theme_Throne_M3_Dialog_BlueGrey
            BLACK -> R.style.Theme_Throne_M3_Dialog_Black
            VERDANT_MINT -> R.style.Theme_Throne_M3_Dialog_VerdantMint
            WHITE ->
                if (usingNightMode()) R.style.Theme_Throne_M3_Dialog_White_Night else R.style.Theme_Throne_M3_Dialog_White
            else -> getDialogTheme(defaultTheme())
        }
    }

    // Whether the White theme is in effect (not at night, where it falls back to Black, and not under Monet)
    fun isWhiteTheme(): Boolean {
        if (usingMonetTheme()) {
            return false
        }
        return DataStore.appTheme == WHITE && !usingNightMode()
    }

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    fun usingNightMode(): Boolean {
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

    // Color palette data class for M3
    data class ColorPalette(
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val secondary: Int,
        val onSecondary: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val tertiary: Int,
        val onTertiary: Int,
        val tertiaryContainer: Int,
        val onTertiaryContainer: Int,
        val error: Int,
        val onError: Int,
        val errorContainer: Int,
        val onErrorContainer: Int,
        val surface: Int,
        val onSurface: Int,
        val surfaceVariant: Int,
        val onSurfaceVariant: Int,
        val outline: Int,
        val outlineVariant: Int,
        val surfaceContainerHighest: Int,
        val surfaceContainerHigh: Int,
        val surfaceContainer: Int,
        val surfaceContainerLow: Int,
        val surfaceContainerLowest: Int,
        val scrim: Int,
        val shadow: Int
    )
}