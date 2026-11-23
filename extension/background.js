// WebSocket은 페이지의 CSP/혼합콘텐츠 제약을 피하려고 서비스 워커에서 연결합니다.
const conns = new Map(); // tabId -> { ws, ping }

function safePost(port, obj) {
  try { port.postMessage(obj); } catch (e) { /* 포트 닫힘 */ }
}

function closeConn(tabId) {
  const c = conns.get(tabId);
  if (!c) return;
  clearInterval(c.ping);
  c.ws.onclose = null;
  try { c.ws.close(); } catch (e) {}
  conns.delete(tabId);
}

function openConn(tabId, port, { url, room, name }) {
  closeConn(tabId);
  let ws;
  try {
    ws = new WebSocket(url);
  } catch (e) {
    safePost(port, { type: 'status', status: 'error', detail: String(e) });
    return;
  }
  const c = { ws, ping: null };
  conns.set(tabId, c);
  ws.onopen = () => {
    ws.send(JSON.stringify({ t: 'join', room, name }));
    // 주기적 트래픽으로 서비스 워커 종료 방지
    c.ping = setInterval(() => {
      if (ws.readyState === 1) ws.send('{"t":"ping"}');
    }, 20000);
    safePost(port, { type: 'status', status: 'open' });
  };
  ws.onmessage = (e) => {
    try { safePost(port, { type: 'msg', data: JSON.parse(e.data) }); } catch (err) {}
  };
  ws.onclose = () => {
    clearInterval(c.ping);
    conns.delete(tabId);
    safePost(port, { type: 'status', status: 'closed' });
  };
  ws.onerror = () => safePost(port, { type: 'status', status: 'error' });
}

chrome.runtime.onConnect.addListener((port) => {
  const tabId = port.sender && port.sender.tab && port.sender.tab.id;
  if (tabId == null) return;
  port.onMessage.addListener((msg) => {
    if (msg.type === 'connect') openConn(tabId, port, msg);
    else if (msg.type === 'send') {
      const c = conns.get(tabId);
      if (c && c.ws.readyState === 1) c.ws.send(JSON.stringify(msg.data));
    } else if (msg.type === 'disconnect') closeConn(tabId);
  });
  port.onDisconnect.addListener(() => closeConn(tabId));
});
