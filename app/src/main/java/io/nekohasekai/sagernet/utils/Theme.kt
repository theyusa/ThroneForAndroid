package io.nekohasekai.sagernet.utils

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr

object Theme {

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
    const val LIGHT_GRAY = 24
    const val CUSTOM = 99

    private fun defaultTheme() = VERDANT_MINT

    fun isValidTheme(themeId: Int): Boolean {
        return themeId in MONET..LIGHT_GRAY || themeId == CUSTOM
    }

    fun getClosestThemeForColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val hue = hsv[0]
        val sat = hsv[1]

        if (sat < 0.12f) return GREY
        return when {
            hue in 345f..360f || hue < 15f -> RED
            hue in 15f..35f -> DEEP_ORANGE
            hue in 35f..50f -> ORANGE
            hue in 50f..65f -> AMBER
            hue in 65f..90f -> LIME
            hue in 90f..140f -> GREEN
            hue in 140f..165f -> VERDANT_MINT
            hue in 165f..185f -> TEAL
            hue in 185f..205f -> CYAN
            hue in 205f..230f -> BLUE
            hue in 230f..250f -> INDIGO
            hue in 250f..275f -> DEEP_PURPLE
            hue in 275f..315f -> PURPLE
            hue in 315f..345f -> PINK
            else -> VERDANT_MINT
        }
    }

    fun getSystemWallpaperColor(context: Context): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val wallpaperManager = WallpaperManager.getInstance(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val colors = wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    val primary = colors?.primaryColor?.toArgb()
                    if (primary != null && primary != 0) {
                        return primary
                    }
                }
                val sysAccent = context.getColor(android.R.color.system_accent1_600)
                if (sysAccent != 0) return sysAccent
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun apply(context: Context) {
        context.setTheme(getTheme(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (DataStore.appTheme == MONET || DataStore.useSystemTheme) &&
            !isWhiteTheme(context) && !isLightGrayTheme(context) && !isBlackTheme(context) &&
            context is android.app.Activity
        ) {
            com.google.android.material.color.DynamicColors.applyIfAvailable(context)
        }
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (DataStore.appTheme == MONET || DataStore.useSystemTheme) &&
            !isWhiteTheme(context) && !isLightGrayTheme(context) && !isBlackTheme(context) &&
            context is android.app.Activity
        ) {
            com.google.android.material.color.DynamicColors.applyIfAvailable(context)
        }
    }

    fun usingMonetTheme(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (DataStore.useSystemTheme || DataStore.appTheme == MONET)
    }

    fun getTheme(context: Context? = null): Int {
        val ctx = context ?: app
        val themeId = if (DataStore.useSystemTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MONET
        } else {
            DataStore.appTheme
        }
        if (themeId == BLACK) {
            return R.style.Theme_SagerNet_Black
        }
        if (usingNightMode(ctx) && themeId in setOf(WHITE, LIGHT_GRAY)) {
            return R.style.Theme_SagerNet_Black
        }
        if (usingNightMode(ctx) && DataStore.amoledTheme) {
            return getAmoledTheme(themeId)
        }
        return getTheme(themeId, ctx)
    }

    fun getDialogTheme(context: Context? = null): Int {
        val ctx = context ?: app
        val themeId = if (DataStore.useSystemTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MONET
        } else {
            DataStore.appTheme
        }
        if (themeId == BLACK) {
            return R.style.Theme_SagerNet_Dialog_Black
        }
        if (usingNightMode(ctx) && themeId in setOf(WHITE, LIGHT_GRAY)) {
            return R.style.Theme_SagerNet_Dialog_Black
        }
        if (usingNightMode(ctx) && DataStore.amoledTheme) {
            return getAmoledDialogTheme(themeId)
        }
        return getDialogTheme(themeId, ctx)
    }

    fun getAmoledTheme(themeId: Int): Int {
        return when (themeId) {
            RED -> R.style.Theme_SagerNet_Amoled_Red
            VERDANT_MINT -> R.style.Theme_SagerNet_Amoled_VerdantMint
            BLUE -> R.style.Theme_SagerNet_Amoled_Blue
            PURPLE, DEEP_PURPLE -> R.style.Theme_SagerNet_Amoled_Purple
            ORANGE, DEEP_ORANGE -> R.style.Theme_SagerNet_Amoled_Orange
            TEAL, CYAN -> R.style.Theme_SagerNet_Amoled_Teal
            GREEN -> R.style.Theme_SagerNet_Amoled_Green
            PINK, PINK_SSR -> R.style.Theme_SagerNet_Amoled_Pink
            AMBER, YELLOW -> R.style.Theme_SagerNet_Amoled_Amber
            INDIGO, LIGHT_BLUE -> R.style.Theme_SagerNet_Amoled_Indigo
            else -> R.style.Theme_SagerNet_Black
        }
    }

    fun getAmoledDialogTheme(themeId: Int): Int {
        return when (themeId) {
            RED -> R.style.Theme_SagerNet_Dialog_Amoled_Red
            VERDANT_MINT -> R.style.Theme_SagerNet_Dialog_Amoled_VerdantMint
            BLUE -> R.style.Theme_SagerNet_Dialog_Amoled_Blue
            PURPLE, DEEP_PURPLE -> R.style.Theme_SagerNet_Dialog_Amoled_Purple
            ORANGE, DEEP_ORANGE -> R.style.Theme_SagerNet_Dialog_Amoled_Orange
            TEAL, CYAN -> R.style.Theme_SagerNet_Dialog_Amoled_Teal
            GREEN -> R.style.Theme_SagerNet_Dialog_Amoled_Green
            PINK, PINK_SSR -> R.style.Theme_SagerNet_Dialog_Amoled_Pink
            AMBER, YELLOW -> R.style.Theme_SagerNet_Dialog_Amoled_Amber
            INDIGO, LIGHT_BLUE -> R.style.Theme_SagerNet_Dialog_Amoled_Indigo
            else -> R.style.Theme_SagerNet_Dialog_Black
        }
    }

    fun getTheme(theme: Int, context: Context? = null): Int {
        return when (theme) {
            MONET -> R.style.Theme_SagerNet_Monet
            RED -> R.style.Theme_SagerNet_Red
            PINK_SSR -> R.style.Theme_SagerNet_Pink_SSR
            PINK -> R.style.Theme_SagerNet
            PURPLE -> R.style.Theme_SagerNet_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Indigo
            BLUE -> R.style.Theme_SagerNet_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_LightBlue
            CYAN -> R.style.Theme_SagerNet_Cyan
            TEAL -> R.style.Theme_SagerNet_Teal
            GREEN -> R.style.Theme_SagerNet_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_LightGreen
            LIME -> R.style.Theme_SagerNet_Lime
            YELLOW -> R.style.Theme_SagerNet_Yellow
            AMBER -> R.style.Theme_SagerNet_Amber
            ORANGE -> R.style.Theme_SagerNet_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Brown
            GREY -> R.style.Theme_SagerNet_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_BlueGrey
            BLACK -> R.style.Theme_SagerNet_Black
            VERDANT_MINT -> R.style.Theme_SagerNet_VerdantMint
            WHITE -> R.style.Theme_SagerNet_White
            LIGHT_GRAY -> R.style.Theme_SagerNet_LightGray
            else -> R.style.Theme_SagerNet_LightGray
        }
    }

    fun getDialogTheme(theme: Int, context: Context? = null): Int {
        return when (theme) {
            MONET -> R.style.Theme_SagerNet_Dialog_Monet
            RED -> R.style.Theme_SagerNet_Dialog_Red
            PINK_SSR -> R.style.Theme_SagerNet_Dialog_Pink_SSR
            PINK -> R.style.Theme_SagerNet_Dialog
            PURPLE -> R.style.Theme_SagerNet_Dialog_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_Dialog_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Dialog_Indigo
            BLUE -> R.style.Theme_SagerNet_Dialog_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_Dialog_LightBlue
            CYAN -> R.style.Theme_SagerNet_Dialog_Cyan
            TEAL -> R.style.Theme_SagerNet_Dialog_Teal
            GREEN -> R.style.Theme_SagerNet_Dialog_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_Dialog_LightGreen
            LIME -> R.style.Theme_SagerNet_Dialog_Lime
            YELLOW -> R.style.Theme_SagerNet_Dialog_Yellow
            AMBER -> R.style.Theme_SagerNet_Dialog_Amber
            ORANGE -> R.style.Theme_SagerNet_Dialog_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_Dialog_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Dialog_Brown
            GREY -> R.style.Theme_SagerNet_Dialog_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_Dialog_BlueGrey
            BLACK -> R.style.Theme_SagerNet_Dialog_Black
            VERDANT_MINT -> R.style.Theme_SagerNet_Dialog_VerdantMint
            WHITE -> R.style.Theme_SagerNet_Dialog_White
            LIGHT_GRAY -> R.style.Theme_SagerNet_Dialog_LightGray
            else -> R.style.Theme_SagerNet_Dialog_LightGray
        }
    }

    fun isSystemNight(context: Context = app): Boolean {
        val ctxUiMode = (context as? android.app.Activity)?.resources?.configuration?.uiMode
            ?: context.resources?.configuration?.uiMode
        if (ctxUiMode != null && (ctxUiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            return (ctxUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val sysUiMode = android.content.res.Resources.getSystem().configuration.uiMode
        return (sysUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun isWhiteTheme(context: Context? = null): Boolean {
        val ctx = context ?: app
        return !usingNightMode(ctx) && DataStore.appTheme == WHITE
    }

    fun isLightGrayTheme(context: Context? = null): Boolean {
        val ctx = context ?: app
        return !usingNightMode(ctx) && DataStore.appTheme == LIGHT_GRAY
    }

    fun isBlackTheme(context: Context? = null): Boolean {
        val ctx = context ?: app
        return DataStore.appTheme == BLACK || (usingNightMode(ctx) && (DataStore.amoledTheme || DataStore.appTheme in setOf(WHITE, LIGHT_GRAY)))
    }

    fun getPrimaryColor(context: Context): Int {
        if (isBlackTheme(context)) {
            return Color.WHITE
        }
        if (isWhiteTheme(context)) {
            return Color.parseColor("#212121")
        }
        if (isLightGrayTheme(context)) {
            return Color.parseColor("#1F2937")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (DataStore.appTheme == MONET || DataStore.useSystemTheme)) {
            val wallpaperColor = getSystemWallpaperColor(context)
            if (wallpaperColor != null) {
                return wallpaperColor
            }
        }
        return context.getColorAttr(R.attr.colorPrimary)
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

    fun usingNightMode(context: Context? = null): Boolean {
        val ctx = context ?: app
        if (DataStore.appTheme == BLACK) return true
        val mode = if (currentNightMode != -1) currentNightMode else DataStore.nightTheme
        return when (mode) {
            1 -> true
            2 -> false
            else -> isSystemNight(ctx)
        }
    }

    fun applyNightTheme() {
        if (DataStore.appTheme == BLACK) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            return
        }
        when (DataStore.nightTheme) {
            0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
        }
    }

}
