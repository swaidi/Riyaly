package app.riyaly

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Small helpers for building screens in code. */
object Ui {
    val PALETTE = listOf(
        Color.rgb(31, 111, 80), Color.rgb(37, 99, 235), Color.rgb(217, 119, 6), Color.rgb(190, 24, 93),
        Color.rgb(124, 58, 237), Color.rgb(8, 145, 178), Color.rgb(101, 163, 13), Color.rgb(220, 38, 38),
    )
    val GREY = Color.rgb(120, 120, 120)
    val ALERT = Color.rgb(198, 40, 40)
}

fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.attrColor(attr: Int, fallback: Int = Color.BLACK) = MaterialColors.getColor(this, attr, fallback)

fun Context.vbox(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (pad > 0) setPadding(dp(pad))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.scroll(child: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    addView(child, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

fun Context.scrollH(child: View): android.widget.HorizontalScrollView = android.widget.HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false
    addView(child)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
}

fun Context.text(s: CharSequence, size: Float = 15f, bold: Boolean = false, color: Int? = null): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setTextColor(color ?: attrColor(com.google.android.material.R.attr.colorOnSurface))
}

fun Context.muted(s: CharSequence, size: Float = 13f): TextView =
    text(s, size, color = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))

fun Context.section(s: CharSequence): TextView = text(s, 17f, true, attrColor(androidx.appcompat.R.attr.colorPrimary)).apply {
    setPadding(0, dp(18), 0, dp(6))
}

fun Context.card(vararg children: View, onClick: (() -> Unit)? = null): MaterialCardView = MaterialCardView(this).apply {
    radius = dp(16).toFloat()
    cardElevation = 0f
    strokeWidth = dp(1)
    strokeColor = attrColor(com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
    useCompatPadding = false
    val inner = vbox(16)
    children.forEach { inner.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    addView(inner)
    if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(12)
    }
}

fun Context.button(label: CharSequence, outlined: Boolean = false, onClick: () -> Unit): MaterialButton =
    (if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) else MaterialButton(this)).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

fun Context.textButton(label: CharSequence, onClick: () -> Unit): MaterialButton =
    MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }

fun Context.chips(singleSelection: Boolean = true): ChipGroup = ChipGroup(this).apply {
    isSingleSelection = singleSelection
    isSelectionRequired = singleSelection
}

fun Context.chip(label: CharSequence, checked: Boolean, checkable: Boolean = true, onClick: () -> Unit): Chip = Chip(this).apply {
    text = label
    isCheckable = checkable
    isChecked = checked
    setOnClickListener { onClick() }
}

fun Context.switchRow(label: CharSequence, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch = MaterialSwitch(this).apply {
    text = label
    isChecked = checked
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    setPadding(0, dp(4), 0, dp(4))
    setOnCheckedChangeListener { _, c -> onChange(c) }
}

class Field(val layout: TextInputLayout, val edit: TextInputEditText) {
    val value: String get() = edit.text?.toString()?.trim() ?: ""
}

fun Context.field(hint: CharSequence, initial: String = "", type: Int = InputType.TYPE_CLASS_TEXT, helper: CharSequence? = null): Field {
    val l = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
    val e = TextInputEditText(l.context)
    e.setText(initial)
    e.inputType = type
    l.hint = hint
    if (helper != null) l.helperText = helper
    l.addView(e)
    l.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    return Field(l, e)
}

fun Context.dialog(title: CharSequence) = MaterialAlertDialogBuilder(this).setTitle(title)

fun Context.confirm(title: CharSequence, message: CharSequence, yes: CharSequence, onYes: () -> Unit) {
    dialog(title).setMessage(message).setPositiveButton(yes) { _, _ -> onYes() }.setNegativeButton(R.string.cancel, null).show()
}

fun Context.toast(s: CharSequence) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

fun LinearLayout.add(v: View, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, weight: Float = 0f): View {
    addView(v, LinearLayout.LayoutParams(if (weight > 0) 0 else width, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
    return v
}

fun Context.colorDot(color: Int, size: Int = 12): View = View(this).apply {
    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color) }
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(8) }
}

fun Context.badge(s: CharSequence, color: Int): TextView = text(s, 12f, true, color).apply {
    background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)))
    }
    setPadding(dp(8), dp(2), dp(8), dp(2))
}
