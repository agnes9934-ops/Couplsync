// 쿠플 싱크 릴레이 서버: 방 코드별로 재생 상태와 채팅을 중계합니다.
const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
// 일반 HTTP 요청에는 200을 돌려줘 호스팅 서비스의 상태 확인을 통과시킵니다.
const httpServer = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('쿠플 싱크 서버 정상 작동 중');
});
const wss = new WebSocketServer({ server: httpServer, maxPayload: 16 * 1024 });
httpServer.listen(PORT);
const rooms = new Map(); // code -> { clients:Set<ws>, state:{time,playing,at}|null }

function send(ws, obj) {
  if (ws.readyState === 1) ws.send(JSON.stringify(obj));
}
function broadcast(room, obj, except) {
  for (const c of room.clients) if (c !== except) send(c, obj);
}
function peers(room) {
  broadcast(room, { t: 'peers', n: room.clients.size });
}
function leave(ws) {
  const room = ws.room;
  if (!room) return;
  room.clients.delete(ws);
  ws.room = null;
  if (room.clients.size === 0) {
    // 마지막 사람이 나가면 10분 뒤 방 삭제
    room.timer = setTimeout(() => {
      if (room.clients.size === 0) rooms.delete(room.code);
    }, 10 * 60 * 1000);
  } else {
    peers(room);
  }
}

wss.on('connection', (ws) => {
  ws.room = null;
  ws.name = '익명';
  ws.alive = true;
  ws.on('pong', () => (ws.alive = true));

  ws.on('message', (raw) => {
    let m;
    try { m = JSON.parse(raw); } catch { return; }
    if (!m || typeof m.t !== 'string') return;

    if (m.t === 'ping') return;

    if (m.t === 'join') {
      const code = String(m.room || '').trim().slice(0, 40);
      if (!code) return;
      leave(ws);
      ws.name = String(m.name || '익명').slice(0, 20);
      let room = rooms.get(code);
      if (!room) {
        room = { code, clients: new Set(), state: null, timer: null };
        rooms.set(code, room);
      }
      if (room.timer) { clearTimeout(room.timer); room.timer = null; }
      room.clients.add(ws);
      ws.room = room;
      send(ws, { t: 'joined' });
      if (room.state) {
        const s = room.state;
        const time = s.playing ? s.time + (Date.now() - s.at) / 1000 : s.time;
        send(ws, { t: 'state', time, playing: s.playing, name: '(방 상태)' });
      }
      peers(room);
      return;
    }

    const room = ws.room;
    if (!room) return;

    if (m.t === 'state') {
      const time = Number(m.time);
      if (!Number.isFinite(time)) return;
      room.state = { time, playing: !!m.playing, at: Date.now() };
      broadcast(room, { t: 'state', time, playing: !!m.playing, name: ws.name }, ws);
    } else if (m.t === 'chat') {
      const text = String(m.text || '').slice(0, 500);
      if (text) broadcast(room, { t: 'chat', name: ws.name, text }, ws);
    }
  });

  ws.on('close', () => leave(ws));
  ws.on('error', () => leave(ws));
});

// 죽은 연결 정리 (호스팅 환경의 유휴 타임아웃 방지 겸용)
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.alive) { ws.terminate(); continue; }
    ws.alive = false;
    ws.ping();
  }
}, 30000);

console.log(`쿠플 싱크 서버 실행 중: ws://localhost:${PORT}`);
