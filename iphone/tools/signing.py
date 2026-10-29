"""App Store signing pieces for tools/testflight.sh. One bundle at a time: the app and each of
its extensions has its own bundle id, provisioning profile and entitlements.

  signing.py profile <bundle id> <bundle name> <cert id> <out.mobileprovision>
      Writes an active App Store provisioning profile for the bundle id, made through the App
      Store Connect API when there is none (a capability change on the App ID invalidates the
      old one, so it is replaced then). An extension's bundle id is registered first when it
      is not there yet; the app's must already exist (the App Store Connect app is made on it).
  signing.py entitlements app <profile> <passkey host> <out.plist>
  signing.py entitlements widget <profile> <out.plist>
  signing.py entitlements notifications <profile> <out.plist>
      Writes the entitlements to sign that bundle with: what it needs, each one checked against
      what its profile grants. Time Sensitive notifications go in only once the App ID has that
      capability (it is switched on in the developer website; the API cannot).
"""
import base64
import fnmatch
import plistlib
import sys

from asc import call

APP_ID = "com.p4ulcristian.claudia"
# The Keychain group the app and the Notification Service Extension share (the device key,
# Sources/ClaudiaShared/DeviceKey.swift), after the team prefix. First in both lists: the app
# stores into its first group, and the extension reads all of its groups.
SHARED_KEYCHAIN = APP_ID + ".shared"


def bundle_id(identifier, name):
    found = call("GET", f"/v1/bundleIds?filter[identifier]={identifier}&include=profiles&limit=200")
    bundle = next((b for b in found["data"] if b["attributes"]["identifier"] == identifier), None)
    if bundle:
        return bundle, found.get("included", [])
    if identifier == APP_ID:
        sys.exit(f"no bundle id {identifier} in App Store Connect")
    made = call("POST", "/v1/bundleIds", {"data": {
        "type": "bundleIds",
        "attributes": {"identifier": identifier, "name": name, "platform": "IOS"}}})
    print(f"registered the bundle id {identifier}")
    return made["data"], []


def profile(identifier, name, cert_id, out):
    bundle, included = bundle_id(identifier, name)
    profile_name = f"{name} App Store"
    profiles = [p for p in included if p["type"] == "profiles"
                and p["attributes"]["profileType"] == "IOS_APP_STORE"]
    active = [p for p in profiles if p["attributes"]["profileState"] == "ACTIVE"]
    if active:
        content = active[0]["attributes"]["profileContent"]
    else:
        for p in profiles:   # invalid ones keep the name taken
            if p["attributes"]["name"] == profile_name:
                call("DELETE", f"/v1/profiles/{p['id']}")
        made = call("POST", "/v1/profiles", {"data": {
            "type": "profiles",
            "attributes": {"name": profile_name, "profileType": "IOS_APP_STORE"},
            "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bundle["id"]}},
                              "certificates": {"data": [{"type": "certificates", "id": cert_id}]}}}})
        content = made["data"]["attributes"]["profileContent"]
        print(f"made a new App Store profile for {identifier}")
    open(out, "wb").write(base64.b64decode(content))


def granted(path):
    raw = open(path, "rb").read()
    start, end = raw.index(b"<?xml"), raw.index(b"</plist>") + len(b"</plist>")
    return plistlib.loads(raw[start:end])["Entitlements"]


def base(g):
    """What every bundle is signed with: who it is, App Store distribution."""
    e = {
        "application-identifier": g["application-identifier"],
        "com.apple.developer.team-identifier": g["com.apple.developer.team-identifier"],
        "get-task-allow": False,
    }
    if g.get("beta-reports-active"):
        e["beta-reports-active"] = True   # TestFlight
    return e


def shared_keychain(g):
    prefix = g["application-identifier"].split(".", 1)[0]
    group = f"{prefix}.{SHARED_KEYCHAIN}"
    if not any(fnmatch.fnmatchcase(group, allowed) for allowed in g.get("keychain-access-groups", [])):
        sys.exit(f"the provisioning profile does not allow the Keychain group {group}")
    return [group]


def entitlements(role, profile_path, *rest):
    g = granted(profile_path)
    e = base(g)
    if role == "app":
        passkey_host, out = rest
        for key in ("aps-environment", "com.apple.developer.associated-domains"):
            if key not in g:
                sys.exit(f"the provisioning profile does not grant {key}: enable it on the App ID")
        if g["aps-environment"] != "production":
            sys.exit(f"the profile's aps-environment is {g['aps-environment']}, not production")
        e["aps-environment"] = "production"
        # Passkeys inside the web view: Pocket ID's relying party must list this app in its
        # /.well-known/apple-app-site-association (webcredentials).
        e["com.apple.developer.associated-domains"] = [f"webcredentials:{passkey_host}"]
        e["keychain-access-groups"] = shared_keychain(g)
        ts = "com.apple.developer.usernotifications.time-sensitive"
        if g.get(ts):
            e[ts] = True
        else:
            print("note: the App ID has no Time Sensitive Notifications capability yet; signing without it")
    elif role == "notifications":
        (out,) = rest
        e["keychain-access-groups"] = shared_keychain(g)
    elif role == "widget":
        (out,) = rest
    else:
        sys.exit(f"unknown bundle role {role}")
    plistlib.dump(e, open(out, "wb"))
    print(f"entitlements ({role}): " + ", ".join(sorted(e)))


if __name__ == "__main__":
    {"profile": profile, "entitlements": entitlements}[sys.argv[1]](*sys.argv[2:])
