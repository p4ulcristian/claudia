# Claudia's hub, for the gate: the page built ahead into plain files (the gate has too little memory
# to bundle it), and only the hub's own code beside it, plus jose (signs the APNs token for phone
# pushes; it has no dependencies of its own). No shell, runs as any uid. The last step resolves the
# hub's whole import graph from the copied files, so a file missing from the list fails the build
# instead of the hub at start (src/presence.ts once did).
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
 && cp src/proxy.ts src/version.ts src/api-version.ts src/presence.ts src/commit.txt /out/app/src/ \
 && cp src/hub/*.ts /out/app/src/hub/ \
 && mkdir -p /out/app/node_modules && cp -r node_modules/jose /out/app/node_modules/jose \
 && cp -r web/app-icon /out/app/web/app-icon \
 && cd /out/app && bun build hub.ts --target bun --outdir /tmp/hub-check --external ./web/index.html >/dev/null

FROM oven/bun:1.4.2-distroless
COPY --from=build /out/app /app
COPY --from=build /out/web /web
WORKDIR /app
ENV NODE_ENV=production HUB_WEB=/web HUB_DATA=/data
USER 10010:10010
ENTRYPOINT ["bun", "hub.ts"]
