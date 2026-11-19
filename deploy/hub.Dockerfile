# syntax=docker/dockerfile:1
# Claudia's hub, for the gate (under 1 GB of RAM): plain Node with only the compiled hub and key
# broker, the built page and the npm packages they load at run time. Nothing is compiled or run here:
# the image is put together by COPY from a bundle check.yml built (out/, production node_modules,
# version.json), so .github/workflows/stage.yml can make it without running the bundle's code (it
# refuses a Dockerfile with RUN). By hand, from a built checkout:
#   npm ci && npm run build && echo '{"version":"dev","tree":"dev"}' > version.json
#   docker build -f deploy/hub.Dockerfile -t claudia-hub .
#   docker run --read-only -p 127.0.0.1:4590:4590 -v /var/lib/claudia-hub:/data claudia-hub
# /data (writable, owned by the hub's uid) holds the store (hub.db) and the peer requests; a
# hub.json there is imported once into an empty store. The gate passes the commit as HUB_COMMIT.

FROM gcr.io/distroless/nodejs24-debian13:nonroot
COPY out/hub.js out/broker.js version.json /app/
COPY node_modules/ws /app/node_modules/ws
COPY node_modules/jose /app/node_modules/jose
# sshpk and its dependencies: the SSH certificate authority (README decision 53).
COPY node_modules/sshpk /app/node_modules/sshpk
COPY node_modules/asn1 /app/node_modules/asn1
COPY node_modules/safer-buffer /app/node_modules/safer-buffer
COPY node_modules/assert-plus /app/node_modules/assert-plus
COPY node_modules/dashdash /app/node_modules/dashdash
COPY node_modules/getpass /app/node_modules/getpass
COPY node_modules/jsbn /app/node_modules/jsbn
COPY node_modules/tweetnacl /app/node_modules/tweetnacl
COPY node_modules/ecc-jsbn /app/node_modules/ecc-jsbn
COPY node_modules/bcrypt-pbkdf /app/node_modules/bcrypt-pbkdf
# The key broker (out/broker.js, README decision 66) runs from this same image as its own
# container and user: the WebAuthn checks, the 1Password SDK, libsodium and their dependencies.
COPY node_modules/@1password/sdk /app/node_modules/@1password/sdk
COPY node_modules/@1password/sdk-core /app/node_modules/@1password/sdk-core
COPY node_modules/libsodium-wrappers /app/node_modules/libsodium-wrappers
COPY node_modules/libsodium /app/node_modules/libsodium
COPY node_modules/@simplewebauthn/server /app/node_modules/@simplewebauthn/server
COPY node_modules/@hexagon/base64 /app/node_modules/@hexagon/base64
COPY node_modules/@levischuck/tiny-cbor /app/node_modules/@levischuck/tiny-cbor
COPY node_modules/@peculiar/asn1-android /app/node_modules/@peculiar/asn1-android
COPY node_modules/@peculiar/asn1-schema /app/node_modules/@peculiar/asn1-schema
COPY node_modules/@peculiar/utils /app/node_modules/@peculiar/utils
COPY node_modules/tslib /app/node_modules/tslib
COPY node_modules/asn1js /app/node_modules/asn1js
COPY node_modules/pvtsutils /app/node_modules/pvtsutils
COPY node_modules/pvutils /app/node_modules/pvutils
COPY node_modules/@peculiar/asn1-ecc /app/node_modules/@peculiar/asn1-ecc
COPY node_modules/@peculiar/asn1-x509 /app/node_modules/@peculiar/asn1-x509
COPY node_modules/@peculiar/asn1-rsa /app/node_modules/@peculiar/asn1-rsa
COPY node_modules/@peculiar/asn1-x509-post-quantum /app/node_modules/@peculiar/asn1-x509-post-quantum
COPY node_modules/@peculiar/asn1-asym-key /app/node_modules/@peculiar/asn1-asym-key
COPY node_modules/@peculiar/asn1-pkcs8 /app/node_modules/@peculiar/asn1-pkcs8
COPY node_modules/@peculiar/x509 /app/node_modules/@peculiar/x509
COPY node_modules/@peculiar/asn1-cms /app/node_modules/@peculiar/asn1-cms
COPY node_modules/@peculiar/asn1-x509-attr /app/node_modules/@peculiar/asn1-x509-attr
COPY node_modules/@peculiar/asn1-csr /app/node_modules/@peculiar/asn1-csr
COPY node_modules/@peculiar/asn1-pkcs9 /app/node_modules/@peculiar/asn1-pkcs9
COPY node_modules/@peculiar/asn1-pfx /app/node_modules/@peculiar/asn1-pfx
COPY node_modules/tsyringe /app/node_modules/tsyringe
COPY node_modules/reflect-metadata /app/node_modules/reflect-metadata
COPY out/web /web
WORKDIR /app
ENV NODE_ENV=production HUB_HOST=0.0.0.0 HUB_PORT=4590 HUB_WEB=/web HUB_DATA=/data HUB_CONFIG=/data/hub.json
EXPOSE 4590
# nonroot: uid 65532 (the gate runs it as its own uid); the hub writes only into /data, so
# --read-only works.
USER 65532:65532
CMD ["hub.js"]
