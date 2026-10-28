# Claudia for iPhone

A native shell around the hub's page (README decision 23): one full-screen web view on the hub, plus Apple push. The page is the same one browsers get, so page changes need no new build.

- **The page**: a `WKWebView` on the hub, under the status bar; the page pads itself by the safe areas. Cookies and storage persist, so the sign-in survives restarts. The edge swipe goes back through the page's history (chat → chat list). Links to other sites open in the browser; the hub and the sign-in hosts stay in the app. File inputs (photos, camera) are WebKit's own.
- **What the page is told**: before its scripts run, `window.claudiaNative = { platform: "ios", version, build }` (hub origin only, never the sign-in pages). Once APNs gives a token: `window.claudiaNative.pushToken = { token, env, topic }` and a `claudia-push-token` event on `window` with the same `detail`, again after every page load. `env` is `production` for TestFlight and App Store builds and `development` for development-signed ones (read from the embedded provisioning profile); `topic` is the bundle id the build runs under. The page registers it with the hub.
- **Push**: asks for notification permission on the first launch. A notification's `url` (a path on the hub) opens on tap, also from a cold start. In the foreground, a notification for the chat on screen (its `url`, or its `chat` + `server`, against the web view's address) shows no banner; others do. The hub sends `interruption-level: time-sensitive` for questions and permission asks; it breaks through Focus only once the App ID has the Time Sensitive Notifications capability (switched on in the developer website: the API cannot), and the next build signs with it by itself.
- **Passkeys**: the sign-in (oauth2-proxy, then Pocket ID) runs inside the web view. WebKit allows WebAuthn there only for a relying party the app is associated with, so the app carries `webcredentials:<Pocket ID host>` and that host must serve `/.well-known/apple-app-site-association` listing `<team id>.com.p4ulcristian.claudia` under `webcredentials.apps`.

## Build and ship to TestFlight

From Linux, Roomprint's recipe: xtool builds, rcodesign signs, the App Store Connect API uploads.

```sh
iphone/tools/testflight.sh              # build, sign, upload (then add the build to a TestFlight group)
iphone/tools/testflight.sh --no-upload  # stop at iphone/build/Claudia.ipa
iphone/tools/asc GET /v1/apps           # any App Store Connect API call with the configured key
```

`testflight.sh` bakes the hub address and the sign-in hosts into Info.plist, makes or reuses the App Store provisioning profile through the API, and signs with the entitlements that profile grants (push, the passkey host, Time Sensitive once enabled).

The icon is `Icon/AppIcon.png`, rendered from `web/app-icon/icon.svg` by `tools/icon.sh`. App Store Connect takes it only from a compiled asset catalog, which only Apple's actool makes: the `app-icon` workflow runs it on a macOS runner when `Icon/AppIcon.png` changes; `tools/fetch-icon.sh` downloads the result into `Icon/compiled/`, which is committed.

## Local config

`~/.config/claudia-ios/` (or `$CLAUDIA_IOS_CONFIG`), never in the repo. `env` holds `HUB_URL`, `LOGIN_HOSTS`, `PASSKEY_HOST`, `ASC_KEY_ID`, `ASC_KEY_FILE`, `ASC_ISSUER_ID`, `ASC_APP_ID`, `DIST_KEY`, `DIST_CER`, `DIST_CERT_ID` and `XTOOL`; `appstore.mobileprovision` is written there by the build.
