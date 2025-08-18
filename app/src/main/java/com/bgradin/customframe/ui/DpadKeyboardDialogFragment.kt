package com.bgradin.customframe.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isEmpty
import androidx.fragment.app.DialogFragment
import com.bgradin.customframe.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

class DpadKeyboardDialogFragment : DialogFragment() {
    private lateinit var preview: TextView
    private lateinit var title: TextView
    private lateinit var rows: LinearLayout

    var initialText: String = ""
    var titleText: String = ""
    var onCommit: ((String) -> Unit)? = null

    private var buffer = StringBuilder()
    private var mode = KeyboardMode.ALPHA
    private var isUpper = false
    private var pendingFocusTag: String? = null

    enum class KeyboardMode { ALPHA, NUM, SYMBOL }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.dialog_dpad_keyboard, container, false)
        preview = v.findViewById(R.id.preview)
        title = v.findViewById(R.id.title)
        rows = v.findViewById(R.id.rowContainer)

        buffer.clear()
        buffer.append(initialText)
        title.text = titleText
        updatePreview()
        buildKeyboard()
        return v
    }

    private fun updatePreview() { preview.text = buffer.toString() }

    private fun buildKeyboard() {
        rows.removeAllViews()
        when (mode) {
            KeyboardMode.ALPHA -> buildAlphaRows()
            KeyboardMode.NUM -> buildNumericRows()
            KeyboardMode.SYMBOL -> buildSymbolRows()
        }
        buildBottomRow()
        // Keep focus on the requested control if provided; otherwise first character key
        val target = pendingFocusTag?.let { rows.findViewWithTag<View>(it) }
            ?: rows.findViewWithTag("firstKey")
        target.requestFocus()
    }

    private fun buildAlphaRows() {
        val r1 = "qwertyuiop"
        val r2 = "asdfghjkl"
        val r3 = "zxcvbnm"
        addRow(r1)
        addRow(r2)
        addRow(r3)
    }

    private fun buildNumericRows() {
        addRow("1234567890")
        addRow("-/:;()$&@\"")
        addRow(".,?!'")
    }

    private fun buildSymbolRows() {
        addRow("[]{}#%^*+=")
        addRow("_\\|~<>€£¥•")
        addRow(".,?!'")
    }

    private fun addRow(chars: String) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_HORIZONTAL
            val side = resources.getDimensionPixelSize(R.dimen.row_side_padding)
            setPadding(side, 0, side, 0)
        }
        val keyH = resources.getDimensionPixelSize(R.dimen.key_height)
        val keyW = resources.getDimensionPixelSize(R.dimen.key_width)
        val gap = resources.getDimensionPixelSize(R.dimen.key_gap)

        chars.forEachIndexed { idx, ch ->
            val b = Button(ctx).apply {
                text = if (mode == KeyboardMode.ALPHA) {
                    if (isUpper) ch.uppercase() else ch.lowercase()
                } else ch.toString()
                isFocusable = true
                isFocusableInTouchMode = true
                isAllCaps = false
                if (rows.isEmpty() && idx == 0) tag = "firstKey"
                setOnClickListener {
                    val actual = if (mode == KeyboardMode.ALPHA) {
                        if (isUpper) ch.uppercaseChar() else ch
                    } else ch
                    appendChar(actual)
                }
            }
            val lp = LinearLayout.LayoutParams(keyW, keyH).apply { setMargins(gap, gap, gap, gap) }
            row.addView(b, lp)
        }
        rows.addView(row)
    }

    private fun buildBottomRow() {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_HORIZONTAL
            val side = resources.getDimensionPixelSize(R.dimen.row_side_padding)
            setPadding(side, 0, side, 0)
        }
        val keyH = resources.getDimensionPixelSize(R.dimen.key_height)
        val gap = resources.getDimensionPixelSize(R.dimen.key_gap)

        fun addSpecial(text: String, primary: Boolean, tag: String? = null, onClick: () -> Unit) {
            val bClass = if (primary) MaterialButton(ctx) else Button(ctx);
            val b = bClass.apply {
                this.text = text
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { onClick() }
                tag?.let { this.tag = it }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                keyH
            ).apply { setMargins(gap, gap, gap, gap) }
            row.addView(b, lp)
        }

        // Mode button cycles ALPHA -> NUM -> SYMBOL -> ALPHA with specified labels
        val modeLabel = when (mode) {
            KeyboardMode.ALPHA -> "123"
            KeyboardMode.NUM -> "#+="
            KeyboardMode.SYMBOL -> "ABC"
        }
        addSpecial(modeLabel, primary = false, tag = "btn_mode") {
            pendingFocusTag = "btn_mode"
            mode = when (mode) {
                KeyboardMode.ALPHA -> KeyboardMode.NUM
                KeyboardMode.NUM -> KeyboardMode.SYMBOL
                KeyboardMode.SYMBOL -> KeyboardMode.ALPHA
            }
            if (mode != KeyboardMode.ALPHA) isUpper = false // shift only for alpha
            buildKeyboard()
        }

        if (mode == KeyboardMode.ALPHA) {
            addSpecial("Shift", primary = isUpper, tag = "btn_shift") {
                pendingFocusTag = "btn_shift"
                isUpper = !isUpper
                buildKeyboard()
            }
        }

        // Space (content width, extra padding for comfort)
        val spaceBtn = Button(ctx).apply {
            text = context.getString(R.string.keySpace)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(paddingLeft + 32, paddingTop, paddingRight + 32, paddingBottom)
            setOnClickListener { appendChar(' ') }
        }
        row.addView(
            spaceBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                keyH
            ).apply { setMargins(gap, gap, gap, gap) }
        )

        addSpecial("Backspace", primary = false) {
            if (buffer.isNotEmpty()) {
                buffer.deleteCharAt(buffer.length - 1)
                updatePreview()
            }
        }

        // OK is always primary
        addSpecial("OK", primary = true) {
            onCommit?.invoke(buffer.toString())
            dismissAllowingStateLoss()
        }

        rows.addView(row)
    }

    private fun appendChar(ch: Char) {
        buffer.append(ch)
        updatePreview()
    }

    companion object {
        fun newInstance(title: String, initial: String, onCommit: (String) -> Unit): DpadKeyboardDialogFragment {
            return DpadKeyboardDialogFragment().apply {
                this.initialText = initial
                this.titleText = title
                this.onCommit = onCommit
            }
        }
    }
}
