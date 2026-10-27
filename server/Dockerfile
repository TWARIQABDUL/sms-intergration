FROM node:20-bookworm-slim AS builder
WORKDIR /app

# python3/make/g++ are only needed if better-sqlite3 has no prebuilt binary
# for this platform and falls back to compiling from source.
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*

COPY package*.json ./
RUN npm install --omit=dev
COPY src ./src

FROM node:20-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production

COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package*.json ./
COPY --from=builder /app/src ./src

# Persistent disk mount point for the SQLite file (see README: Render Disks).
RUN mkdir -p /data
ENV DB_PATH=/data/sms-gateway.db

EXPOSE 3000
CMD ["node", "src/server.js"]
