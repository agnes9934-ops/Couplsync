(() => {
  if (window.__cpSyncLoaded) return;
  window.__cpSyncLoaded = true;

  const DEFAULTS = { url: 'ws://localhost:8080', room: '', name: '' };
  const DRIFT_LIMIT = 0.8; // 초: 이보다 어긋나면 이동
  const IGNORE_MS = 1500;  // 원격 명령 적용 후 로컬 이벤트 무시 시간

  let port = null;
  let connected = false;
  let ignoreUntil = 0;
  let boundVideo = null;

  // ---------- 영상 찾기 ----------
  function getVideo() {
    const vids = Array.from(document.querySelectorAll('video'));
    if (!vids.length) return null;
    return vids
      .map((v) => ({ v, area: v.clientWidth * v.clientHeight }))
      .sort((a, b) => b.area - a.area)[0].v;
  }

  // ---------- 서버 통신 ----------
  function send(data) {
    if (port && connected) port.postMessage({ type: 'send', data });
  }

  function connect(cfg) {
    disconnect();
    port = chrome.runtime.connect({ name: 'cpsync' });
    port.onMessage.addListener(onBgMessage);
    port.onDisconnect.addListener(() => { port = null; setStatus('연결 끊김', false); });
    setStatus('연결 중…', false);
    port.postMessage({ type: 'connect', url: cfg.url, room: cfg.room, name: cfg.name || '익명' });
  }

  function disconnect() {
    if (port) { try { port.postMessage({ type: 'disconnect' }); port.disconnect(); } catch (e) {} }
    port = null;
    connected = false;
    setStatus('연결 안 됨', false);
  }

  function onBgMessage(m) {
    if (m.type === 'status') {
      if (m.status === 'open') { connected = true; setStatus('연결됨', true); }
      else if (m.status === 'closed') { connected = false; setStatus('연결 끊김', false); }
      else if (m.status === 'error') { connected = false; setStatus('서버 연결 실패 (주소 확인)', false); }
    } else if (m.type === 'msg') {
      const d = m.data;
      if (d.t === 'state') applyRemote(d);
      else if (d.t === 'chat') addChat(d.name, d.text, false);
      else if (d.t === 'peers') setPeers(d.n);
    }
  }

  // ---------- 재생 동기화 ----------
  function onLocal() {
    if (Date.now() < ignoreUntil) return;
    const v = boundVideo;
    if (!v) return;
    send({ t: 'state', time: v.currentTime, playing: !v.paused });
  }

  function applyRemote(s) {
    const v = getVideo();
    if (!v) { toast('영상 재생 화면에서 연결해 주세요'); return; }
    ignoreUntil = Date.now() + IGNORE_MS;
    if (Math.abs(v.currentTime - s.time) > DRIFT_LIMIT) v.currentTime = s.time;
    if (s.playing && v.paused) {
      v.play().catch(() => toast('재생이 브라우저에 막혔어요. 화면을 한 번 클릭하세요'));
    } else if (!s.playing && !v.paused) {
      v.pause();
    }
    if (s.name && s.name !== '(방 상태)') toast(`${s.name}: ${s.playing ? '재생' : '일시정지'}`);
  }

  // 영상 요소가 바뀌면(다음 화면, 회차 변경 등) 이벤트를 다시 연결
  function bindVideo() {
    const v = getVideo();
    if (v === boundVideo) return;
    if (boundVideo) {
      ['play', 'pause', 'seeked'].forEach((e) => boundVideo.removeEventListener(e, onLocal));
    }
    boundVideo = v;
    if (v) ['play', 'pause', 'seeked'].forEach((e) => v.addEventListener(e, onLocal));
  }
  setInterval(bindVideo, 1000);

  // ---------- UI (Shadow DOM) ----------
  const host = document.createElement('div');
  host.style.cssText = 'all:initial;position:fixed;z-index:2147483647;right:0;top:0;';
  const root = host.attachShadow({ mode: 'open' });
  root.innerHTML = `
    <style>
      :host{all:initial}
      *{box-sizing:border-box;font-family:-apple-system,"Malgun Gothic",sans-serif}
      .fab{position:fixed;right:16px;bottom:90px;width:44px;height:44px;border-radius:50%;
        background:#e53935;color:#fff;border:0;font-size:20px;cursor:pointer;opacity:.85}
      .fab:hover{opacity:1}
      .panel{position:fixed;right:16px;bottom:144px;width:300px;max-height:70vh;display:none;
        flex-direction:column;background:rgba(20,20,20,.95);color:#eee;border-radius:12px;
        padding:12px;font-size:13px;box-shadow:0 4px 20px rgba(0,0,0,.5)}
      .panel.open{display:flex}
      label{display:block;margin:6px 0 2px;color:#aaa;font-size:11px}
      input{width:100%;padding:6px 8px;border-radius:6px;border:1px solid #444;background:#111;color:#eee}
      .row{display:flex;gap:6px;margin-top:8px}
      button.b{flex:1;padding:7px;border-radius:6px;border:0;background:#e53935;color:#fff;cursor:pointer}
      button.b.gray{background:#444}
      .status{margin-top:8px;color:#9ccc65;font-size:12px}
      .status.off{color:#ef9a9a}
      .log{flex:1;min-height:80px;max-height:200px;overflow-y:auto;margin-top:8px;padding:6px;
        background:#111;border-radius:6px;font-size:12px;line-height:1.5}
      .log div{margin-bottom:3px;word-break:break-all}
      .log b{color:#ffab91}
      .toast{position:fixed;left:50%;top:24px;transform:translateX(-50%);max-width:70vw;
        background:rgba(0,0,0,.75);color:#fff;padding:8px 14px;border-radius:18px;font-size:14px;
        opacity:0;transition:opacity .25s;pointer-events:none;z-index:9}
      .toast.show{opacity:1}
      .live{position:fixed;left:16px;bottom:90px;max-width:40vw;pointer-events:none}
      .live div{background:rgba(0,0,0,.6);color:#fff;padding:5px 10px;border-radius:12px;
        margin-top:4px;font-size:14px;word-break:break-all}
    </style>
    <div class="toast"></div>
    <div class="live"></div>
    <button class="fab" title="쿠플 싱크">👥</button>
    <div class="panel">
      <label>서버 주소</label><input id="url" placeholder="wss://...">
      <label>방 코드 (같이 볼 사람과 동일하게)</label><input id="room" placeholder="예: our-room-8271">
      <label>닉네임</label><input id="name" placeholder="닉네임">
      <div class="row">
        <button class="b" id="join">연결</button>
        <button class="b gray" id="leave">해제</button>
      </div>
      <div class="status off" id="status">연결 안 됨</div>
      <div class="log" id="log"></div>
      <div class="row"><input id="msg" placeholder="댓글 입력 후 Enter"></div>
    </div>`;
  const $ = (id) => root.getElementById(id);
  const panel = root.querySelector('.panel');
  const toastEl = root.querySelector('.toast');
  const liveEl = root.querySelector('.live');
  let peerCount = 0;
  let statusText = '연결 안 됨';

  function setStatus(t, ok) {
    statusText = t;
    const el = $('status');
    el.textContent = t + (ok && peerCount ? ` · ${peerCount}명 접속` : '');
    el.className = 'status' + (ok ? '' : ' off');
  }
  function setPeers(n) { peerCount = n; setStatus(statusText, connected); }

  let toastTimer;
  function toast(t) {
    toastEl.textContent = t;
    toastEl.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove('show'), 2000);
  }

  function addChat(name, text, mine) {
    const log = $('log');
    const row = document.createElement('div');
    const b = document.createElement('b');
    b.textContent = mine ? '나' : name;
    row.append(b, ': ' + text);
    log.appendChild(row);
    log.scrollTop = log.scrollHeight;
    // 패널이 닫혀 있거나 전체화면이어도 보이도록 화면 왼쪽 아래에 잠깐 표시
    if (!mine) {
      const live = document.createElement('div');
      live.textContent = `${name}: ${text}`;
      liveEl.appendChild(live);
      while (liveEl.children.length > 5) liveEl.firstChild.remove();
      setTimeout(() => live.remove(), 6000);
    }
  }

  $('msg').addEventListener('keydown', (e) => {
    e.stopPropagation(); // 영상 단축키(스페이스 등)와 충돌 방지
    if (e.key !== 'Enter' || e.isComposing) return;
    const text = e.target.value.trim();
    if (!text) return;
    if (!connected) { toast('먼저 연결하세요'); return; }
    send({ t: 'chat', text });
    addChat('', text, true);
    e.target.value = '';
  });
  ['keydown', 'keyup', 'keypress'].forEach((ev) =>
    root.querySelectorAll('input').forEach((i) => i.addEventListener(ev, (e) => e.stopPropagation()))
  );

  root.querySelector('.fab').addEventListener('click', () => panel.classList.toggle('open'));

  $('join').addEventListener('click', () => {
    const cfg = { url: $('url').value.trim(), room: $('room').value.trim(), name: $('name').value.trim() };
    if (!cfg.url || !cfg.room) { toast('서버 주소와 방 코드를 입력하세요'); return; }
    chrome.storage.local.set(cfg);
    connect(cfg);
  });
  $('leave').addEventListener('click', disconnect);

  // 전체화면에서는 fullscreenElement 안에 UI가 있어야 보임
  function placeHost() {
    let target = document.fullscreenElement || document.documentElement;
    if (target.tagName === 'VIDEO') target = document.documentElement; // video 안에는 자식이 렌더되지 않음
    if (host.parentNode !== target) target.appendChild(host);
  }
  document.addEventListener('fullscreenchange', placeHost);
  placeHost();

  chrome.storage.local.get(DEFAULTS, (cfg) => {
    $('url').value = cfg.url;
    $('room').value = cfg.room;
    $('name').value = cfg.name;
  });
})();
