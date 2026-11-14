package com.arfan.aihub

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A single service card for the Home grid.
 *
 * Built in code rather than XML because the card is a small, self-contained
 * compound view with no state of its own — this keeps the layout file to the
 * grid itself and avoids eight near-identical includes.
 */
class ServiceCardView(
    context: Context,
    val service: AiService
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(12), dp(14), dp(12), dp(14))

        // Rounded card surface, colours taken from the theme.
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(context.getColor(R.color.card))
            setStroke(dp(1), context.getColor(R.color.card_stroke))
        }
        isFocusable = true
        isClickable = true

        val accentCircle = TextView(context).apply {
            text = service.initial
            setTextColor(context.getColor(R.color.on_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(service.accent)
            }
        }
        addView(
            accentCircle,
            LayoutParams(dp(44), dp(44))
        )

        val nameView = TextView(context).apply {
            text = service.label
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(
            nameView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        )

        val roleView = TextView(context).apply {
            text = service.role
            setTextColor(context.getColor(R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(
            roleView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        )

        contentDescription = "${service.label}, ${service.role}"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
