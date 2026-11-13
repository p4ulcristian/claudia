"""A small App Store Connect API client (the same one Roomprint uses).

  iphone/tools/asc GET /v1/bundleIds
  iphone/tools/asc POST /v1/bundleIds '{"data": {...}}'

Reads ASC_KEY_ID, ASC_KEY_FILE and ASC_ISSUER_ID from the environment (iphone/tools/asc
loads them from the local config).
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

import jwt


def token():
    now = int(time.time())
    return jwt.encode({"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"},
                      open(os.environ["ASC_KEY_FILE"]).read(), algorithm="ES256",
                      headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"})


def call(method, path, body=None):
    """One API call; returns the parsed JSON (empty dict for no content). Exits on an HTTP error."""
    url = path if path.startswith("https://") else "https://api.appstoreconnect.apple.com" + path
    req = urllib.request.Request(url, json.dumps(body).encode() if body is not None else None, method=method,
                                 headers={"Authorization": f"Bearer {token()}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path}: {e.code} {e.read().decode()}")


if __name__ == "__main__":
    method, path = sys.argv[1], sys.argv[2]
    body = json.loads(sys.argv[3]) if len(sys.argv) > 3 else None
    print(json.dumps(call(method, path, body), indent=2))
