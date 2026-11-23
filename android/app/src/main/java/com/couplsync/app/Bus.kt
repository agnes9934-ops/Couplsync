package com.couplsync.app

import android.os.Handler
import android.os.Looper

/** 서비스 → 화면으로 상태를 전달하는 간단한 저장소. 모든 변경은 메인 스레드에서 일어납니다. */
object Bus {
    private val main = Handler(Looper.getMainLooper())

    var status = "연결 안 됨"; private set
    var media = "쿠팡플레이 재생 세션 없음"; private set
    val chat = ArrayList<String>()
    var onChange: (() -> Unit)? = null

    fun setStatus(s: String) = main.post { status = s; onChange?.invoke() }
    fun setMedia(s: String) = main.post { media = s; onChange?.invoke() }
    fun addChat(s: String) = main.post {
        chat.add(s)
        if (chat.size > 200) chat.removeAt(0)
        onChange?.invoke()
    }
}
