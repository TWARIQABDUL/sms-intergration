FROM node:20-bookworm-slim AS builder
WORKDIR /app

# python3/make/g++ are only needed if @libsql/client's native binary has no
# prebuilt match for this platform and falls back to compiling from source.
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

# Storage lives in Turso (TURSO_DATABASE_URL/TURSO_AUTH_TOKEN, set in the
# deploy environment) - no local volume/disk needed on the host.
EXPOSE 3000
CMD ["node", "src/server.js"]
