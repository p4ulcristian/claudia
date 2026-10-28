"""App Store signing pieces for tools/testflight.sh.

  signing.py profile <bundle id> <cert id> <out.mobileprovision>
      Writes an active App Store provisioning profile for the bundle id, made through the App
      Store Connect API when there is none (a capability change on the App ID invalidates the
      old one, so it is replaced then).
  signing.py entitlements <profile> <passkey host> <out.plist>
      Writes the entitlements to sign with: what the app needs, each one checked against what
      the profile grants. Time Sensitive notifications go in only once the App ID has that
      capability (it is switched on in the developer website; the API cannot).
"""
import base64
import plistlib
import sys

from asc import call

PROFILE_NAME = "Claudia App Store"


def profile(identifier, cert_id, out):
    found = call("GET", f"/v1/bundleIds?filter[identifier]={identifier}&include=profiles&limit=200")
    bundle = next((b for b in found["data"] if b["attributes"]["identifier"] == identifier), None)
    if not bundle:
        sys.exit(f"no bundle id {identifier} in App Store Connect")
    profiles = [p for p in found.get("included", []) if p["type"] == "profiles"
                and p["attributes"]["profileType"] == "IOS_APP_STORE"]
    active = [p for p in profiles if p["attributes"]["profileState"] == "ACTIVE"]
    if active:
        content = active[0]["attributes"]["profileContent"]
    else:
        for p in profiles:   # invalid ones keep the name taken
            if p["attributes"]["name"] == PROFILE_NAME:
                call("DELETE", f"/v1/profiles/{p['id']}")
        made = call("POST", "/v1/profiles", {"data": {
            "type": "profiles",
            "attributes": {"name": PROFILE_NAME, "profileType": "IOS_APP_STORE"},
            "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bundle["id"]}},
                              "certificates": {"data": [{"type": "certificates", "id": cert_id}]}}}})
        content = made["data"]["attributes"]["profileContent"]
        print(f"made a new App Store profile for {identifier}")
    open(out, "wb").write(base64.b64decode(content))


def granted(path):
    raw = open(path, "rb").read()
    start, end = raw.index(b"<?xml"), raw.index(b"</plist>") + len(b"</plist>")
    return plistlib.loads(raw[start:end])["Entitlements"]


def entitlements(profile_path, passkey_host, out):
    g = granted(profile_path)
    for key in ("aps-environment", "com.apple.developer.associated-domains"):
        if key not in g:
            sys.exit(f"the provisioning profile does not grant {key}: enable it on the App ID")
    if g["aps-environment"] != "production":
        sys.exit(f"the profile's aps-environment is {g['aps-environment']}, not production")
    e = {
        "application-identifier": g["application-identifier"],
        "com.apple.developer.team-identifier": g["com.apple.developer.team-identifier"],
        "get-task-allow": False,
        "beta-reports-active": True,
        "aps-environment": "production",
        # Passkeys inside the web view: Pocket ID's relying party must list this app in its
        # /.well-known/apple-app-site-association (webcredentials).
        "com.apple.developer.associated-domains": [f"webcredentials:{passkey_host}"],
    }
    ts = "com.apple.developer.usernotifications.time-sensitive"
    if g.get(ts):
        e[ts] = True
    else:
        print("note: the App ID has no Time Sensitive Notifications capability yet; signing without it")
    plistlib.dump(e, open(out, "wb"))
    print("entitlements: " + ", ".join(sorted(e)))


if __name__ == "__main__":
    {"profile": profile, "entitlements": entitlements}[sys.argv[1]](*sys.argv[2:])
