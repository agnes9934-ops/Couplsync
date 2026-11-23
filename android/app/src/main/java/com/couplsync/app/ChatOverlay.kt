package com.couplsync.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 쿠팡플레이 화면 위에 떠 있는 채팅 버블.
 * - 받은 댓글은 버블 위에 6초간 표시
 * - 버블을 누르면 입력창이 열림, 드래그로 위치 이동
 */
class ChatOverlay(private val ctx: Context, private val onSend: (String) -> Unit) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private val closedFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    private val openFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        closedFlags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        x = dp(12)
        y = dp(90)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    }

    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val messages = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val edit = EditText(ctx).apply {
        hint = "댓글 입력"
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_SEND
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
        minWidth = dp(200)
    }
    private val inputRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = round(Color.argb(220, 20, 20, 20), dp(12).toFloat())
    }
    private val bubble = TextView(ctx).apply {
        text = "💬"
        textSize = 22f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(210, 229, 57, 53))
        }
    }
    private var shown = false
    private var open = false

    init {
        val sendBtn = Button(ctx).apply {
            text = "보내기"
            setOnClickListener { submit() }
        }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
        inputRow.addView(edit)
        inputRow.addView(sendBtn)

        root.addView(messages)
        root.addView(inputRow)
        root.addView(bubble, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(4) })
        setupDrag()
    }

    private fun round(color: Int, r: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = r
    }

    private fun submit() {
        val t = edit.text.toString().trim()
        if (t.isEmpty()) return
        onSend(t)
        showMessage("나: $t")
        edit.setText("")
    }

    private fun setupDrag() {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY - dy.toInt() // BOTTOM 기준이라 위로 올리면 y 증가
                        update()
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggle()
            }
            true
        }
    }

    private fun toggle() {
        open = !open
        inputRow.visibility = if (open) View.VISIBLE else View.GONE
        params.flags = if (open) openFlags else closedFlags
        update()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (open) {
            edit.requestFocus()
            edit.post { imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            imm.hideSoftInputFromWindow(edit.windowToken, 0)
        }
    }

    private fun update() {
        if (shown) wm.updateViewLayout(root, params)
    }

    fun show() {
        if (shown) return
        wm.addView(root, params)
        shown = true
    }

    fun hide() {
        if (!shown) return
        wm.removeView(root)
        shown = false
    }

    fun showMessage(text: String) {
        if (!shown) return
        val tv = TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = dp(260)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = round(Color.argb(170, 0, 0, 0), dp(12).toFloat())
        }
        messages.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        while (messages.childCount > 5) messages.removeViewAt(0)
        tv.postDelayed({ messages.removeView(tv) }, 6000)
    }
}
