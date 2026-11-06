package io.nekohasekai.sagernet.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.MenuItem
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.android.material.navigation.NavigationView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.ExpressivePreferenceDecoration

/**
 * Material 3 Expressive (Android 14/15 Pixel Settings) design helper.
 * Generates 42dp (settings) and 36dp (drawer) circular pastel masked icon badges
 * harmonized with Throne's 3-tone seed theme system. Fully hardware-accelerated.
 */
object ExpressiveSettingsHelper {

    fun resolveHarmonizedColor(key: String?, baseHue: Float): Int {
        val hueOffset = when (key) {
            "settingsGeneral" -> 0f
            "settingsAppearance" -> 30f
            "settingsInbound" -> 15f
            "settingsTun" -> 180f       // Complementary
            "settingsRouting" -> 120f   // Triadic green
            "settingsDns" -> 210f       // Analogous blue
            "settingsPresets" -> 270f   // Lavender
            "settingsTesting" -> 45f    // Warm amber
            "settingsSubscriptions" -> 160f // Cyan
            "settingsCore" -> 280f      // Violet
            "settingsDiagnostics" -> 350f
            "settingsBackup" -> 200f
            "resetSettings" -> 0f
            "serviceMode" -> 0f
            "autoConnect" -> 120f
            "alwaysOnVpn" -> 180f
            "theme" -> 30f
            "nightMode" -> 210f
            "tintPreferenceIcons" -> 0f
            "language" -> 60f
            "alwaysShowAddress" -> 45f
            "compactProfileRows" -> 160f
            "compactGroupCards" -> 270f
            "showConfigSecurity" -> 210f
            // Drawer items
            "settingsProfiles" -> 0f
            "settingsGroups" -> 30f
            "drawerItem" -> 120f
            else -> {
                if (key != null) (Math.abs(key.hashCode()) % 360).toFloat() else 0f
            }
        }
        val finalHue = (baseHue + hueOffset) % 360f
        return Color.HSVToColor(floatArrayOf(finalHue, 0.60f, 0.90f))
    }

    fun createMaskedIcon(
        originalDrawable: Drawable,
        containerColor: Int,
        iconColor: Int,
        sizeDp: Int = 42,
        iconSizeDp: Int = 22,
    ): Drawable {
        val size = dp2px(sizeDp)
        val iconSize = dp2px(iconSizeDp)
        val inset = (size - iconSize) / 2

        // Avoid wrapping LayerDrawables inside LayerDrawables:
        // Always extract layer 1 (the underlying raw vector icon)
        val rawIcon = if (originalDrawable is LayerDrawable && originalDrawable.numberOfLayers == 2) {
            originalDrawable.getDrawable(1)
        } else {
            originalDrawable
        }

        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(size, size)
            setColor(containerColor)
        }

        val iconCopy = rawIcon.constantState?.newDrawable()?.mutate() ?: rawIcon.mutate()
        iconCopy.clearColorFilter()
        DrawableCompat.setTint(iconCopy, iconColor)

        return LayerDrawable(arrayOf(background, iconCopy)).apply {
            setLayerInset(1, inset, inset, inset, inset)
            setBounds(0, 0, size, size)
        }
    }

    fun applyDrawerMaskedIcons(navigation: NavigationView, context: Context) {
        val isDark = Theme.usingNightMode(context)
        val themeAccent = context.getColorAttr(R.attr.colorAccent).takeIf { it != 0 && it != Color.WHITE }
            ?: context.getColorAttr(R.attr.colorPrimary).takeIf { it != 0 && it != Color.WHITE }
            ?: ContextCompat.getColor(context, R.color.material_blue_accent_200)
        val hsv = FloatArray(3)
        Color.colorToHSV(themeAccent, hsv)
        val baseHue = hsv[0]
        val tintIcons = DataStore.tintPreferenceIcons

        navigation.itemIconTintList = null

        val menu = navigation.menu
        fun processItem(item: MenuItem, key: String) {
            val d = item.icon ?: return
            val harmonizedColor = resolveHarmonizedColor(key, baseHue)
            val containerColor = if (isDark) {
                ColorUtils.setAlphaComponent(harmonizedColor, (255 * 0.24f).toInt())
            } else {
                ColorUtils.blendARGB(harmonizedColor, Color.WHITE, 0.82f)
            }
            val iconColor = if (tintIcons) {
                if (isDark) {
                    ColorUtils.blendARGB(harmonizedColor, Color.WHITE, 0.25f)
                } else {
                    ColorUtils.blendARGB(harmonizedColor, Color.BLACK, 0.30f)
                }
            } else {
                if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#444746")
            }
            item.icon = createMaskedIcon(d, containerColor, iconColor, sizeDp = 36, iconSizeDp = 20)
        }

        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.hasSubMenu()) {
                val subMenu = item.subMenu ?: continue
                for (j in 0 until subMenu.size()) {
                    val subItem = subMenu.getItem(j)
                    val key = resolveDrawerKey(subItem.itemId)
                    processItem(subItem, key)
                }
            } else {
                val key = resolveDrawerKey(item.itemId)
                processItem(item, key)
            }
        }
    }

    private fun resolveDrawerKey(id: Int): String {
        return when (id) {
            R.id.nav_configuration -> "settingsProfiles"
            R.id.nav_group -> "settingsGroups"
            R.id.nav_route -> "settingsRouting"
            R.id.nav_settings -> "settingsGeneral"
            R.id.nav_logcat -> "settingsDiagnostics"
            R.id.nav_live_graphs -> "settingsTesting"
            R.id.nav_traffic -> "settingsTun"
            R.id.nav_tools -> "settingsPresets"
            R.id.nav_faq -> "settingsDns"
            R.id.nav_about -> "settingsAppearance"
            else -> "drawerItem_$id"
        }
    }
}

fun Preference.applyMaskedIconBadges(isDark: Boolean, baseHue: Float, tintIcons: Boolean) {
    if (this is PreferenceGroup) {
        for (i in 0 until preferenceCount) {
            getPreference(i).applyMaskedIconBadges(isDark, baseHue, tintIcons)
        }
    }
    icon?.let { d ->
        val harmonizedColor = ExpressiveSettingsHelper.resolveHarmonizedColor(key, baseHue)
        val containerColor = if (isDark) {
            ColorUtils.setAlphaComponent(harmonizedColor, (255 * 0.24f).toInt())
        } else {
            ColorUtils.blendARGB(harmonizedColor, Color.WHITE, 0.82f)
        }
        val iconColor = if (tintIcons) {
            if (isDark) {
                ColorUtils.blendARGB(harmonizedColor, Color.WHITE, 0.25f)
            } else {
                ColorUtils.blendARGB(harmonizedColor, Color.BLACK, 0.30f)
            }
        } else {
            if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#444746")
        }

        icon = ExpressiveSettingsHelper.createMaskedIcon(d, containerColor, iconColor)
    }
}

fun PreferenceFragmentCompat.applyExpressiveSettingsStyle() {
    val ctx = context ?: return
    val list = listView ?: return

    if (list.layoutManager !is FixedLinearLayoutManager) {
        list.layoutManager = FixedLinearLayoutManager(list)
    }

    // Obliterate default divider lines completely
    setDivider(null)
    setDividerHeight(0)

    val isNight = Theme.usingNightMode(ctx)
    val isAmoled = Theme.isBlackTheme(ctx)

    // 3-Tone Dynamic Theme Harmony: Deep background
    val screenBg = when {
        isAmoled -> 0xFF000000.toInt()
        else -> {
            val bg = ctx.getColorAttr(android.R.attr.colorBackground)
            if (bg != 0) bg else if (isNight) 0xFF121413.toInt() else 0xFFF0F4F1.toInt()
        }
    }
    list.setBackgroundColor(screenBg)

    var hasExpressiveDeco = false
    for (i in 0 until list.itemDecorationCount) {
        if (list.getItemDecorationAt(i) is ExpressivePreferenceDecoration) {
            hasExpressiveDeco = true
            break
        }
    }
    if (!hasExpressiveDeco) {
        list.addItemDecoration(ExpressivePreferenceDecoration())
    }

    // Resolve theme seed hue for harmonious icon palette
    val themeAccent = ctx.getColorAttr(R.attr.colorAccent).takeIf { it != 0 && it != Color.WHITE }
        ?: ctx.getColorAttr(R.attr.colorPrimary).takeIf { it != 0 && it != Color.WHITE }
        ?: ContextCompat.getColor(ctx, R.color.material_blue_accent_200)
    val hsv = FloatArray(3)
    Color.colorToHSV(themeAccent, hsv)
    val baseHue = hsv[0]

    val tintIcons = DataStore.tintPreferenceIcons
    preferenceScreen?.applyMaskedIconBadges(isNight, baseHue, tintIcons)
}
