'use strict';

/**
 * Prime360 - Servidor de vídeos (Node puro + FFmpeg).
 *
 * O celular envia o vídeo BRUTO + o efeito desejado; o servidor responde o link
 * (QR) na HORA e processa o efeito em segundo plano (FFmpeg). Quando o cliente
 * escaneia o QR, vê "processando" e, ao terminar, o vídeo pronto para baixar.
 *
 * - POST /upload?effect=boomerang|slow|normal&fps=20&name=...  -> { id, url } (imediato)
 * - GET  /v/:id      -> página: "processando" (auto-refresh) ou vídeo + download
 * - GET  /status/:id -> { status: processing|done|error }
 * - GET  /raw/:id    -> stream do vídeo final (com Range)
 * - GET  /dl/:id     -> download forçado
 * - GET  /healthz    -> ok
 *
 * Env: API_KEY, PUBLIC_BASE_URL, PORT (8080), DATA_DIR (/data)
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');

const PORT = parseInt(process.env.PORT || '8080', 10);
const DATA_DIR = process.env.DATA_DIR || '/data';
const VIDEOS_DIR = path.join(DATA_DIR, 'videos');
const API_KEY = process.env.API_KEY || '';
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || '').replace(/\/+$/, '');

// Codec de vídeo (velocidade). Padrão: libx264 + preset rápido.
// Para acelerar por GPU, troque VIDEO_ENCODER (ex.: h264_nvenc, h264_qsv,
// h264_videotoolbox) — exige FFmpeg/driver compatíveis no servidor.
const ENCODER = process.env.VIDEO_ENCODER || 'libx264';
const PRESET = process.env.VIDEO_PRESET || 'veryfast';
const CRF = process.env.VIDEO_CRF || '23';
const MAX_HEIGHT = parseInt(process.env.MAX_HEIGHT || '0', 10); // 0 = sem limite
const VAAPI_DEVICE = process.env.VAAPI_DEVICE || '/dev/dri/renderD128';
const isVaapi = ENCODER.includes('vaapi');

function vencArgs() {
  const a = ['-c:v', ENCODER];
  if (ENCODER === 'libx264' || ENCODER === 'libx265') {
    a.push('-preset', PRESET, '-crf', CRF);
  } else if (ENCODER.includes('nvenc')) {
    a.push('-preset', 'p4', '-cq', CRF);
  } else if (isVaapi) {
    a.push('-qp', CRF);
  }
  return a;
}

fs.mkdirSync(VIDEOS_DIR, { recursive: true });

const rawPath = (id) => path.join(VIDEOS_DIR, id + '_raw.mp4');
const finalPath = (id) => path.join(VIDEOS_DIR, id + '.mp4');
const metaPath = (id) => path.join(VIDEOS_DIR, id + '.json');
const framePath = (id) => path.join(VIDEOS_DIR, id + '_frame.png');
const musicPath = (id) => path.join(VIDEOS_DIR, id + '_music.bin');

function readMeta(id) {
  try { return JSON.parse(fs.readFileSync(metaPath(id))); } catch (e) { return null; }
}
function writeMeta(id, meta) { fs.writeFileSync(metaPath(id), JSON.stringify(meta)); }

function baseUrl(req) {
  if (PUBLIC_BASE_URL) return PUBLIC_BASE_URL;
  const proto = req.headers['x-forwarded-proto'] || 'http';
  const host = req.headers['x-forwarded-host'] || req.headers.host;
  return `${proto}://${host}`;
}
const isValidId = (id) => /^[a-f0-9-]{8,40}$/.test(id);
function send(res, status, body, headers) {
  res.writeHead(status, Object.assign({ 'Content-Type': 'text/plain; charset=utf-8' }, headers || {}));
  res.end(body);
}

// ---------- Processamento (FFmpeg) ----------
function ffmpegArgs(id, effect, fps) {
  const f = Math.max(8, Math.min(60, parseInt(fps, 10) || 20));
  const input = rawPath(id);
  const output = finalPath(id);
  const hasFrame = fs.existsSync(framePath(id));
  const hasMusic = fs.existsSync(musicPath(id));

  const args = [];
  if (isVaapi) args.push('-vaapi_device', VAAPI_DEVICE); // aceleração Intel/AMD
  args.push('-y', '-i', input); // [0] = vídeo
  let frameIdx = -1, musicIdx = -1, next = 1;
  if (hasFrame) { args.push('-i', framePath(id)); frameIdx = next++; }
  if (hasMusic) { args.push('-stream_loop', '-1', '-i', musicPath(id)); musicIdx = next++; }

  // Limita a resolução CEDO (acelera e reduz a memória do reverse do boomerang).
  const scale = MAX_HEIGHT > 0 ? `scale=-2:'min(ih,${MAX_HEIGHT})',` : '';

  // Cadeia de vídeo: (escala) -> efeito -> (moldura) -> formato -> [vout]
  let fc;
  if (effect === 'boomerang') {
    // divide em duas cópias: uma normal e uma invertida, e concatena (ida+volta)
    fc = `[0:v]${scale}fps=${f},split[a][b];[b]reverse[r];[a][r]concat=n=2:v=1:a=0[fx];`;
  } else if (effect === 'slow') {
    fc = `[0:v]${scale}setpts=2.0*PTS[fx];`;
  } else {
    fc = `[0:v]${scale}null[fx];`;
  }
  let vlabel = '[fx]';
  if (hasFrame) {
    // escala a moldura para o tamanho do vídeo e sobrepõe
    fc += `[${frameIdx}:v][fx]scale2ref=w=iw:h=ih[frm][base];[base][frm]overlay=0:0[ov];`;
    vlabel = '[ov]';
  }
  // formato final: VAAPI sobe pra GPU (hwupload); senão yuv420p (compatível)
  const finalFmt = isVaapi ? 'format=nv12,hwupload' : 'format=yuv420p';
  fc += `${vlabel}${finalFmt}[vout]`;
  args.push('-filter_complex', fc, '-map', '[vout]');

  if (hasMusic) {
    args.push('-map', `${musicIdx}:a`, '-c:a', 'aac', '-shortest');
  } else if (effect === 'normal') {
    args.push('-map', '0:a?', '-c:a', 'aac'); // mantém áudio original se houver
  } else {
    args.push('-an');
  }
  args.push(...vencArgs(), '-movflags', '+faststart', output);
  return args;
}

function processVideo(id, effect, fps) {
  const input = rawPath(id);
  const output = finalPath(id);
  const args = ffmpegArgs(id, effect, fps);
  console.log(`Processando ${id} (${effect}): ffmpeg ${args.join(' ')}`);
  const proc = spawn('ffmpeg', args);
  let errLog = '';
  proc.stderr.on('data', (d) => { errLog += d.toString().slice(-2000); });
  proc.on('error', (e) => finishProcessing(id, false, 'ffmpeg não encontrado: ' + e.message, input, output));
  proc.on('close', (code) => {
    const ok = code === 0 && fs.existsSync(output) && fs.statSync(output).size > 0;
    finishProcessing(id, ok, ok ? null : ('ffmpeg code ' + code + '\n' + errLog), input, output);
  });
}

function finishProcessing(id, ok, error, input, output) {
  const meta = readMeta(id) || { id };
  if (ok) {
    meta.status = 'done';
    try { fs.unlinkSync(input); } catch (e) {}
  } else {
    console.error(`Falha ao processar ${id}: ${error}`);
    // Fallback: usa o bruto como final para o cliente ainda baixar algo.
    try { if (!fs.existsSync(output)) fs.renameSync(input, output); } catch (e) {}
    meta.status = fs.existsSync(output) ? 'done' : 'error';
    meta.error = (error || '').slice(0, 500);
  }
  // limpa moldura/música temporárias
  try { fs.unlinkSync(framePath(id)); } catch (e) {}
  try { fs.unlinkSync(musicPath(id)); } catch (e) {}
  writeMeta(id, meta);
}

// ---------- Upload ----------
// Recebe moldura (PNG) ou música, associadas ao id do vídeo.
function handleAsset(req, res) {
  if (API_KEY && req.headers['x-api-key'] !== API_KEY) return send(res, 401, 'Chave inválida');
  const u = new URL(req.url, 'http://x');
  const id = u.searchParams.get('id') || '';
  const kind = u.searchParams.get('kind') || '';
  if (!isValidId(id) || (kind !== 'frame' && kind !== 'music')) return send(res, 400, 'Parâmetros inválidos');
  const dest = kind === 'frame' ? framePath(id) : musicPath(id);
  const out = fs.createWriteStream(dest);
  req.pipe(out);
  out.on('finish', () => send(res, 200, 'ok'));
  out.on('error', () => send(res, 500, 'Falha'));
  req.on('error', () => out.destroy());
}

function handleUpload(req, res) {
  if (API_KEY && req.headers['x-api-key'] !== API_KEY) return send(res, 401, 'Chave inválida');
  const u = new URL(req.url, 'http://x');
  const reqId = u.searchParams.get('id') || '';
  const id = isValidId(reqId) ? reqId : crypto.randomUUID();
  const name = (u.searchParams.get('name') || 'Prime360.mp4').replace(/[^\w.\- ]/g, '_');
  const effect = (u.searchParams.get('effect') || 'normal').toLowerCase();
  const fps = u.searchParams.get('fps') || '20';
  const event = (u.searchParams.get('event') || '').replace(/[^\w.\- ]/g, '_');

  const out = fs.createWriteStream(rawPath(id));
  req.pipe(out);
  out.on('finish', () => {
    writeMeta(id, { id, name, event, effect, status: 'processing', createdAt: Date.now() });
    // responde já com o link (QR instantâneo)
    send(res, 200, JSON.stringify({ id, url: `${baseUrl(req)}/v/${id}` }), {
      'Content-Type': 'application/json; charset=utf-8'
    });
    // processa em segundo plano
    processVideo(id, effect, fps);
  });
  out.on('error', () => send(res, 500, 'Falha ao salvar'));
  req.on('error', () => out.destroy());
}

// ---------- Stream / download ----------
function serveFile(req, res, id, asDownload) {
  const file = finalPath(id);
  if (!isValidId(id) || !fs.existsSync(file)) return send(res, 404, 'Não encontrado');
  const stat = fs.statSync(file);
  if (asDownload) {
    let name = 'Prime360.mp4';
    const m = readMeta(id); if (m && m.name) name = m.name;
    res.writeHead(200, {
      'Content-Type': 'video/mp4', 'Content-Length': stat.size,
      'Content-Disposition': `attachment; filename="${name}"`
    });
    return fs.createReadStream(file).pipe(res);
  }
  const range = req.headers.range;
  if (range) {
    const mm = /bytes=(\d*)-(\d*)/.exec(range);
    const start = mm && mm[1] ? parseInt(mm[1], 10) : 0;
    const end = mm && mm[2] ? parseInt(mm[2], 10) : stat.size - 1;
    if (start >= stat.size) return send(res, 416, 'Range inválido');
    res.writeHead(206, {
      'Content-Range': `bytes ${start}-${end}/${stat.size}`, 'Accept-Ranges': 'bytes',
      'Content-Length': end - start + 1, 'Content-Type': 'video/mp4'
    });
    fs.createReadStream(file, { start, end }).pipe(res);
  } else {
    res.writeHead(200, { 'Content-Length': stat.size, 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes' });
    fs.createReadStream(file).pipe(res);
  }
}

// ---------- Páginas ----------
function pageProcessing() {
  return `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="3">
<title>Preparando seu vídeo…</title>
<style>
 body{margin:0;background:#0B0B0F;color:#fff;font-family:-apple-system,Segoe UI,Roboto,sans-serif;
 display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;padding:24px;text-align:center}
 .ring{width:64px;height:64px;border:5px solid #2a2a35;border-top-color:#8B5CF6;border-radius:50%;animation:spin 1s linear infinite;margin-bottom:20px}
 @keyframes spin{to{transform:rotate(360deg)}}
 h1{font-size:20px;margin:0 0 8px} p{color:#9a9aa8;font-size:14px}
</style></head><body>
 <div class="ring"></div>
 <h1>Preparando seu vídeo… 🎬</h1>
 <p>Isso leva alguns segundos. A página atualiza sozinha.</p>
</body></html>`;
}

function pageReady(id) {
  return `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Seu vídeo 360</title>
<style>
 body{margin:0;background:#0B0B0F;color:#fff;font-family:-apple-system,Segoe UI,Roboto,sans-serif;
 display:flex;flex-direction:column;align-items:center;padding:24px}
 h1{font-size:20px;margin:12px 0 16px}
 video{width:100%;max-width:420px;border-radius:14px;background:#000}
 a.btn{margin-top:20px;background:#8B5CF6;color:#fff;text-decoration:none;font-weight:bold;
 padding:16px 28px;border-radius:30px;font-size:18px}
 p.dica{color:#9a9aa8;font-size:13px;margin-top:14px;text-align:center}
</style></head><body>
 <h1>🎬 Seu vídeo está pronto!</h1>
 <video src="/raw/${id}" controls playsinline autoplay muted loop></video>
 <a class="btn" href="/dl/${id}">⬇️ Baixar vídeo</a>
 <p class="dica">Toque em "Baixar vídeo" para salvar no seu celular.</p>
</body></html>`;
}

function handlePage(req, res, id) {
  const meta = readMeta(id);
  if (!isValidId(id) || !meta) return send(res, 404, 'Vídeo não encontrado');
  const html = meta.status === 'done' ? pageReady(id) : pageProcessing();
  send(res, 200, html, { 'Content-Type': 'text/html; charset=utf-8' });
}

// ---------- Roteamento ----------
const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  const p = u.pathname.split('/').filter(Boolean);
  const id2 = (p[1] || '').replace(/\.mp4$/, '');

  if (req.method === 'POST' && p[0] === 'upload') return handleUpload(req, res);
  if (req.method === 'POST' && p[0] === 'asset') return handleAsset(req, res);
  if (req.method === 'GET' && p[0] === 'healthz') return send(res, 200, 'ok');
  if (req.method === 'GET' && p[0] === 'status' && p[1]) {
    const m = readMeta(id2);
    return send(res, m ? 200 : 404, JSON.stringify({ status: m ? m.status : 'unknown' }),
      { 'Content-Type': 'application/json' });
  }
  if (req.method === 'GET' && p[0] === 'v' && p[1]) return handlePage(req, res, id2);
  if (req.method === 'GET' && p[0] === 'raw' && p[1]) return serveFile(req, res, id2, false);
  if (req.method === 'GET' && p[0] === 'dl' && p[1]) return serveFile(req, res, id2, true);
  if (req.method === 'GET' && p.length === 0) return send(res, 200, 'Prime360 server ok');
  send(res, 404, 'Não encontrado');
});

server.listen(PORT, () => {
  console.log(`Prime360 server na porta ${PORT} (dados em ${VIDEOS_DIR})`);
  if (!API_KEY) console.warn('AVISO: API_KEY vazia — qualquer um pode enviar vídeos!');
});
