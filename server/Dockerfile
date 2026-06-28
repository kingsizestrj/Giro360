FROM node:20-alpine

# FFmpeg faz o processamento dos efeitos (boomerang, câmera lenta, etc.).
# Os drivers Intel (VAAPI) são opcionais — habilitam a aceleração por hardware
# (Quick Sync). O "|| true" garante que o build não falha se não existirem.
RUN apk add --no-cache ffmpeg \
 && (apk add --no-cache intel-media-driver mesa-va-gallium libva-utils || true)

WORKDIR /app
COPY server.js ./

# Sem dependências npm — Node puro.
ENV PORT=8080 DATA_DIR=/data
EXPOSE 8080
VOLUME ["/data"]

CMD ["node", "server.js"]
