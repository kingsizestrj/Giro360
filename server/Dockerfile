FROM node:20-alpine

WORKDIR /app
COPY server.js ./

# Sem dependências externas — Node puro.
ENV PORT=8080 DATA_DIR=/data
EXPOSE 8080
VOLUME ["/data"]

CMD ["node", "server.js"]
