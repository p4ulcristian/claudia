# Claudia's hub, for the gate: the page built ahead into plain files (the gate has too little memory
# to bundle it), and only the hub's own code beside it. No shell, runs as any uid.
#   docker build -f deploy/hub.Dockerfile --build-arg COMMIT=$(git rev-parse --short HEAD) .
FROM oven/bun:1.4.2 AS build
WORKDIR /src
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile --production
COPY . .
ARG COMMIT=unknown
RUN printf '%s\n' "$COMMIT" > src/commit.txt \
 && bun build ./web/index.html --outdir /out/web --minify --public-path=/ \
 && cp -r web/app-icon /out/web/app-icon \
 && mkdir -p /out/app/src/hub /out/app/web \
 && cp hub.ts package.json /out/app/ \
 && cp src/proxy.ts src/version.ts src/api-version.ts src/commit.txt /out/app/src/ \
 && cp src/hub/*.ts /out/app/src/hub/ \
 && cp -r web/app-icon /out/app/web/app-icon

FROM oven/bun:1.4.2-distroless
COPY --from=build /out/app /app
COPY --from=build /out/web /web
WORKDIR /app
ENV NODE_ENV=production HUB_WEB=/web HUB_DATA=/data
USER 10010:10010
ENTRYPOINT ["bun", "hub.ts"]
