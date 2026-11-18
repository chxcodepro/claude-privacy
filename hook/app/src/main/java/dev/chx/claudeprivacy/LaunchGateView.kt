package dev.chx.claudeprivacy

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

internal class LaunchGateView(private val activity: Activity) : FrameLayout(activity) {
    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val foregroundColor = if (dark) 0xffeef1f6.toInt() else 0xff172034.toInt()
    private val detailColor = if (dark) 0xffaab5c8.toInt() else 0xff4b5976.toInt()
    private val checkColor = if (dark) 0xff7ce0aa.toInt() else 0xff187e52.toInt()
    private val decor = activity.window.decorView as ViewGroup
    private val content = decor.findViewById<View>(android.R.id.content)
    private val previousAccessibility = content?.importantForAccessibility
    private val stack = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val mark = FrameLayout(activity)
    private val title = TextView(activity).apply {
        textSize = 25f
        setTextColor(foregroundColor)
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val detail = TextView(activity).apply {
        textSize = 15f
        setTextColor(detailColor)
        gravity = Gravity.CENTER
    }
    private val exit = Button(activity).apply {
        text = "退出"
        setOnClickListener { activity.finishAndRemoveTask() }
    }
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusableInTouchMode = true
        elevation = dp(24).toFloat()
        setBackgroundColor(if (dark) 0xff10141c.toInt() else 0xfff5f7fb.toInt())
        content?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val padding = dp(28)
        stack.setPadding(padding, padding, padding, padding)
        addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        stack.addView(mark, LinearLayout.LayoutParams(dp(56), dp(56)))
        stack.addView(title, row(20))
        stack.addView(detail, row(8))
        stack.addView(exit, row(24))
        decor.addView(this, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        requestFocus()
        checking()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun row(margin: Int) = LinearLayout.LayoutParams(
        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(margin) }

    fun checking() {
        animator?.cancel()
        title.text = "正在审核公网 IPv4"
        detail.visibility = View.GONE
        exit.visibility = View.GONE
        mark.removeAllViews()
        mark.addView(ProgressBar(activity), LayoutParams(dp(36), dp(36), Gravity.CENTER))
    }

    fun denied(message: String) {
        animator?.cancel()
        title.text = "已阻止进入 Claude"
        detail.text = message
        detail.visibility = View.VISIBLE
        exit.visibility = View.VISIBLE
        mark.removeAllViews()
    }

    fun passed(onContinue: () -> Unit) {
        title.text = "IP 审核通过"
        detail.visibility = View.GONE
        exit.visibility = View.GONE
        mark.removeAllViews()
        val check = Checkmark()
        mark.addView(check, LayoutParams(dp(56), dp(56), Gravity.CENTER))
        if (ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320
                addUpdateListener { check.progress = it.animatedFraction; check.invalidate() }
                start()
            }
            postDelayed(onContinue, 430)
        } else {
            check.progress = 1f
            postDelayed(onContinue, 170)
        }
    }

    fun dismiss() {
        animator?.cancel()
        previousAccessibility?.let { content?.importantForAccessibility = it }
        decor.removeView(this)
    }

    private inner class Checkmark : View(activity) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = checkColor
            style = Paint.Style.STROKE
            strokeWidth = dp(4).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        private val segment = Path()
        private val measure = PathMeasure()
        var progress = 0f

        override fun onDraw(canvas: Canvas) {
            val size = width.toFloat()
            path.reset()
            path.moveTo(size * 0.2f, size * 0.5f)
            path.lineTo(size * 0.42f, size * 0.7f)
            path.lineTo(size * 0.8f, size * 0.31f)
            segment.reset()
            measure.setPath(path, false)
            measure.getSegment(0f, measure.length * progress, segment, true)
            canvas.drawPath(segment, stroke)
        }
    }
}
