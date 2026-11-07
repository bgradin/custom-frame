package com.bgradin.customframe.ui

import android.text.TextWatcher
import androidx.core.widget.doAfterTextChanged
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.doOnLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.bgradin.customframe.R
import com.google.android.material.button.MaterialButton

/** Bottom-anchored text entry over the settings screen, with remote focus contained in the sheet. */
internal class InlineKeyboard(private val host: FrameLayout, private val content: ViewGroup) {
    private var panel: LinearLayout? = null
    private var overlay: FrameLayout? = null
    private var sheet: ScrollView? = null
    private var scrim: View? = null
    private var target: EditText? = null
    private var upper = false
    private var mode = 0
    private var closing = false
    private var previewWatcher: TextWatcher? = null
    private var previousFocusability = content.descendantFocusability
    private var previousAccessibility = content.importantForAccessibility
    private var previousFocusable = content.isFocusable
    private var previousTouchFocusable = content.isFocusableInTouchMode

    fun close(): Boolean {
        val active = overlay ?: return false
        if (closing) return true
        closing = true
        val activeSheet = sheet ?: return true
        activeSheet.animate().cancel()
        scrim?.animate()?.alpha(0f)?.setDuration(180)?.start()
        activeSheet.animate().translationY(activeSheet.height.toFloat()).alpha(0f)
            .setDuration(180).setInterpolator(AccelerateInterpolator())
            .withEndAction { if (overlay === active) dismiss(restoreFocus = true) }.start()
        return true
    }

    /** Cancel animations and detach immediately when the owning view is destroyed. */
    fun dispose() = dismiss(restoreFocus = false)

    private fun dismiss(restoreFocus: Boolean) {
        val active = overlay ?: return
        sheet?.animate()?.cancel()
        scrim?.animate()?.cancel()
        previewWatcher?.let { target?.removeTextChangedListener(it) }
        previewWatcher = null
        host.removeView(active)
        content.descendantFocusability = previousFocusability
        content.importantForAccessibility = previousAccessibility
        content.isFocusableInTouchMode = previousTouchFocusable
        content.isFocusable = previousFocusable
        overlay = null
        sheet = null
        scrim = null
        panel = null
        closing = false
        val field = target
        target = null
        if (restoreFocus) field?.requestFocus()
    }

    fun open(field: EditText, title: String) {
        dismiss(restoreFocus = false)
        target = field
        mode = 0
        upper = false
        previousFocusability = content.descendantFocusability
        previousAccessibility = content.importantForAccessibility
        previousFocusable = content.isFocusable
        previousTouchFocusable = content.isFocusableInTouchMode
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        content.isFocusableInTouchMode = false
        content.isFocusable = false
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val layer = FrameLayout(host.context).apply {
            isClickable = true
            isFocusable = false
            setOnClickListener { close() }
        }
        overlay = layer
        scrim = View(host.context).apply {
            setBackgroundColor(0x99000000.toInt())
            alpha = 0f
        }.also { layer.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        val surface = ScrollView(host.context).apply {
            setBackgroundResource(R.drawable.bg_keyboard_sheet)
            isFillViewport = true
            isClickable = true
            clipToOutline = true
            elevation = dp(24).toFloat()
            alpha = 0f
        }
        sheet = surface
        panel = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            clipChildren = false
            clipToPadding = false
        }
        surface.addView(panel, ViewGroup.LayoutParams(-1, -2))
        // Limit height on smaller displays; keys remain reachable by scrolling with the remote.
        val availableHeight = host.height.takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels
        val height = minOf(dp(440), (availableHeight * 0.85f).toInt())
        layer.addView(surface, FrameLayout.LayoutParams(-1, height, Gravity.BOTTOM))
        host.addView(layer, FrameLayout.LayoutParams(-1, -1))
        androidx.core.view.ViewCompat.setAccessibilityPaneTitle(surface, title)
        build(title)
        surface.doOnLayout {
            if (overlay !== layer || closing) return@doOnLayout
            surface.translationY = surface.height.toFloat()
            surface.scaleX = 0.98f
            surface.pivotY = surface.height.toFloat()
            surface.animate().translationY(0f).scaleX(1f).alpha(1f)
                .setDuration(300).setInterpolator(OvershootInterpolator(0.65f)).start()
            scrim?.animate()?.alpha(1f)?.setDuration(220)?.start()
        }
    }

    private fun dp(value: Int) = (value * (target?.resources?.displayMetrics?.density ?: 1f)).toInt()

    private fun build(title: String, focusLabel: String? = null) {
        val field = target ?: return
        val container = panel ?: return
        fun insert(value: String) {
            val start = field.selectionStart.coerceAtLeast(0)
            val end = field.selectionEnd.coerceAtLeast(start)
            field.text.replace(start, end, value)
            field.setSelection(start + value.length)
        }
        container.removeAllViews()
        container.addView(TextView(field.context).apply {
            text = title
            setTextAppearance(R.style.Settings_SectionTitle)
        })
        val preview = TextView(field.context).apply {
            setTextAppearance(R.style.Settings_Description)
            maxLines = 2
            // Keep password and token entry masked, just like the source field.
            transformationMethod = field.transformationMethod
        }
        fun updatePreview() { preview.text = field.text.toString() }
        updatePreview()
        previewWatcher?.let { field.removeTextChangedListener(it) }
        previewWatcher = field.doAfterTextChanged { updatePreview() }
        container.addView(preview)
        var first: View? = null
        var focus: View? = null
        fun row(keys: List<Pair<String, () -> Unit>>) {
            val line = LinearLayout(field.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
            }
            keys.forEach { (label, action) ->
                val key = MaterialButton(field.context, null, 0).apply {
                    text = label
                    isAllCaps = false
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                    includeFontPadding = false
                    insetTop = 0
                    insetBottom = 0
                    textSize = 16f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(0, 0, 0, 0)
                    cornerRadius = dp(10)
                    backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(context, R.color.settings_key_fill)
                    setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.settings_key_text))
                    strokeWidth = dp(2)
                    strokeColor = androidx.core.content.ContextCompat.getColorStateList(context, R.color.settings_key_stroke)
                    isFocusableInTouchMode = true
                    setOnFocusChangeListener { _, focused ->
                        animate().scaleX(if (focused) 1.06f else 1f)
                            .scaleY(if (focused) 1.06f else 1f).setDuration(140).start()
                        elevation = dp(if (focused) 8 else 0).toFloat()
                    }
                    setOnClickListener { action(); updatePreview() }
                }
                if (first == null) first = key
                if (label == focusLabel) focus = key
                line.addView(key, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            container.addView(line)
        }
        val characters = when (mode) {
            0 -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            1 -> listOf("1234567890", "-/:;()$&@\"", ".,?!'")
            else -> listOf("[]{}#%^*+=", "_\\|~<>€£¥•", ".,?!'")
        }
        characters.forEach { chars ->
            row(chars.map { ch ->
                val value = if (upper && mode == 0) ch.uppercaseChar() else ch
                value.toString() to { insert(value.toString()) }
            })
        }
        val modeLabel = when (mode) { 0 -> "123"; 1 -> "#+="; else -> "ABC" }
        row(listOf(
            modeLabel to { mode = (mode + 1) % 3; build(title, when (mode) { 0 -> "123"; 1 -> "#+="; else -> "ABC" }) },
            field.context.getString(R.string.keyShift) to { upper = !upper; build(title, field.context.getString(R.string.keyShift)) },
            field.context.getString(R.string.keySpace) to { insert(" ") }
        ))
        row(listOf(
            field.context.getString(R.string.keyBackspace) to {
                val position = field.selectionStart.coerceAtLeast(0)
                val end = field.selectionEnd.coerceAtLeast(position)
                if (end > position) field.text.delete(position, end)
                else if (position > 0) {
                    val start = Character.offsetByCodePoints(field.text, position, -1)
                    field.text.delete(start, position)
                }
                Unit
            },
            field.context.getString(R.string.keyDone) to { close(); Unit }
        ))
        (focus ?: first)?.requestFocus()
    }
}
