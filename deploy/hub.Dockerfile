# syntax=docker/dockerfile:1
# Claudia's hub, for the gate (under 1 GB of RAM): plain Node with only the compiled hub, the built
# page and the npm packages the hub loads at run time (ws, jose). Nothing is compiled or run here:
# the image is put together by COPY from a bundle check.yml built (out/, production node_modules,
# version.json), so .github/workflows/stage.yml can make it without running the bundle's code (it
# refuses a Dockerfile with RUN). By hand, from a built checkout:
#   npm ci && npm run build && echo '{"version":"dev","tree":"dev"}' > version.json
#   docker build -f deploy/hub.Dockerfile -t claudia-hub .
#   docker run --read-only -p 127.0.0.1:4590:4590 -v /var/lib/claudia-hub:/data claudia-hub
# /data (writable, owned by the hub's uid) holds the store (hub.db) and the peer requests; a
# hub.json there is imported once into an empty store. The gate passes the commit as HUB_COMMIT.

FROM gcr.io/distroless/nodejs24-debian13:nonroot
COPY out/hub.js version.json /app/
COPY node_modules/ws /app/node_modules/ws
COPY node_modules/jose /app/node_modules/jose
COPY out/web /web
WORKDIR /app
ENV NODE_ENV=production HUB_HOST=0.0.0.0 HUB_PORT=4590 HUB_WEB=/web HUB_DATA=/data HUB_CONFIG=/data/hub.json
EXPOSE 4590
# nonroot: uid 65532 (the gate runs it as its own uid); the hub writes only into /data, so
# --read-only works.
USER 65532:65532
CMD ["hub.js"]
