package dev.agentm.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Small native chrome shared by the terminal and browser; all targets remain at least 48 dp. */
class SessionChrome(val activity: Activity, val colors: SessionAppearance) {
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    fun ripple(color: Int = 0x00000000, radius: Int = 24) = RippleDrawable(ColorStateList.valueOf((colors.accent and 0x00FFFFFF) or 0x26000000), shape(color, radius), shape(-1, radius))
    fun label(value: String, size: Float = 14f, color: Int = colors.foreground) = TextView(activity).apply { text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL }
    fun icon(name: String, color: Int = colors.foreground): Drawable = ChromeIcon(name, color)
    fun button(name: String, description: String, action: () -> Unit) = ImageButton(activity).apply {
        setImageDrawable(icon(name)); contentDescription = description; tooltipText = description
        background = ripple(); setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); setOnClickListener { action() }
    }
    fun enabled(view: View, enabled: Boolean) { view.isEnabled = enabled; view.alpha = if (enabled) 1f else 0.32f }
    fun bar() = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0); setBackgroundColor(colors.bar)
        layoutParams = LinearLayout.LayoutParams(-1, dp(56))
    }
    fun insets(root: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, root).apply { isAppearanceLightStatusBars = !colors.dark; isAppearanceLightNavigationBars = !colors.dark }
        activity.window.isNavigationBarContrastEnforced = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
        ViewCompat.requestApplyInsets(root)
    }
    data class Item(val icon: String, val title: String, val enabled: Boolean = true, val checked: Boolean = false, val danger: Boolean = false, val action: () -> Unit)
    fun menu(anchor: View, title: String, detail: String, items: List<Item>) {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        content.addView(label(title, 14f, colors.accent).apply { setPadding(dp(16), dp(8), dp(16), 0) })
        content.addView(label(detail, 12f, colors.muted).apply { setPadding(dp(16), dp(4), dp(16), dp(12)) })
        val scroll = ScrollView(activity).apply { addView(content); isFillViewport = false }
        val popup = PopupWindow(scroll, minOf(dp(280), activity.resources.displayMetrics.widthPixels - dp(24)), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(shape(colors.bar, 24)); elevation = dp(8).toFloat(); isOutsideTouchable = true
        }
        items.forEach { item ->
            val tint = if (item.danger) colors.error else colors.foreground
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL; background = ripple(radius = 12); minimumHeight = dp(48)
                setPadding(dp(12), dp(4), dp(12), dp(4)); contentDescription = item.title
                addView(ImageView(activity).apply { setImageDrawable(icon(item.icon, tint)) }, LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(label(item.title, 14f, tint).apply { setPadding(dp(16), 0, dp(4), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                if (item.checked) addView(ImageView(activity).apply { setImageDrawable(icon("check", colors.accent)) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                isEnabled = item.enabled; alpha = if (item.enabled) 1f else 0.38f
                setOnClickListener { popup.dismiss(); item.action() }
            }
            content.addView(row)
        }
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }
    fun show(dialog: AlertDialog): AlertDialog {
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(shape(colors.container, 28))
            fun tint(view: View) {
                if (view is TextView) view.setTextColor(colors.foreground)
                if (view is ViewGroup) for (index in 0 until view.childCount) tint(view.getChildAt(index))
            }
            dialog.window?.decorView?.let(::tint)
            listOf(-1, -2, -3).forEach { dialog.getButton(it)?.setTextColor(colors.accent) }
        }
        dialog.show(); return dialog
    }
    fun confirm(title: String, message: String, action: String, run: () -> Unit) = show(AlertDialog.Builder(activity).setTitle(title).setMessage(message)
        .setNegativeButton("取消", null).setPositiveButton(action) { _, _ -> run() }.create())
}

/** Consistent 24-unit, rounded stroke icons, without system Button chrome or font glyphs. */
private class ChromeIcon(private val name: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(vararg points: Float) { val path = Path(); path.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1]); canvas.drawPath(path, paint) }
        when (name) {
            "back" -> { line(14f, 5f, 7f, 12f, 14f, 19f); line(7f, 12f, 21f, 12f) }
            "forward" -> { line(9f, 5f, 16f, 12f, 9f, 19f); line(3f, 12f, 16f, 12f) }
            "more" -> { paint.style = Paint.Style.FILL; listOf(5f, 12f, 19f).forEach { canvas.drawCircle(12f, it, 1.7f, paint) }; paint.style = Paint.Style.STROKE }
            "refresh" -> { canvas.drawArc(4f, 4f, 20f, 20f, 45f, 285f, false, paint); line(20f, 3f, 20f, 9f, 14f, 9f) }
            "desktop" -> { canvas.drawRoundRect(3f, 4f, 21f, 17f, 2f, 2f, paint); line(12f, 17f, 12f, 21f); line(8f, 21f, 16f, 21f) }
            "external" -> { line(14f, 3f, 21f, 3f, 21f, 10f); line(21f, 3f, 10f, 14f); line(10f, 4f, 4f, 4f, 4f, 20f, 20f, 20f, 20f, 14f) }
            "stop" -> canvas.drawRoundRect(5f, 5f, 19f, 19f, 2f, 2f, paint)
            "clear" -> { line(8f, 4f, 16f, 4f); line(4f, 7f, 20f, 7f); line(6f, 7f, 7f, 21f, 17f, 21f, 18f, 7f); line(10f, 11f, 10f, 17f); line(14f, 11f, 14f, 17f) }
            "copy" -> { canvas.drawRoundRect(8f, 8f, 21f, 21f, 2f, 2f, paint); line(16f, 4f, 3f, 4f, 3f, 17f) }
            "keyboard" -> { canvas.drawRoundRect(2f, 5f, 22f, 19f, 2f, 2f, paint); listOf(8f, 12f).forEach { y -> listOf(6f, 10f, 14f, 18f).forEach { x -> canvas.drawPoint(x, y, paint) } }; line(7f, 16f, 17f, 16f) }
            "folder" -> line(3f, 20f, 21f, 20f, 21f, 6f, 12f, 6f, 10f, 3f, 3f, 3f, 3f, 20f)
            "check" -> line(5f, 12f, 10f, 17f, 20f, 6f)
            "text" -> { line(4f, 19f, 10f, 5f, 16f, 19f); line(6f, 14f, 14f, 14f); line(18f, 9f, 22f, 9f); line(20f, 7f, 20f, 15f) }
            else -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 10f, 12f, 17f); canvas.drawPoint(12f, 6f, paint) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
