# Prime360 — Servidor de vídeos (Docker)

Servidor que recebe os vídeos do app Prime360, **aplica o efeito com FFmpeg**
(boomerang, câmera lenta, etc.) e gera um link/QR Code. O QR aparece na hora;
quando o cliente escaneia, vê "processando" e, ao terminar, o vídeo pronto para
baixar.

Feito em **Node puro + FFmpeg** — sem dependências npm.

## Como funciona

```
App Prime360 --(POST /upload?effect=boomerang + vídeo bruto)--> Servidor
                                          | responde o link na HORA (QR)
                                          | processa em 2º plano (FFmpeg)
Cliente escaneia QR --> GET /v/<id>
   - ainda processando -> página "Preparando seu vídeo…" (atualiza sozinha)
   - pronto            -> preview + botão "Baixar vídeo"
```

Os efeitos rodam no servidor (rápido e correto), então o celular só grava e
envia — o QR sai instantâneo.

## Subir no seu servidor

Pré-requisitos: Docker e Docker Compose instalados.

1. Copie a pasta `server/` para o seu servidor.
2. Edite o `docker-compose.yml`:
   - `API_KEY`: troque por uma chave secreta sua (a mesma vai no app).
   - `PUBLIC_BASE_URL`: a URL pública pela qual o **celular do cliente** acessa.
     - Com domínio + HTTPS: `https://videos.seudominio.com`
     - Acesso por IP na rede local: `http://192.168.0.10:8080`
3. Suba:
   ```bash
   docker compose up -d --build
   ```
4. Teste: abra `http://SEU_IP:8080/healthz` — deve responder `ok`.

Os vídeos ficam em `./data/videos` (montado como volume, persistem entre reinícios).

## No app

Na tela inicial do Giro360, toque na **engrenagem** e preencha:
- **URL do servidor**: o mesmo valor do `PUBLIC_BASE_URL` (ex.: `http://192.168.0.10:8080`)
- **Chave (API key)**: o mesmo valor do `API_KEY`

Depois, na galeria de um vídeo, toque em **QR Code** → **Enviar e gerar QR**.
O app envia o vídeo e mostra o QR pronto pro cliente escanear.

## Velocidade (rodar num notebook na festa) ⚡

Rodar o servidor num **notebook na própria festa** é o cenário mais rápido:
o upload é instantâneo (rede local) e o notebook processa muito mais rápido
que o celular.

Para deixar ainda mais veloz, ajuste no `docker-compose.yml`:

- `VIDEO_PRESET=ultrafast` — encoda o mais rápido possível (arquivo um pouco maior).
- `MAX_HEIGHT=720` — processa/baixa mais rápido (720p é ótimo para celular).
- **Aceleração por HARDWARE (tipo CapCut):** usa o chip de vídeo da CPU/GPU.
  Renderiza um clipe de ~16s em **menos de 1 segundo**.

### Intel (Quick Sync / VAAPI) — ideal para PCs com Intel (ex.: i3 7ª gen)

Seu i3 de 7ª geração tem **Quick Sync**, que faz o encode em hardware. No `docker-compose.yml`:

1. Descomente no serviço:
   ```yaml
   VIDEO_ENCODER: "h264_vaapi"
   LIBVA_DRIVER_NAME: "iHD"
   ```
2. Descomente o bloco que passa a placa de vídeo:
   ```yaml
   devices:
     - /dev/dri:/dev/dri
   ```
3. `docker compose up -d --build`

A imagem já tenta instalar o driver Intel. Se der erro de processamento,
volte para `VIDEO_ENCODER: libx264` + `VIDEO_PRESET: ultrafast` (CPU) — também é
bem rápido.

> **i3 7ª gen com 4 GB sobrando dá conta tranquilo** de clipes curtos. Para
> garantir, deixe `MAX_HEIGHT: 1080` (ou 720) — também reduz a memória do boomerang.

- **NVIDIA:** `VIDEO_ENCODER=h264_nvenc` (precisa runtime NVIDIA no Docker).

Dica: numa festa, crie um **Wi‑Fi local** (roteador ou o hotspot do notebook),
conecte o celular nele e use o IP do notebook em `PUBLIC_BASE_URL`. Os convidados
escaneiam o QR e baixam pela mesma rede — sem depender de internet.

## HTTPS (recomendado com domínio)

Android bloqueia HTTP "puro" por padrão — o app já vem liberado para HTTP para
funcionar com IP local, mas o ideal é HTTPS. Se você tem um domínio:

1. Aponte o domínio (DNS tipo A) para o IP do servidor.
2. No `docker-compose.yml`, descomente o serviço `caddy` e o bloco `volumes`.
3. Edite o `Caddyfile` com o seu domínio.
4. Troque `PUBLIC_BASE_URL` para `https://seu-dominio` e remova o `ports` do
   serviço `giro360` (o Caddy vira a entrada nas portas 80/443).
5. `docker compose up -d --build`

O Caddy obtém e renova o certificado SSL automaticamente.

## Endpoints

| Método | Rota          | Descrição                                    |
|--------|---------------|----------------------------------------------|
| POST   | `/upload`     | Envia o vídeo (corpo bruto). Header `X-Api-Key`. Devolve `{ "url": "..." }` |
| GET    | `/v/<id>`     | Página de download (alvo do QR Code)         |
| GET    | `/raw/<id>`   | Stream do vídeo (com Range, p/ tocar)        |
| GET    | `/dl/<id>`    | Download forçado do arquivo                  |
| GET    | `/healthz`    | Verificação de saúde                         |

## Manutenção

- Os vídeos não são apagados automaticamente. Para limpar antigos, você pode
  apagar arquivos de `./data/videos` (cada vídeo são 2 arquivos: `<id>.mp4` e `<id>.json`).
