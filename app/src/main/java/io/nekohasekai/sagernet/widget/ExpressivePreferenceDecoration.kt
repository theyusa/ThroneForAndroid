package io.nekohasekai.sagernet.widget

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme

/**
 * Material 3 Expressive (Android 14/15 Pixel Settings) ItemDecoration.
 * Applies 20dp outer group corners, 4dp row corners, and 2dp item micro-spacing
 * without drawn divider lines. Fully harmonized with Throne's 3-tone theme system
 * and hardware-accelerated for smooth 60fps scrolling.
 */
class ExpressivePreferenceDecoration : RecyclerView.ItemDecoration() {

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val path = Path()
    private val rectF = RectF()

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION || pos !in 0 until adapter.itemCount) return

        val pref = adapter.getItem(pos)
        if (pref is PreferenceCategory) {
            val topOffset = if (pos == 0) dp2px(2) else dp2px(8)
            outRect.set(0, topOffset, 0, dp2px(2))
        } else {
            val marginH = dp2px(16)
            val isBottom = (pos == adapter.itemCount - 1 || adapter.getItem(pos + 1) is PreferenceCategory)
            val bottomMargin = if (isBottom) dp2px(4) else dp2px(2)
            outRect.set(marginH, 0, marginH, bottomMargin)
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val ctx = parent.context

        val isNight = Theme.usingNightMode(ctx)
        val isAmoled = Theme.isBlackTheme(ctx)

        // 3-Tone Dynamic Theme Harmony:
        // Use active theme's cardElevatedSurfaceColor (or colorSurface fallback)
        val cardColor = when {
            isAmoled -> 0xFF121212.toInt()
            else -> {
                val elevated = ctx.getColorAttr(R.attr.cardElevatedSurfaceColor)
                if (elevated != 0) elevated else {
                    if (isNight) 0xFF1E1F1E.toInt() else 0xFFFFFFFF.toInt()
                }
            }
        }
        cardPaint.color = cardColor

        val rBig = dp2px(20).toFloat()
        val rSmall = dp2px(4).toFloat()
        val strokeWidthPx = dp2px(1).toFloat()

        if (isAmoled) {
            strokePaint.strokeWidth = strokeWidthPx
            strokePaint.color = 0x22FFFFFF.toInt()
        }

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || pos !in 0 until adapter.itemCount) continue

            val pref = adapter.getItem(pos)
            if (pref is PreferenceCategory) continue

            val isTop = (pos == 0 || adapter.getItem(pos - 1) is PreferenceCategory)
            val isBottom = (pos == adapter.itemCount - 1 || adapter.getItem(pos + 1) is PreferenceCategory)

            val left = child.left.toFloat()
            val right = child.right.toFloat()
            val top = child.top.toFloat()
            val bottom = child.bottom.toFloat()

            val (tl, tr, br, bl) = when {
                isTop && isBottom -> listOf(rBig, rBig, rBig, rBig)
                isTop -> listOf(rBig, rBig, rSmall, rSmall)
                isBottom -> listOf(rSmall, rSmall, rBig, rBig)
                else -> listOf(rSmall, rSmall, rSmall, rSmall)
            }

            val radii = floatArrayOf(
                tl, tl,
                tr, tr,
                br, br,
                bl, bl
            )

            path.reset()
            rectF.set(left, top, right, bottom)
            path.addRoundRect(rectF, radii, Path.Direction.CW)

            c.drawPath(path, cardPaint)
            if (isAmoled) {
                c.drawPath(path, strokePaint)
            }

            // Outline clipping for smooth ripple without lag or RenderThread choking
            val stateKey = (if (isTop) 1 else 0) or (if (isBottom) 2 else 0)
            if (child.tag != stateKey) {
                child.tag = stateKey
                val roundR = if (isTop && isBottom) rBig else rSmall
                child.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, roundR)
                    }
                }
                child.clipToOutline = true
            }
        }
    }
}
