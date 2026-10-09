// EarBridge 중계 서버: 폰(send)이 보낸 소리를 같은 코드로 붙은 맥(listen)들에게 그대로 넘긴다.
// 의존성 없이 WebSocket을 직접 처리한다 (파이에 npm 없음).
const http = require('http');
const crypto = require('crypto');

const PORT = +(process.env.PORT || 8170);
const rooms = new Map(); // code -> { sender, header, listeners:Set }

function room(code) {
  let r = rooms.get(code);
  if (!r) { r = { sender: null, header: null, listeners: new Set() }; rooms.set(code, r); }
  return r;
}
function cleanup(code) {
  const r = rooms.get(code);
  if (r && !r.sender && r.listeners.size === 0) rooms.delete(code);
}

function frame(op, payload) {
  const len = payload.length;
  let head;
  if (len < 126) head = Buffer.from([0x80 | op, len]);
  else if (len < 65536) { head = Buffer.alloc(4); head[0] = 0x80 | op; head[1] = 126; head.writeUInt16BE(len, 2); }
  else { head = Buffer.alloc(10); head[0] = 0x80 | op; head[1] = 127; head.writeBigUInt64BE(BigInt(len), 2); }
  return Buffer.concat([head, payload]);
}

class Peer {
  constructor(sock) {
    this.sock = sock;
    this.buf = Buffer.alloc(0);
    this.onMessage = () => {};
    this.onClose = () => {};
    this.closed = false;
    sock.setNoDelay(true);
    sock.on('data', d => { this.buf = Buffer.concat([this.buf, d]); this.parse(); });
    sock.on('close', () => this.close());
    sock.on('error', () => this.close());
  }
  parse() {
    for (;;) {
      const b = this.buf;
      if (b.length < 2) return;
      const op = b[0] & 0x0f, masked = b[1] & 0x80;
      let len = b[1] & 0x7f, off = 2;
      if (len === 126) { if (b.length < 4) return; len = b.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (b.length < 10) return; len = Number(b.readBigUInt64BE(2)); off = 10; }
      if (len > 1 << 20) return this.close();
      const need = off + (masked ? 4 : 0) + len;
      if (b.length < need) return;
      let data = b.subarray(off + (masked ? 4 : 0), need);
      if (masked) {
        const m = b.subarray(off, off + 4);
        data = Buffer.from(data);
        for (let i = 0; i < data.length; i++) data[i] ^= m[i & 3];
      }
      this.buf = b.subarray(need);
      if (op === 8) return this.close();
      if (op === 9) { this.raw(frame(10, data)); continue; }
      if (op === 1 || op === 2) this.onMessage(data, op === 2);
    }
  }
  raw(buf) { if (!this.closed) this.sock.write(buf); }
  send(buf, binary = true) {
    // 듣는 쪽이 느려서 0.5MB 이상 밀리면 이번 조각은 버린다 (지연 누적 방지)
    if (this.sock.writableLength > 512 * 1024) return;
    this.raw(frame(binary ? 2 : 1, buf));
  }
  close() {
    if (this.closed) return;
    this.closed = true;
    try { this.sock.end(frame(8, Buffer.alloc(0))); } catch {}
    this.sock.destroy();
    this.onClose();
  }
}

function tellSender(r) {
  if (r.sender) r.sender.send(Buffer.from('listeners:' + r.listeners.size), false);
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
  res.end(`earbridge relay, rooms ${rooms.size}\n`);
});

server.on('upgrade', (req, sock) => {
  const m = /\/(send|listen)\/([A-Za-z0-9]{6,32})\/?(?:\?.*)?$/.exec(req.url || '');
  const key = req.headers['sec-websocket-key'];
  if (!m || !key) { sock.end('HTTP/1.1 400 Bad Request\r\n\r\n'); return; }
  const accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  sock.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);

  const [, role, code0] = m;
  const code = code0.toUpperCase();
  const r = room(code);
  const p = new Peer(sock);

  if (role === 'send') {
    if (r.sender) r.sender.close();
    r.sender = p;
    r.header = null;
    // 첫 메시지는 9바이트 헤더(EBR1 + 샘플레이트 + 채널), 그 뒤는 PCM 조각
    p.onMessage = (data, bin) => {
      if (!bin) return;
      if (!r.header) {
        r.header = Buffer.from(data);
        // 맥에게 새 스트림이 시작됨을 먼저 알리고 헤더를 보낸다
        for (const l of r.listeners) { l.send(Buffer.from('start'), false); l.send(r.header); }
        return;
      }
      for (const l of r.listeners) l.send(data);
    };
    p.onClose = () => {
      if (r.sender === p) {
        r.sender = null; r.header = null;
        for (const l of r.listeners) l.send(Buffer.from('gone'), false);
      }
      cleanup(code);
    };
    tellSender(r);
    log(`send ${code}`);
  } else {
    r.listeners.add(p);
    if (r.header) { p.send(Buffer.from('start'), false); p.send(r.header); }
    p.onClose = () => { r.listeners.delete(p); tellSender(r); cleanup(code); };
    tellSender(r);
    log(`listen ${code} (${r.listeners.size})`);
  }
});

// 끊긴 걸 빨리 알아채도록 주기적으로 ping
setInterval(() => {
  for (const r of rooms.values()) {
    for (const p of [r.sender, ...r.listeners]) if (p) p.raw(frame(9, Buffer.alloc(0)));
  }
}, 15000);

function log(s) { console.log(new Date().toISOString(), s); }
server.listen(PORT, '0.0.0.0', () => log(`listening ${PORT}`));
