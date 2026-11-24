package com.kenshix.deck

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

internal object DeckUi {
    const val BLACK = Color.BLACK
    val panel = Color.rgb(12, 12, 15)
    val raised = Color.rgb(19, 18, 23)
    val border = Color.rgb(43, 40, 52)
    val text = Color.rgb(247, 245, 252)
    val muted = Color.rgb(166, 160, 180)
    val accent = Color.rgb(188, 173, 255)
    val accentPanel = Color.rgb(23, 18, 37)
    val success = Color.rgb(147, 231, 190)
    val danger = Color.rgb(255, 171, 181)
    private val ease = DecelerateInterpolator(1.65f)

    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value + .5f).toInt()

    fun shape(context: Context, fill: Int = panel, radius: Int = 20, outline: Int = border) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
            if (outline != Color.TRANSPARENT) setStroke(dp(context, 1), outline)
        }

    fun touch(view: View, fill: Int = raised, radius: Int = 14, outline: Int = border) {
        view.background = RippleDrawable(ColorStateList.valueOf(0x33BCADFF), shape(view.context, fill, radius, outline), null)
        if (!ValueAnimator.areAnimatorsEnabled()) return
        fun scale(value: Float, durationMs: Long) = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, value),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, value),
            )
            duration = durationMs
            interpolator = ease
        }
        view.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), scale(.965f, 95))
            addState(intArrayOf(), scale(1f, 165))
        }
    }

    fun label(context: Context, caption: CharSequence, size: Int, color: Int = text, weight: Int = 400) =
        TextView(context).apply {
            text = caption
            textSize = size.toFloat()
            setTextColor(color)
            typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
            includeFontPadding = false
        }

    fun action(context: Context, caption: String, primary: Boolean = false, run: (View) -> Unit = {}) =
        Button(context).apply {
            text = caption
            textSize = 14f
            isAllCaps = false
            setTextColor(if (primary) accent else DeckUi.text)
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            minHeight = dp(context, 46)
            minWidth = dp(context, 46)
            touch(this, if (primary) accentPanel else raised)
            setOnClickListener(run)
        }

    fun screenHeading(context: Context, title: String, back: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 4), 0, dp(context, 16))
        addView(action(context, "‹") { back() }.apply {
            contentDescription = "Geri"
            textSize = 26f
        }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
        addView(label(context, title, 26, weight = 650).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(context, 14) })
    }

    fun appear(view: View, offset: Int = 10) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel()
        view.alpha = .45f
        view.translationY = dp(view.context, offset).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(ease).start()
    }

    fun editor(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 6))
    }

    fun scrollForm(content: View) = android.widget.ScrollView(content.context).apply {
        isFillViewport = true
        addView(content)
    }

    fun field(edit: EditText) {
        val ctx = edit.context
        edit.background = shape(ctx, raised, 13)
        edit.setTextColor(text)
        edit.setHintTextColor(muted)
        edit.setPadding(dp(ctx, 14), dp(ctx, 13), dp(ctx, 14), dp(ctx, 13))
        edit.minHeight = dp(ctx, 48)
    }

    fun choose(context: Context, title: String, values: List<String>, selected: Int): DeckChoice =
        DeckChoice(context, title, values, selected)

    fun choices(builder: AlertDialog.Builder, values: List<String>, select: (Int) -> Unit): AlertDialog.Builder {
        val context = builder.context
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, values) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? TextView ?: label(context, "", 15, weight = 550).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(context, 52)
                    setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8))
                }
                row.text = getItem(position)
                return row
            }
        }
        return builder.setAdapter(adapter) { _, index -> select(index) }
    }

    fun dialog(context: Context) = AlertDialog.Builder(context, R.style.DeckDialogTheme)
}

internal class DeckChoice(
    context: Context, private val title: String, private val values: List<String>, initial: Int
) : LinearLayout(context) {
    var onSelection: ((Int) -> Unit)? = null
    var selectedItemPosition = initial
        private set

    private val selection = DeckUi.action(context, values[initial], primary = true) {
        DeckUi.choices(DeckUi.dialog(context).setTitle(title), values) { index ->
            selectedItemPosition = index
            selectionText(values[index])
            onSelection?.invoke(index)
        }.setNegativeButton("Kapat", null).showDeck()
    }.apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        maxLines = 2
    }

    init {
        orientation = VERTICAL
        addView(DeckUi.label(context, title, 12, DeckUi.muted, 550).apply {
            setPadding(0, DeckUi.dp(context, 9), 0, DeckUi.dp(context, 7))
        })
        addView(selection, LayoutParams(-1, -2))
        selectionText(values[initial])
    }

    private fun selectionText(value: String) {
        selection.text = value
        selection.contentDescription = "$title: $value"
    }
}

internal fun AlertDialog.Builder.showDeck(): AlertDialog {
    val dialog = create()
    dialog.window?.setWindowAnimations(R.style.DeckDialogMotion)
    dialog.show()
    val context = dialog.context
    dialog.window?.apply {
        setBackgroundDrawable(DeckUi.shape(context, DeckUi.panel, 26))
        setDimAmount(.72f)
        val available = (context.resources.configuration.screenWidthDp - 30).coerceIn(230, 500)
        setLayout(DeckUi.dp(context, available), WindowManager.LayoutParams.WRAP_CONTENT)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    dialog.listView?.apply {
        divider = android.graphics.drawable.ColorDrawable(DeckUi.border)
        dividerHeight = DeckUi.dp(context, 1)
    }
    listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE).forEach { id ->
        dialog.getButton(id)?.apply {
            isAllCaps = false
            setTextColor(if (id == AlertDialog.BUTTON_POSITIVE) DeckUi.accent else DeckUi.muted)
            DeckUi.touch(this, if (id == AlertDialog.BUTTON_POSITIVE) DeckUi.accentPanel else DeckUi.panel)
        }
    }
    return dialog
}
