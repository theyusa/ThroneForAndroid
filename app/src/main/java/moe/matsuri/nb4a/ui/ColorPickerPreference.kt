package moe.matsuri.nb4a.ui

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.TypedArrayUtils
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import kotlin.math.roundToInt

class ColorPickerPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context,
        androidx.preference.R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    )
) : Preference(context, attrs, defStyle) {

    data class PresetTheme(
        val id: Int,
        val name: String,
        val color: Int
    )

    private fun resolveSwatchColor(themeId: Int): Int {
        return when (themeId) {
            Theme.BLACK -> Color.BLACK
            Theme.WHITE -> Color.WHITE
            Theme.LIGHT_GRAY -> Color.parseColor("#F5F5F7")
            Theme.MONET -> Theme.getSystemWallpaperColor(context) ?: Color.parseColor("#3664E8")
            Theme.VERDANT_MINT -> Color.parseColor("#00B96B")
            Theme.BLUE -> Color.parseColor("#2196F3")
            Theme.INDIGO -> Color.parseColor("#3F51B5")
            Theme.LIGHT_BLUE -> Color.parseColor("#03A9F4")
            Theme.CYAN -> Color.parseColor("#00BCD4")
            Theme.TEAL -> Color.parseColor("#009688")
            Theme.GREEN -> Color.parseColor("#4CAF50")
            Theme.LIGHT_GREEN -> Color.parseColor("#8BC34A")
            Theme.LIME -> Color.parseColor("#CDDC39")
            Theme.YELLOW -> Color.parseColor("#FFEB3B")
            Theme.AMBER -> Color.parseColor("#FFC107")
            Theme.ORANGE -> Color.parseColor("#FF9800")
            Theme.DEEP_ORANGE -> Color.parseColor("#FF5722")
            Theme.RED -> Color.parseColor("#F44336")
            Theme.PINK_SSR -> Color.parseColor("#FB7299")
            Theme.PINK -> Color.parseColor("#E91E63")
            Theme.PURPLE -> Color.parseColor("#9C27B0")
            Theme.DEEP_PURPLE -> Color.parseColor("#673AB7")
            Theme.BROWN -> Color.parseColor("#795548")
            Theme.BLUE_GREY -> Color.parseColor("#607D8B")
            Theme.GREY -> Color.parseColor("#9E9E9E")
            Theme.CUSTOM -> 0xFF000000.toInt() or DataStore.customThemeColor
            else -> context.getColorAttr(R.attr.colorPrimary)
        }
    }

    private fun getThemeName(themeId: Int, isTr: Boolean): String {
        return when (themeId) {
            Theme.WHITE -> if (isTr) "Saf Beyaz" else "Pure White"
            Theme.LIGHT_GRAY -> if (isTr) "Açık Gri" else "Light Gray"
            Theme.MONET -> "Material You"
            Theme.VERDANT_MINT -> if (isTr) "Nane Yeşili" else "Verdant Mint"
            Theme.BLUE -> if (isTr) "Klasik Mavi" else "Classic Blue"
            Theme.RED -> if (isTr) "Alev Kırmızısı" else "Flame Red"
            Theme.DEEP_PURPLE -> if (isTr) "Koyu Mor" else "Deep Purple"
            Theme.PURPLE -> if (isTr) "Lavanta Moru" else "Purple"
            Theme.DEEP_ORANGE -> if (isTr) "Mercan" else "Coral Orange"
            Theme.ORANGE -> if (isTr) "Canlı Turuncu" else "Vivid Orange"
            Theme.AMBER -> if (isTr) "Kehribar" else "Amber"
            Theme.CYAN -> if (isTr) "Turkuaz" else "Cyan"
            Theme.TEAL -> if (isTr) "Su Yeşili" else "Teal"
            Theme.GREEN -> if (isTr) "Orman Yeşili" else "Forest Green"
            Theme.INDIGO -> if (isTr) "İndigo" else "Indigo"
            Theme.LIGHT_BLUE -> if (isTr) "Gök Mavisi" else "Sky Blue"
            Theme.PINK_SSR -> if (isTr) "Kiraz Çiçeği" else "Sakura Pink"
            Theme.PINK -> if (isTr) "Neon Pembe" else "Neon Pink"
            Theme.BROWN -> if (isTr) "Mocha Kahve" else "Mocha Brown"
            Theme.BLUE_GREY -> if (isTr) "Mavi Gri" else "Blue Grey"
            Theme.GREY -> if (isTr) "Antrasit Gri" else "Graphite Grey"
            Theme.LIGHT_GREEN -> if (isTr) "Açık Yeşil" else "Light Green"
            Theme.LIME -> if (isTr) "Limon Yeşili" else "Lime"
            Theme.YELLOW -> if (isTr) "Sarı" else "Yellow"
            Theme.CUSTOM -> if (isTr) "Özel Tohum Rengi" else "Custom Seed Color"
            else -> if (isTr) "Nane Yeşili" else "Verdant Mint"
        }
    }

    override fun getSummary(): CharSequence? {
        val activeThemeId = if (DataStore.useSystemTheme) Theme.MONET else DataStore.appTheme
        val isTr = context.resources.configuration.locales[0]?.language == "tr"
        return getThemeName(activeThemeId, isTr)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as? LinearLayout ?: return
        widgetFrame.removeAllViews()

        val activeThemeId = if (DataStore.useSystemTheme) Theme.MONET else DataStore.appTheme
        val displayColor = resolveSwatchColor(activeThemeId)

        val factor = context.resources.displayMetrics.density
        val size = (36 * factor).roundToInt()
        val widgetIv = ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setImageDrawable(getColorBadgeDrawable(context.resources, displayColor))
        }
        widgetFrame.addView(widgetIv)
        widgetFrame.visibility = android.view.View.VISIBLE
    }

    private fun getColorBadgeDrawable(res: Resources, color: Int): Drawable {
        val factor = res.displayMetrics.density
        val strokeColor = when (color) {
            Color.WHITE -> Color.parseColor("#CCCCCC")
            Color.BLACK -> Color.parseColor("#444444")
            Color.parseColor("#F5F5F7") -> Color.parseColor("#CBD5E1")
            else -> Color.parseColor("#33888888")
        }

        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1.5f * factor).roundToInt().coerceAtLeast(1), strokeColor)
        }
    }

    override fun onClick() {
        super.onClick()

        lateinit var dialog: AlertDialog

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(16))
        }

        val scroll = NestedScrollView(context).apply {
            addView(rootLayout)
        }

        val currentThemeId = if (DataStore.useSystemTheme) Theme.MONET else DataStore.appTheme

        fun applyTheme(themeId: Int) {
            persistInt(themeId)
            DataStore.appTheme = themeId
            DataStore.useSystemTheme = (themeId == Theme.MONET)
            if (themeId in setOf(Theme.WHITE, Theme.LIGHT_GRAY) && Theme.usingNightMode(context)) {
                DataStore.nightTheme = 2
                Theme.currentNightMode = 2
            }
            Theme.applyNightTheme()
            dialog.dismiss()
            notifyChanged()
            callChangeListener(themeId)
            (context as? Activity)?.let {
                ActivityCompat.recreate(it)
            }
        }

        val isTr = context.resources.configuration.locales[0]?.language == "tr"
        val seedThemes = mutableListOf<PresetTheme>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            seedThemes.add(
                PresetTheme(Theme.MONET, "Material You", resolveSwatchColor(Theme.MONET))
            )
        }

        seedThemes.addAll(
            listOf(
                PresetTheme(Theme.WHITE, if (isTr) "Saf Beyaz" else "Pure White", Color.WHITE),
                PresetTheme(Theme.LIGHT_GRAY, if (isTr) "Açık Gri" else "Light Gray", Color.parseColor("#F5F5F7")),
                PresetTheme(Theme.VERDANT_MINT, if (isTr) "Nane Yeşili" else "Verdant Mint", resolveSwatchColor(Theme.VERDANT_MINT)),
                PresetTheme(Theme.BLUE, if (isTr) "Klasik Mavi" else "Classic Blue", resolveSwatchColor(Theme.BLUE)),
                PresetTheme(Theme.RED, if (isTr) "Alev Kırmızısı" else "Flame Red", resolveSwatchColor(Theme.RED)),
                PresetTheme(Theme.DEEP_PURPLE, if (isTr) "Koyu Mor" else "Deep Purple", resolveSwatchColor(Theme.DEEP_PURPLE)),
                PresetTheme(Theme.PURPLE, if (isTr) "Lavanta Moru" else "Purple", resolveSwatchColor(Theme.PURPLE)),
                PresetTheme(Theme.DEEP_ORANGE, if (isTr) "Mercan" else "Coral Orange", resolveSwatchColor(Theme.DEEP_ORANGE)),
                PresetTheme(Theme.ORANGE, if (isTr) "Canlı Turuncu" else "Vivid Orange", resolveSwatchColor(Theme.ORANGE)),
                PresetTheme(Theme.AMBER, if (isTr) "Kehribar" else "Amber", resolveSwatchColor(Theme.AMBER)),
                PresetTheme(Theme.CYAN, if (isTr) "Turkuaz" else "Cyan", resolveSwatchColor(Theme.CYAN)),
                PresetTheme(Theme.TEAL, if (isTr) "Su Yeşili" else "Teal", resolveSwatchColor(Theme.TEAL)),
                PresetTheme(Theme.GREEN, if (isTr) "Orman Yeşili" else "Forest Green", resolveSwatchColor(Theme.GREEN)),
                PresetTheme(Theme.INDIGO, if (isTr) "İndigo" else "Indigo", resolveSwatchColor(Theme.INDIGO)),
                PresetTheme(Theme.LIGHT_BLUE, if (isTr) "Gök Mavisi" else "Sky Blue", resolveSwatchColor(Theme.LIGHT_BLUE)),
                PresetTheme(Theme.PINK_SSR, if (isTr) "Kiraz Çiçeği" else "Sakura Pink", resolveSwatchColor(Theme.PINK_SSR)),
                PresetTheme(Theme.PINK, if (isTr) "Neon Pembe" else "Neon Pink", resolveSwatchColor(Theme.PINK)),
                PresetTheme(Theme.BROWN, if (isTr) "Mocha Kahve" else "Mocha Brown", resolveSwatchColor(Theme.BROWN)),
                PresetTheme(Theme.BLUE_GREY, if (isTr) "Mavi Gri" else "Blue Grey", resolveSwatchColor(Theme.BLUE_GREY)),
                PresetTheme(Theme.GREY, if (isTr) "Antrasit Gri" else "Graphite Grey", resolveSwatchColor(Theme.GREY))
            )
        )

        for (preset in seedThemes) {
            val isSelected = currentThemeId == preset.id
            val strokeCol = if (isSelected) {
                if (preset.color == Color.BLACK) Color.WHITE else preset.color
            } else {
                Color.parseColor("#20888888")
            }
            val bgCol = if (isSelected) {
                if (preset.color == Color.BLACK) Color.parseColor("#15FFFFFF")
                else ColorUtils.setAlphaComponent(preset.color, 24)
            } else Color.TRANSPARENT

            val card = MaterialCardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp2px(4))
                }
                radius = dp2px(10).toFloat()
                cardElevation = 0f
                strokeWidth = if (isSelected) dp2px(2) else dp2px(1)
                strokeColor = strokeCol
                setCardBackgroundColor(bgCol)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    applyTheme(preset.id)
                }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp2px(12), dp2px(8), dp2px(12), dp2px(8))

                    val iv = ImageView(context).apply {
                        val sz = dp2px(26)
                        layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                            marginEnd = dp2px(12)
                        }
                        setImageDrawable(getColorBadgeDrawable(context.resources, preset.color))
                    }
                    addView(iv)

                    val titleView = TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        text = preset.name
                        textSize = 14f
                        setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                        setTextColor(context.getColorAttr(android.R.attr.textColorPrimary))
                    }
                    addView(titleView)

                    if (isSelected) {
                        val checkIv = ImageView(context).apply {
                            val sz = dp2px(20)
                            layoutParams = LinearLayout.LayoutParams(sz, sz)
                            val checkTint = if (preset.color == Color.BLACK) Color.WHITE else preset.color
                            val checkDrawable = ResourcesCompat.getDrawable(context.resources, R.drawable.ic_baseline_check_circle_24, null)!!.mutate()
                            DrawableCompat.setTint(checkDrawable, checkTint)
                            setImageDrawable(checkDrawable)
                        }
                        addView(checkIv)
                    }
                }
                addView(row)
            }
            rootLayout.addView(card)
        }

        // Custom Seed Color Section
        val isCustomSelected = currentThemeId == Theme.CUSTOM
        val customColorInt = 0xFF000000.toInt() or DataStore.customThemeColor
        val customCard = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp2px(2), 0, dp2px(4))
            }
            radius = dp2px(10).toFloat()
            cardElevation = 0f
            strokeWidth = if (isCustomSelected) dp2px(2) else dp2px(1)
            strokeColor = if (isCustomSelected) customColorInt else Color.parseColor("#20888888")
            setCardBackgroundColor(
                if (isCustomSelected) ColorUtils.setAlphaComponent(customColorInt, 24)
                else Color.TRANSPARENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val input = EditText(context).apply {
                    hint = context.getString(R.string.custom_color_hex_hint)
                    inputType = InputType.TYPE_CLASS_TEXT
                    val savedHex = String.format("#%06X", 0xFFFFFF and DataStore.customThemeColor)
                    setText(savedHex)
                    setSelection(text.length)
                }
                val container = LinearLayout(context).apply {
                    setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
                    addView(input, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                MaterialAlertDialogBuilder(context)
                    .setTitle(context.getString(R.string.custom_color_title))
                    .setView(container)
                    .setPositiveButton(context.getString(R.string.custom_color_apply)) { _, _ ->
                        val hexStr = input.text.toString().trim()
                        try {
                            val parsed = Color.parseColor(if (hexStr.startsWith("#")) hexStr else "#$hexStr")
                            DataStore.customThemeColor = parsed
                            val mappedTheme = Theme.getClosestThemeForColor(parsed)
                            applyTheme(mappedTheme)
                        } catch (_: Throwable) {
                            Toast.makeText(context, "Invalid HEX code", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }

            val customRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp2px(12), dp2px(8), dp2px(12), dp2px(8))

                val customIv = ImageView(context).apply {
                    val sz = dp2px(26)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = dp2px(12)
                    }
                    setImageDrawable(getColorBadgeDrawable(context.resources, customColorInt))
                }
                addView(customIv)

                val titleView = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    val savedHex = String.format("#%06X", 0xFFFFFF and DataStore.customThemeColor)
                    text = "${context.getString(R.string.custom_color_title)} ($savedHex)"
                    textSize = 14f
                    setTypeface(null, if (isCustomSelected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(context.getColorAttr(android.R.attr.textColorPrimary))
                }
                addView(titleView)

                if (isCustomSelected) {
                    val checkIv = ImageView(context).apply {
                        val sz = dp2px(20)
                        layoutParams = LinearLayout.LayoutParams(sz, sz)
                        val checkDrawable = ResourcesCompat.getDrawable(context.resources, R.drawable.ic_baseline_check_circle_24, null)!!.mutate()
                        DrawableCompat.setTint(checkDrawable, customColorInt)
                        setImageDrawable(checkDrawable)
                    }
                    addView(checkIv)
                }
            }
            addView(customRow)
        }
        rootLayout.addView(customCard)

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
