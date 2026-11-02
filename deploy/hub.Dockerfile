# syntax=docker/dockerfile:1
# Claudia's hub, for the gate (under 1 GB of RAM): built here, run as plain Node with only the
# compiled hub, the built page and the one npm package the hub loads at run time (ws).
#   docker build -f deploy/hub.Dockerfile -t claudia-hub .
#   docker run --read-only -p 127.0.0.1:4590:4590 -v /etc/claudia-hub:/config:ro claudia-hub
# The config folder (holding hub.json) is mounted, not the file: an edited hub.json replaces the
# file, and a single-file mount would keep showing the old one.

# shadow-cljs needs Java to compile; the JRE comes from Temurin, Node from the official image.
FROM eclipse-temurin:25-jre AS java

FROM node:24-trixie-slim AS build
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH
WORKDIR /src
COPY package.json package-lock.json ./
# No install scripts: nothing the hub or the build uses needs one.
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY shadow-cljs.edn ./
COPY src src
COPY resources resources
RUN --mount=type=cache,target=/root/.m2 \
    npx shadow-cljs release hub web && npm run build:web-files
RUN mkdir -p /app/node_modules \
 && cp out/hub.js /app/ \
 && cp -r node_modules/ws /app/node_modules/ws \
 && cp -r out/web /web \
 && cd /app && node -e "require('ws')"

FROM gcr.io/distroless/nodejs24-debian13:nonroot
COPY --from=build /app /app
COPY --from=build /web /web
WORKDIR /app
ENV NODE_ENV=production HUB_HOST=0.0.0.0 HUB_PORT=4590 HUB_WEB=/web HUB_CONFIG=/config/hub.json
EXPOSE 4590
# nonroot: uid 65532; the hub writes nothing, so --read-only works.
USER 65532:65532
CMD ["hub.js"]
