package com.couplsync.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var urlEt: EditText
    private lateinit var roomEt: EditText
    private lateinit var nameEt: EditText
    private lateinit var permTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var mediaTv: TextView
    private lateinit var chatTv: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var msgEt: EditText

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(10), 0, dp(2)) }
            .also { col.addView(it) }
        fun input(hint: String, value: String) = EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine()
        }.also { col.addView(it) }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }.also { col.addView(it) }

        TextView(this).apply { text = "쿠플 싱크"; textSize = 24f }.also { col.addView(it) }

        label("① 권한 설정 (처음 한 번)")
        permTv = TextView(this).also { col.addView(it) }
        button("알림 접근 허용 (재생 제어에 필요)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button("다른 앱 위에 표시 허용 (채팅 버블)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        label("② 방 설정 (같이 볼 사람과 서버 주소·방 코드 동일하게)")
        urlEt = input("서버 주소 예: wss://xxxx.onrender.com", prefs.getString("url", "") ?: "")
        roomEt = input("방 코드", prefs.getString("room", "") ?: "")
        nameEt = input("닉네임", prefs.getString("name", "") ?: "")

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "연결"; setOnClickListener { connect() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "해제"; setOnClickListener { disconnect() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        statusTv = TextView(this).also { col.addView(it) }

        label("③ 쿠팡플레이에서 영상 재생")
        button("쿠팡플레이 열기") { openCoupangPlay() }
        mediaTv = TextView(this).also { col.addView(it) }

        label("채팅")
        chatTv = TextView(this).apply { setTextIsSelectable(true) }
        chatScroll = ScrollView(this).apply { addView(chatTv) }
        col.addView(chatScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)))
        msgEt = EditText(this).apply {
            hint = "댓글 입력 후 전송"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendChat(); true } else false
            }
        }
        col.addView(msgEt)

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Bus.onChange = { render() }
        render()
    }

    override fun onPause() {
        Bus.onChange = null
        super.onPause()
    }

    private fun hasListenerPerm(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun render() {
        val a = if (hasListenerPerm()) "✅ 알림 접근" else "❌ 알림 접근"
        val b = if (Settings.canDrawOverlays(this)) "✅ 화면 위 표시" else "❌ 화면 위 표시"
        permTv.text = "$a   $b"
        statusTv.text = "상태: ${Bus.status}"
        mediaTv.text = Bus.media
        chatTv.text = Bus.chat.joinToString("\n")
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun connect() {
        val url = urlEt.text.toString().trim()
        val room = roomEt.text.toString().trim()
        val name = nameEt.text.toString().trim()
        if (url.isEmpty() || room.isEmpty()) {
            toast("서버 주소와 방 코드를 입력하세요"); return
        }
        if (!hasListenerPerm()) {
            toast("먼저 '알림 접근 허용'을 켜 주세요"); return
        }
        prefs.edit().putString("url", url).putString("room", room).putString("name", name).apply()
        val i = Intent(this, SyncService::class.java).setAction(SyncService.ACTION_CONNECT)
            .putExtra("url", url).putExtra("room", room).putExtra("name", name)
        startForegroundService(i)
        if (!Settings.canDrawOverlays(this)) toast("채팅 버블을 보려면 '다른 앱 위에 표시'를 허용하세요")
    }

    private fun disconnect() {
        if (SyncService.instance != null) {
            startService(Intent(this, SyncService::class.java).setAction(SyncService.ACTION_STOP))
        }
    }

    private fun sendChat() {
        val s = SyncService.instance
        if (s == null) { toast("먼저 연결하세요"); return }
        s.sendChat(msgEt.text.toString())
        msgEt.setText("")
    }

    private fun openCoupangPlay() {
        val i = packageManager.getLaunchIntentForPackage("com.coupang.mobile.play")
        if (i != null) startActivity(i) else toast("쿠팡플레이 앱을 직접 열어 주세요")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
