package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.applyTopInset

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    companion object {
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    private var lastUseSystemTheme: Boolean = false
    private var lastWallpaperColor: Int? = null
    private var lastAppTheme: Int = 0
    private var lastNightTheme: Int = 0
    private var lastUsingNight: Boolean = false

    var lightStatusBar = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        lastUseSystemTheme = DataStore.useSystemTheme
        lastWallpaperColor = if (DataStore.useSystemTheme) Theme.getSystemWallpaperColor(this) else null
        lastAppTheme = DataStore.appTheme
        lastNightTheme = DataStore.nightTheme
        lastUsingNight = Theme.usingNightMode(this)

        Theme.applyNightTheme()
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        if (!isDialog) enableEdgeToEdge(statusBarStyle(), navigationBarStyle())

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode
    }

    private fun statusBarStyle(): SystemBarStyle {
        val isDark = Theme.isBlackTheme(this)
        val isLightSurface = !isDark && ColorUtils.calculateLuminance(getColorAttr(R.attr.colorSurface)) > 0.45
        lightStatusBar = isLightSurface
        return if (lightStatusBar) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
    }

    private fun navigationBarStyle() = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { Theme.usingNightMode(this) }

    override fun onResume() {
        super.onResume()
        val currentWallpaperColor = if (DataStore.useSystemTheme) Theme.getSystemWallpaperColor(this) else null
        val currentUsingNight = Theme.usingNightMode(this)
        if (lastUseSystemTheme != DataStore.useSystemTheme ||
            (DataStore.useSystemTheme && lastWallpaperColor != currentWallpaperColor) ||
            (!DataStore.useSystemTheme && lastAppTheme != DataStore.appTheme) ||
            lastNightTheme != DataStore.nightTheme ||
            lastUsingNight != currentUsingNight) {
            ActivityCompat.recreate(this)
        }
    }

    override fun onContentChanged() {
        super.onContentChanged()
        findViewById<AppBarLayout>(R.id.appbar)?.applyTopInset()
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)
        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG)

}
