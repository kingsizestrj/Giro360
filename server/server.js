'use strict';

/**
 * Giro360 - Servidor de vídeos (Node puro, sem dependências).
 *
 * - POST /upload        -> recebe o vídeo (corpo bruto) do app, salva e devolve { url }
 * - GET  /v/:id         -> página amigável com preview + botão de download (alvo do QR Code)
 * - GET  /raw/:id       -> stream do vídeo (com suporte a Range, p/ tocar no navegador)
 * - GET  /dl/:id        -> download forçado do arquivo
 * - GET  /healthz       -> verificação de saúde
 *
 * Configuração por variáveis de ambiente:
 *   API_KEY          chave exigida no header X-Api-Key para enviar (obrigatório p/ upload)
 *   PUBLIC_BASE_URL  URL pública do servidor (ex.: https://videos.seudominio.com)
 *   PORT             porta (padrão 8080)
 *   DATA_DIR         pasta dos vídeos (padrão /data)
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '8080', 10);
const DATA_DIR = process.env.DATA_DIR || '/data';
const VIDEOS_DIR = path.join(DATA_DIR, 'videos');
const API_KEY = process.env.API_KEY || '';
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || '').replace(/\/+$/, '');

fs.mkdirSync(VIDEOS_DIR, { recursive: true });

function baseUrl(req) {
  if (PUBLIC_BASE_URL) return PUBLIC_BASE_URL;
  const proto = req.headers['x-forwarded-proto'] || 'http';
  const host = req.headers['x-forwarded-host'] || req.headers.host;
  return `${proto}://${host}`;
}

function isValidId(id) {
  return /^[a-f0-9-]{8,40}$/.test(id);
}

function send(res, status, body, headers) {
  res.writeHead(status, Object.assign({ 'Content-Type': 'text/plain; charset=utf-8' }, headers || {}));
  res.end(body);
}

function metaPath(id) { return path.join(VIDEOS_DIR, id + '.json'); }
function filePath(id) { return path.join(VIDEOS_DIR, id + '.mp4'); }

// ---------- Upload ----------
function handleUpload(req, res) {
  if (API_KEY && req.headers['x-api-key'] !== API_KEY) {
    return send(res, 401, 'Chave inválida');
  }
  const id = crypto.randomUUID();
  const url = new URL(req.url, 'http://x');
  const name = (url.searchParams.get('name') || 'Giro360.mp4').replace(/[^\w.\- ]/g, '_');
  const event = (url.searchParams.get('event') || '').replace(/[^\w.\- ]/g, '_');

  const out = fs.createWriteStream(filePath(id));
  let bytes = 0;
  req.on('data', (chunk) => { bytes += chunk.length; });
  req.pipe(out);

  out.on('finish', () => {
    fs.writeFileSync(metaPath(id), JSON.stringify({
      id, name, event, bytes, createdAt: Date.now()
    }));
    const link = `${baseUrl(req)}/v/${id}`;
    send(res, 200, JSON.stringify({ id, url: link }), {
      'Content-Type': 'application/json; charset=utf-8'
    });
  });
  out.on('error', () => send(res, 500, 'Falha ao salvar'));
  req.on('error', () => { out.destroy(); });
}

// ---------- Stream com Range ----------
function handleRaw(req, res, id) {
  const file = filePath(id);
  if (!isValidId(id) || !fs.existsSync(file)) return send(res, 404, 'Não encontrado');
  const stat = fs.statSync(file);
  const range = req.headers.range;
  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range);
    const start = m && m[1] ? parseInt(m[1], 10) : 0;
    const end = m && m[2] ? parseInt(m[2], 10) : stat.size - 1;
    if (start >= stat.size) return send(res, 416, 'Range inválido');
    res.writeHead(206, {
      'Content-Range': `bytes ${start}-${end}/${stat.size}`,
      'Accept-Ranges': 'bytes',
      'Content-Length': end - start + 1,
      'Content-Type': 'video/mp4'
    });
    fs.createReadStream(file, { start, end }).pipe(res);
  } else {
    res.writeHead(200, {
      'Content-Length': stat.size,
      'Content-Type': 'video/mp4',
      'Accept-Ranges': 'bytes'
    });
    fs.createReadStream(file).pipe(res);
  }
}

// ---------- Download forçado ----------
function handleDownload(req, res, id) {
  const file = filePath(id);
  if (!isValidId(id) || !fs.existsSync(file)) return send(res, 404, 'Não encontrado');
  let name = 'Giro360.mp4';
  try { name = JSON.parse(fs.readFileSync(metaPath(id))).name || name; } catch (e) {}
  const stat = fs.statSync(file);
  res.writeHead(200, {
    'Content-Type': 'video/mp4',
    'Content-Length': stat.size,
    'Content-Disposition': `attachment; filename="${name}"`
  });
  fs.createReadStream(file).pipe(res);
}

// ---------- Página de download (alvo do QR) ----------
function handlePage(req, res, id) {
  if (!isValidId(id) || !fs.existsSync(filePath(id))) return send(res, 404, 'Vídeo não encontrado');
  const html = `<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Seu vídeo 360</title>
<style>
  body { margin:0; background:#0b0b0b; color:#fff; font-family:-apple-system,Segoe UI,Roboto,sans-serif;
         display:flex; flex-direction:column; align-items:center; padding:24px; }
  h1 { font-size:20px; margin:12px 0 16px; }
  video { width:100%; max-width:420px; border-radius:14px; background:#000; }
  a.btn { margin-top:20px; background:#e50914; color:#fff; text-decoration:none; font-weight:bold;
          padding:16px 28px; border-radius:30px; font-size:18px; display:inline-block; }
  p.dica { color:#9a9a9a; font-size:13px; margin-top:14px; text-align:center; }
</style>
</head>
<body>
  <h1>🎬 Seu vídeo 360 está pronto!</h1>
  <video src="/raw/${id}" controls playsinline autoplay muted loop></video>
  <a class="btn" href="/dl/${id}">⬇️ Baixar vídeo</a>
  <p class="dica">Toque em "Baixar vídeo" para salvar no seu celular.</p>
</body>
</html>`;
  send(res, 200, html, { 'Content-Type': 'text/html; charset=utf-8' });
}

// ---------- Roteamento ----------
const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  const parts = u.pathname.split('/').filter(Boolean);

  if (req.method === 'POST' && parts[0] === 'upload') return handleUpload(req, res);
  if (req.method === 'GET' && parts[0] === 'healthz') return send(res, 200, 'ok');
  if (req.method === 'GET' && parts[0] === 'v' && parts[1]) return handlePage(req, res, parts[1]);
  if (req.method === 'GET' && parts[0] === 'raw' && parts[1]) return handleRaw(req, res, parts[1].replace(/\.mp4$/, ''));
  if (req.method === 'GET' && parts[0] === 'dl' && parts[1]) return handleDownload(req, res, parts[1].replace(/\.mp4$/, ''));
  if (req.method === 'GET' && parts.length === 0) return send(res, 200, 'Giro360 server ok');

  send(res, 404, 'Não encontrado');
});

server.listen(PORT, () => {
  console.log(`Giro360 server ouvindo na porta ${PORT} (dados em ${VIDEOS_DIR})`);
  if (!API_KEY) console.warn('AVISO: API_KEY vazia — qualquer um pode enviar vídeos!');
});
