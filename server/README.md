# Giro360 — Servidor de vídeos (Docker)

Servidor leve que recebe os vídeos enviados pelo app Giro360, guarda no seu
servidor e gera um link/QR Code. Quando o cliente escaneia o QR, abre uma
página com o vídeo dele e um botão **Baixar vídeo**.

Feito em **Node puro (sem dependências)** — imagem Docker mínima.

## Como funciona

```
App Giro360  --(POST /upload + X-Api-Key)-->  Servidor  --salva-->  /data/videos
                                                  |
Cliente escaneia QR --> GET /v/<id> --> página com preview + botão "Baixar vídeo"
```

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
