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
 * Material 3 Expressive design helper.
 * Generates 42dp (settings) and 36dp (drawer) circular tonal masked icon badges
 * fully harmonized with Throne's 3-tone seed theme system (Derin zemin, Kart kutusu, Canlı vurgu).
 * Fully hardware-accelerated and bug-free (protects against nested LayerDrawables).
 */
object ExpressiveSettingsHelper {

    /**
     * Unwraps any existing LayerDrawable layers to extract the original core vector drawable.
     * Prevents nested layer wrapping, accumulated insets, and disappearing icons.
     */
    fun unwrapOriginalIcon(drawable: Drawable): Drawable {
        var cur = drawable
        while (cur is LayerDrawable && cur.numberOfLayers >= 2) {
            cur = cur.getDrawable(1)
        }
        return cur
    }

    fun createMaskedIcon(
        context: Context,
        originalDrawable: Drawable,
        containerColor: Int,
        iconColor: Int,
        sizeDp: Int = 42,
        iconSizeDp: Int = 22,
    ): Drawable {
        val size = dp2px(sizeDp)
        val iconSize = dp2px(iconSizeDp)
        val inset = (size - iconSize) / 2

        val rawIcon = unwrapOriginalIcon(originalDrawable)

        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(size, size)
            setColor(containerColor)
        }

        val iconCopy = (rawIcon.constantState?.newDrawable(context.resources) ?: rawIcon).mutate()
        iconCopy.clearColorFilter()
        DrawableCompat.setTint(iconCopy, iconColor)

        return LayerDrawable(arrayOf(background, iconCopy)).apply {
            setLayerInset(1, inset, inset, inset, inset)
            setBounds(0, 0, size, size)
        }
    }

    fun applyDrawerMaskedIcons(navigation: NavigationView, context: Context) {
        val isDark = Theme.usingNightMode(context)
        val isAmoled = Theme.isBlackTheme(context)
        val themeAccent = context.getColorAttr(R.attr.colorAccent).takeIf { it != 0 && it != Color.WHITE }
            ?: context.getColorAttr(R.attr.colorPrimary).takeIf { it != 0 && it != Color.WHITE }
            ?: ContextCompat.getColor(context, R.color.material_blue_accent_200)

        val containerColor = when {
            isAmoled -> ColorUtils.setAlphaComponent(themeAccent, (255 * 0.20f).toInt())
            isDark -> ColorUtils.setAlphaComponent(themeAccent, (255 * 0.16f).toInt())
            else -> ColorUtils.blendARGB(themeAccent, Color.WHITE, 0.88f)
        }

        val tintIcons = DataStore.tintPreferenceIcons
        val iconColor = if (tintIcons) {
            if (isDark) {
                ColorUtils.blendARGB(themeAccent, Color.WHITE, 0.15f)
            } else {
                themeAccent
            }
        } else {
            if (isDark) Color.parseColor("#C4C7C5") else Color.parseColor("#444746")
        }

        navigation.itemIconTintList = null

        val menu = navigation.menu
        fun processItem(item: MenuItem) {
            val d = item.icon ?: return
            item.icon = createMaskedIcon(context, d, containerColor, iconColor, sizeDp = 36, iconSizeDp = 20)
        }

        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.hasSubMenu()) {
                val subMenu = item.subMenu ?: continue
                for (j in 0 until subMenu.size()) {
                    val subItem = subMenu.getItem(j)
                    processItem(subItem)
                }
            } else {
                processItem(item)
            }
        }
    }
}

fun Preference.applyMaskedIconBadges(context: Context, containerColor: Int, iconColor: Int) {
    if (this is PreferenceGroup) {
        for (i in 0 until preferenceCount) {
            getPreference(i).applyMaskedIconBadges(context, containerColor, iconColor)
        }
    }
    icon?.let { d ->
        icon = ExpressiveSettingsHelper.createMaskedIcon(context, d, containerColor, iconColor)
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

    for (i in list.itemDecorationCount - 1 downTo 0) {
        val deco = list.getItemDecorationAt(i)
        if (deco !is ExpressivePreferenceDecoration) {
            list.removeItemDecoration(deco)
        }
    }

    val isNight = Theme.usingNightMode(ctx)
    val isAmoled = Theme.isBlackTheme(ctx)

    // 3-Tone Dynamic Theme Harmony: Deep background (Derin zemin)
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

    // 3-Tone Seed Harmony: Vibrant theme accent (CanlÄ± vurgu)
    val themeAccent = ctx.getColorAttr(R.attr.colorAccent).takeIf { it != 0 && it != Color.WHITE }
        ?: ctx.getColorAttr(R.attr.colorPrimary).takeIf { it != 0 && it != Color.WHITE }
        ?: ContextCompat.getColor(ctx, R.color.material_blue_accent_200)

    val containerColor = when {
        isAmoled -> ColorUtils.setAlphaComponent(themeAccent, (255 * 0.20f).toInt())
        isNight -> ColorUtils.setAlphaComponent(themeAccent, (255 * 0.16f).toInt())
        else -> ColorUtils.blendARGB(themeAccent, Color.WHITE, 0.88f)
    }

    val tintIcons = DataStore.tintPreferenceIcons
    val iconColor = if (tintIcons) {
        if (isNight) {
            ColorUtils.blendARGB(themeAccent, Color.WHITE, 0.15f)
        } else {
            themeAccent
        }
    } else {
        if (isNight) Color.parseColor("#C4C7C5") else Color.parseColor("#444746")
    }

    preferenceScreen?.applyMaskedIconBadges(ctx, containerColor, iconColor)
}
