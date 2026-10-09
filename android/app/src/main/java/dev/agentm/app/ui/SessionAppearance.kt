package dev.agentm.app.ui

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

/** Colors come from the same Material tonal scheme as the workbench, never from Agent pages. */
class SessionAppearance(context: Context, terminal: Boolean = false) {
    private val prefs = context.getSharedPreferences("session-appearance", Context.MODE_PRIVATE)
    val dark = terminal || prefs.getBoolean("dark", false)
    private fun color(key: String, fallback: String) = Color.parseColor(prefs.getString(key, fallback))
    val background = if (terminal) color("--term-bg", "#121016") else color("--md-surface", if (dark) "#141218" else "#FEF7FF")
    val bar = if (terminal) color("--term-bar", "#1B181F") else color("--md-surface-container-low", if (dark) "#1D1B20" else "#F7F2FA")
    val container = if (terminal) color("--term-key", "#26232B") else color("--md-surface-container-high", if (dark) "#2B2930" else "#ECE6F0")
    val foreground = if (terminal) color("--term-fg", "#EAE1ED") else color("--md-on-surface", if (dark) "#E6E0E9" else "#1D1B20")
    val muted = if (terminal) color("--term-dim", "#ADA3BA") else color("--md-on-surface-variant", if (dark) "#CAC4D0" else "#49454F")
    val accent = if (terminal) color("--term-accent", "#D0BCFF") else color("--md-primary", if (dark) "#D0BCFF" else "#6750A4")
    val error = if (terminal) color("--term-err", "#FFB4AB") else color("--md-error", if (dark) "#FFB4AB" else "#B3261E")

    companion object {
        private val keys = setOf("--md-surface", "--md-surface-container-low", "--md-surface-container-high", "--md-on-surface", "--md-on-surface-variant", "--md-primary", "--md-error",
            "--term-bg", "--term-bar", "--term-key", "--term-fg", "--term-dim", "--term-accent", "--term-err")
        fun save(context: Context, body: JSONObject) {
            val colors = body.optJSONObject("colors") ?: return
            val edit = context.getSharedPreferences("session-appearance", Context.MODE_PRIVATE).edit().putBoolean("dark", body.optBoolean("dark"))
            keys.forEach { key -> colors.optString(key).takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.let { edit.putString(key, it) } }
            edit.apply()
        }
    }
}
