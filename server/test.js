const WebSocket = require('ws');
const assert = require('assert');
const URL = 'ws://localhost:' + (process.env.PORT || 8080);

function client(name) {
  const ws = new WebSocket(URL);
  ws.inbox = [];
  ws.on('message', (d) => ws.inbox.push(JSON.parse(d)));
  ws.ready = new Promise((r) => ws.on('open', r));
  ws.join = (room) => ws.send(JSON.stringify({ t: 'join', room, name }));
  return ws;
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const a = client('A'), b = client('B');
  await Promise.all([a.ready, b.ready]);
  a.join('r1'); b.join('r1');
  await wait(100);
  a.send(JSON.stringify({ t: 'state', time: 100, playing: true }));
  a.send(JSON.stringify({ t: 'chat', text: '안녕' }));
  await wait(100);
  assert(b.inbox.some((m) => m.t === 'state' && m.time === 100 && m.playing === true), 'B가 상태를 받아야 함');
  assert(b.inbox.some((m) => m.t === 'chat' && m.text === '안녕' && m.name === 'A'), 'B가 채팅을 받아야 함');
  assert(!a.inbox.some((m) => m.t === 'chat'), 'A는 자기 채팅을 되받지 않음');

  // 다른 방은 격리
  const c = client('C'); await c.ready; c.join('r2'); await wait(100);
  a.send(JSON.stringify({ t: 'state', time: 5, playing: false })); await wait(100);
  assert(!c.inbox.some((m) => m.t === 'state'), '다른 방은 받지 않아야 함');

  // 늦게 입장한 사람은 현재 상태를 받음 (재생 중이면 시간 보정)
  a.send(JSON.stringify({ t: 'state', time: 200, playing: true })); await wait(1100);
  const d = client('D'); await d.ready; d.join('r1'); await wait(150);
  const s = d.inbox.find((m) => m.t === 'state');
  assert(s && s.time >= 200.9 && s.time < 202.5 && s.playing, '늦은 입장자 상태 보정: ' + JSON.stringify(s));
  assert(d.inbox.some((m) => m.t === 'peers' && m.n === 3), '접속자 수 3명');

  console.log('모든 테스트 통과');
  process.exit(0);
})().catch((e) => { console.error('실패:', e.message); process.exit(1); });
