package com.arfan.aihub

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Full-screen review settings overlay.
 *
 * Design points that matter for the known bugs:
 *  - Rows are rebuilt from [ReviewSettings] every time the overlay opens (and
 *    on [refresh]), so the ON/OFF state can never go stale.
 *  - Non-reviewer services (Qwen, Coder) render as a locked OFF row and their
 *    controls are disabled, not merely hidden.
 *  - The Close button removes this view from its parent *and* notifies the
 *    Activity, so the overlay is genuinely dismissable rather than just hidden.
 */
class ReviewSettingsView(
    context: Context,
    private val settings: ReviewSettings
) : FrameLayout(context) {

    /** Invoked after the overlay removed itself from the view hierarchy. */
    var onDismiss: (() -> Unit)? = null

    /** Invoked by the Resume button so the Activity can restore a session. */
    var onResume: (() -> Unit)? = null

    /** Tells the overlay whether a Resume button is worth showing. */
    var hasSavedSession: () -> Boolean = { false }

    private val rows = LinearLayout(context)
    private val summary = TextView(context)

    /** Role edits are committed on focus loss; this collects them for Close. */
    private val pendingRoles = mutableListOf<Pair<String, EditText>>()

    init {
        setBackgroundColor(context.getColor(R.color.background))
        isClickable = true // swallow taps so the page underneath is untouched

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(12))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        column.addView(TextView(context).apply {
            text = "Review settings"
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(Typeface.DEFAULT_BOLD)
        })

        column.addView(summary.apply {
            setTextColor(context.getColor(R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
        })

        column.addView(buildLoopControls(), LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        rows.orientation = LinearLayout.VERTICAL
        val scroller = ScrollView(context).apply {
            addView(rows, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        column.addView(scroller, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, 0, 1f
        ))

        column.addView(buildFooter(), LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        refresh()
    }

    // ------------------------------------------------------------------
    // Rebuild
    // ------------------------------------------------------------------

    /** Rebuilds every row from the persisted settings. */
    fun refresh() {
        pendingRoles.clear()
        rows.removeAllViews()
        settings.resetDailyIfNeeded()
        settings.specs().forEach { rows.addView(buildRow(it)) }
        val enabled = settings.specs().count { it.isReviewer }
        summary.text = buildString {
            append("$enabled reviewer(s) enabled · max ${settings.maxRounds} rounds")
            append(" · escalate after ${settings.escalationAfter} failed round(s)")
            append(" · Z.ai left today ${settings.zaiEscalationRemaining()}/${ReviewSettings.MAX_ZAI_PER_DAY}")
        }
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    private fun buildRow(spec: ReviewSpec): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(context.getColor(R.color.card))
                setStroke(dp(1), context.getColor(R.color.card_stroke))
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(TextView(context).apply {
            text = spec.label
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val remaining = TextView(context).apply {
            text = if (spec.eligible) "left ${spec.remaining}/${spec.dailyCap}" else "locked"
            setTextColor(context.getColor(R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        header.addView(remaining, LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })

        val toggle = Button(context).apply {
            text = if (spec.eligible) (if (spec.enabled) "ON" else "OFF") else "LOCKED"
            isEnabled = spec.eligible
            setTextColor(
                if (!spec.eligible) context.getColor(R.color.text_muted)
                else if (spec.enabled) context.getColor(R.color.text_primary)
                else context.getColor(R.color.text_muted)
            )
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                settings.setEnabled(spec.serviceId, !settings.enabled(spec.serviceId))
                refresh()
            }
        }
        header.addView(toggle, LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, dp(36)
        ))
        card.addView(header)

        val roleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }.apply { setPadding(0, dp(6), 0, 0) }

        val roleEdit = EditText(context).apply {
            setText(spec.role)
            hint = "role"
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            inputType = InputType.TYPE_CLASS_TEXT
            isEnabled = spec.eligible
            setSingleLine(true)
            background = null
        }
        roleEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && spec.eligible) settings.setRole(spec.serviceId, roleEdit.text.toString())
        }
        pendingRoles.add(spec.serviceId to roleEdit)
        roleRow.addView(roleEdit, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // Daily cap stepper: - cap + , disabled for locked rows.
        roleRow.addView(capButton("−", spec, -1))
        roleRow.addView(TextView(context).apply {
            text = "cap ${spec.dailyCap}"
            setTextColor(context.getColor(R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(58), LayoutParams.WRAP_CONTENT))
        roleRow.addView(capButton("+", spec, +1))

        card.addView(roleRow)
        return card
    }

    private fun capButton(label: String, spec: ReviewSpec, delta: Int): Button =
        Button(context).apply {
            text = label
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            isEnabled = spec.eligible
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                settings.setDailyCap(spec.serviceId, settings.dailyCap(spec.serviceId) + delta)
                refresh()
            }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(40), dp(32))
        }

    // ------------------------------------------------------------------
    // Loop-wide controls
    // ------------------------------------------------------------------

    private fun buildLoopControls(): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        box.addView(stepper("rounds ${settings.maxRounds}") {
            settings.maxRounds = settings.maxRounds + it
            refresh()
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        box.addView(stepper("escalate ${settings.escalationAfter}") {
            settings.escalationAfter = settings.escalationAfter + it
            refresh()
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        return box
    }

    /** A "- label +" stepper; [onChange] receives -1 or +1. */
    private fun stepper(label: String, onChange: (Int) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun button(text: String, delta: Int) = Button(context).apply {
            this.text = text
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onChange(delta) }
        }
        row.addView(button("−", -1), LinearLayout.LayoutParams(dp(36), dp(32)))
        row.addView(TextView(context).apply {
            text = label
            setTextColor(context.getColor(R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("+", +1), LinearLayout.LayoutParams(dp(36), dp(32)))
        return row
    }

    // ------------------------------------------------------------------
    // Footer
    // ------------------------------------------------------------------

    private fun buildFooter(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(Button(context).apply {
            text = "Reset daily counts"
            textSize = 12f
            setOnClickListener {
                settings.resetDaily()
                refresh()
            }
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        if (hasSavedSession()) {
            row.addView(Button(context).apply {
                text = "Resume"
                textSize = 13f
                setOnClickListener {
                    onResume?.invoke()
                    dismiss()
                }
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        row.addView(Button(context).apply {
            text = "Close"
            textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return row
    }

    /** Commits in-progress role edits, detaches the overlay, notifies the host. */
    fun dismiss() {
        pendingRoles.forEach { (id, edit) ->
            val role = edit.text.toString().trim()
            if (role.isNotEmpty() && role != settings.role(id)) settings.setRole(id, role)
        }
        pendingRoles.clear()
        (parent as? ViewGroup)?.removeView(this)
        onDismiss?.invoke()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
