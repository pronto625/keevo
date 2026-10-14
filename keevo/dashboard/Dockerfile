# ─── Keevo admin dashboard (Next.js standalone) ──────────────────────────────
#
# Backend URL — read this before changing anything:
#   The browser only ever calls same-origin /api/backend/*; next.config.ts rewrites it
#   server-side to ${NEXT_PUBLIC_API_URL}/api/v1/*. Next.js resolves rewrites() at BUILD
#   time (the destination is written into routes-manifest.json and server.js), so a
#   NEXT_PUBLIC_API_URL passed at `docker run` time is IGNORED. The image therefore bakes
#   the internal Docker DNS name http://backend:4500: the compose service `backend` on the
#   network shared with the dashboard (prod keevo_net, staging keevo_staging_net). Traffic
#   to the API never leaves the Docker network and no public URL is baked in. To target
#   another backend, rebuild with --build-arg NEXT_PUBLIC_API_URL=...
#
# Multi-arch: the build stages run on the build machine's platform ($BUILDPLATFORM) and
# produce platform-independent JS; the runtime stage has no RUN step, so building
# linux/arm64 needs no QEMU emulation.

ARG NODE_IMAGE=node:24-alpine

# ─── Stage 1 : dependencies ──────────────────────────────────────────────────
FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# ─── Stage 2 : build ─────────────────────────────────────────────────────────
FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS builder
WORKDIR /app
ARG NEXT_PUBLIC_API_URL=http://backend:4500
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL} \
    NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ─── Stage 3 : runtime ───────────────────────────────────────────────────────
FROM ${NODE_IMAGE} AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# Non-root: the official image ships the unprivileged `node` user (uid/gid 1000).
COPY --from=builder --chown=node:node /app/public ./public
COPY --from=builder --chown=node:node /app/.next/standalone ./
COPY --from=builder --chown=node:node /app/.next/static ./.next/static
USER node

EXPOSE 3000

# /login is public (no auth cookie needed) and served by the Next server itself.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:3000/login || exit 1

CMD ["node", "server.js"]
