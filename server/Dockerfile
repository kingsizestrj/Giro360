FROM node:20-alpine

# FFmpeg faz o processamento dos efeitos (boomerang, câmera lenta, etc.)
RUN apk add --no-cache ffmpeg

WORKDIR /app
COPY server.js ./

# Sem dependências npm — Node puro.
ENV PORT=8080 DATA_DIR=/data
EXPOSE 8080
VOLUME ["/data"]

CMD ["node", "server.js"]
