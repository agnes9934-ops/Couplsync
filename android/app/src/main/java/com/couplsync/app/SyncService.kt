package com.couplsync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 연결 중에는 포그라운드 서비스로 계속 실행되면서
 *  1) 쿠팡플레이 앱의 재생 세션을 감시해 내 재생/정지/이동을 서버로 보내고
 *  2) 서버에서 받은 상대방 동작을 쿠팡플레이 앱에 적용하고
 *  3) 채팅을 주고받아 화면 위 버블에 띄웁니다.
 */
class SyncService : Service() {

    companion object {
        const val ACTION_CONNECT = "connect"
        const val ACTION_STOP = "stop"
        const val TARGET_KEYWORD = "coupang"   // 패키지 이름에 이 단어가 있는 앱만 제어
        private const val NOTI_ID = 1
        private const val CHANNEL = "sync"
        private const val IGNORE_MS = 2500L    // 원격 명령 적용 직후 내 이벤트 무시 시간
        private const val SEEK_LIMIT_MS = 1500L
        var instance: SyncService? = null; private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var wantConnected = false
    private var url = ""; private var room = ""; private var name = ""
    private var peers = 0

    private lateinit var msm: MediaSessionManager
    private lateinit var listenerComp: ComponentName
    private var controller: MediaController? = null
    private var overlay: ChatOverlay? = null

    // 내 재생 상태 추적 (변화 감지용)
    private var ignoreUntil = 0L
    private var lastPlaying: Boolean? = null
    private var lastPos = 0L
    private var lastAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        listenerComp = ComponentName(this, MediaListener::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CONNECT) {
            startForegroundCompat()
            url = intent.getStringExtra("url").orEmpty()
            room = intent.getStringExtra("room").orEmpty()
            name = intent.getStringExtra("name").orEmpty().ifBlank { "익명" }
            wantConnected = true
            connect()
            watchSessions()
            if (overlay == null && Settings.canDrawOverlays(this)) {
                overlay = ChatOverlay(this) { sendChat(it) }.also { it.show() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        instance = null
        super.onDestroy()
    }

    private fun shutdown() {
        wantConnected = false
        ws?.close(1000, "bye"); ws = null
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(mediaCallback); controller = null
        overlay?.hide(); overlay = null
        Bus.setStatus("연결 안 됨")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------- 알림 (포그라운드 서비스 유지용) ----------------
    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "같이 보기", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("쿠플 싱크 연결 중")
            .setContentText("방: $room")
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "연결 해제", stop
                ).build()
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    // ---------------- 서버 연결 ----------------
    private fun connect() {
        ws?.cancel()
        Bus.setStatus("연결 중…")
        val req = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            Bus.setStatus("서버 주소 형식 오류 (ws:// 또는 wss://)")
            return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("t", "join").put("room", room).put("name", name).toString())
                Bus.setStatus("연결됨")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { handleServer(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != ws) return
                Bus.setStatus("연결 실패, 재시도 중… (${t.message ?: "오류"})")
                retry()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != ws) return
                Bus.setStatus("연결 끊김, 재시도 중…")
                retry()
            }
        })
    }

    private fun retry() {
        main.postDelayed({ if (wantConnected) connect() }, 3000)
    }

    private fun send(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    fun sendChat(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        send(JSONObject().put("t", "chat").put("text", t))
        Bus.addChat("나: $t")
    }

    private fun handleServer(text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        when (m.optString("t")) {
            "state" -> applyRemote(m.optDouble("time", 0.0), m.optBoolean("playing"), m.optString("name"))
            "chat" -> {
                val line = "${m.optString("name")}: ${m.optString("text")}"
                Bus.addChat(line)
                overlay?.showMessage(line)
            }
            "peers" -> {
                peers = m.optInt("n")
                Bus.setStatus("연결됨 · ${peers}명 접속")
            }
        }
    }

    // ---------------- 쿠팡플레이 재생 세션 ----------------
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pickController(list ?: emptyList())
    }

    private fun watchSessions() {
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComp, main)
            pickController(msm.getActiveSessions(listenerComp))
        } catch (e: SecurityException) {
            Bus.setMedia("알림 접근 권한이 없습니다. 설정에서 허용하세요")
        }
    }

    private fun pickController(list: List<MediaController>) {
        val found = list.firstOrNull { it.packageName.contains(TARGET_KEYWORD, ignoreCase = true) }
        if (found != null && found.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(mediaCallback)
        controller = found
        lastPlaying = null
        if (found == null) {
            val others = list.joinToString { it.packageName }
            Bus.setMedia("쿠팡플레이 재생 세션 없음" + if (others.isNotEmpty()) " (감지된 앱: $others)" else "")
            return
        }
        found.registerCallback(mediaCallback, main)
        found.playbackState?.let { track(it) }
        Bus.setMedia("감지됨: ${found.packageName}\n지원 기능: ${describeActions(found.playbackState)}")
    }

    private fun describeActions(st: PlaybackState?): String {
        if (st == null) return "알 수 없음"
        val a = st.actions
        val parts = mutableListOf<String>()
        if (a and PlaybackState.ACTION_PLAY != 0L || a and PlaybackState.ACTION_PLAY_PAUSE != 0L) parts += "재생"
        if (a and PlaybackState.ACTION_PAUSE != 0L || a and PlaybackState.ACTION_PLAY_PAUSE != 0L) parts += "정지"
        if (a and PlaybackState.ACTION_SEEK_TO != 0L) parts += "이동"
        return if (parts.isEmpty()) "없음 (제어가 안 될 수 있음)" else parts.joinToString("/")
    }

    private fun posOf(st: PlaybackState): Long {
        if (st.state != PlaybackState.STATE_PLAYING) return st.position
        val elapsed = SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
        return st.position + (elapsed * st.playbackSpeed).toLong()
    }

    private fun track(st: PlaybackState) {
        lastPlaying = st.state == PlaybackState.STATE_PLAYING
        lastPos = posOf(st)
        lastAt = SystemClock.elapsedRealtime()
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(st: PlaybackState?) {
            if (st == null) return
            // 버퍼링/로딩 중 상태는 무시 (깜빡임 방지)
            if (st.state != PlaybackState.STATE_PLAYING && st.state != PlaybackState.STATE_PAUSED) return
            val playing = st.state == PlaybackState.STATE_PLAYING
            val pos = posOf(st)
            val now = SystemClock.elapsedRealtime()
            val prevPlaying = lastPlaying
            val expected = if (prevPlaying == true) lastPos + (now - lastAt) else lastPos
            track(st)
            if (now < ignoreUntil || prevPlaying == null) return
            val toggled = playing != prevPlaying
            val seeked = abs(pos - expected) > 2000
            if (toggled || seeked) {
                send(JSONObject().put("t", "state").put("time", pos / 1000.0).put("playing", playing))
            }
        }

        override fun onSessionDestroyed() {
            controller = null
            Bus.setMedia("쿠팡플레이 재생 세션 없음")
        }
    }

    private fun applyRemote(timeSec: Double, playing: Boolean, from: String) {
        val c = controller
        if (c == null) {
            overlay?.showMessage("쿠팡플레이에서 영상을 먼저 재생하세요")
            return
        }
        ignoreUntil = SystemClock.elapsedRealtime() + IGNORE_MS
        val target = (timeSec * 1000).toLong()
        val cur = c.playbackState?.let { posOf(it) } ?: 0L
        val tc = c.transportControls
        if (abs(cur - target) > SEEK_LIMIT_MS) tc.seekTo(target)
        if (playing) tc.play() else tc.pause()
        lastPlaying = playing; lastPos = target; lastAt = SystemClock.elapsedRealtime()
        if (from.isNotEmpty() && from != "(방 상태)") {
            overlay?.showMessage("$from: ${if (playing) "재생" else "일시정지"}")
        }
    }
}
