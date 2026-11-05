package moe.matsuri.nb4a

import android.content.Context
import android.graphics.Color
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme

// Settings for all protocols
object Protocols {

    // Display

    fun Context.getProtocolColor(type: String): Int {
        val t = type.lowercase()
        return when {
            t == "balancer" -> Color.parseColor("#FF9800")
            t == "chain" -> Color.parseColor("#AB47BC")
            Theme.isWhiteTheme(this) -> getColorAttr(R.attr.colorAccent)
            Theme.isBlackTheme(this) -> {
                val accent = getColorAttr(R.attr.colorAccent)
                if (accent == Color.WHITE) Color.parseColor("#F5F5F5") else accent
            }
            else -> getColorAttr(R.attr.colorPrimary)
        }
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout_error)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            else -> msg
        }
    }

}
