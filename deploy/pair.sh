#!/usr/bin/env bash
# Give this server's front its hub key (README decision 47):
#
#   sudo deploy/pair.sh https://<hub address>     a shared server (/etc/claudia/env)
#   deploy/pair.sh https://<hub address>          your own machine (~/.config/claudia/env)
#
# Makes a one-time secret, prints the pairing key to paste into the hub's Servers page (with the
# address the hub reaches this front at: FRONT_HOST:FRONT_PORT on the private network joining
# them, e.g. a Tailscale or Headscale address), waits until the hub hands out this server's key,
# writes it to the key file (FRONT_HUB_KEY_FILE) and restarts the front. The front answers no call
# without that key, so reaching its port is not enough to act as the hub.
#
# Pairing a server the hub already knows (the same name on the Servers page) gives it its key again.
#
# Needs: curl, node.
set -euo pipefail
hub="${1:?usage: pair.sh https://<hub address>}"
hub="${hub%/}"
for t in curl node; do command -v "$t" >/dev/null || { echo "missing: $t" >&2; exit 1; }; done

if [ "$(id -u)" = 0 ]; then
	env=/etc/claudia/env dir=/etc/claudia/pairing key_file=/etc/claudia/hub-key
	[ -f $env ] || { echo "install Claudia first (deploy/shared-server/install.sh)" >&2; exit 1; }
else
	env="$HOME/.config/claudia/env" dir="$HOME/.config/claudia/pairing" key_file="$HOME/.config/claudia/hub-key"
	[ -f "$env" ] || { echo "no $env: write it first (deploy/env.example), or run as root on a shared server" >&2; exit 1; }
fi

set_env() { # name value
	if grep -q "^$1=" "$env"; then sed -i.bak "s|^$1=.*|$1=$2|" "$env" && rm -f "$env.bak"; else echo "$1=$2" >>"$env"; fi
}

restart_front() {
	if [ "$(id -u)" = 0 ]; then systemctl restart claudia-front.service
	elif [ "$(uname)" = Darwin ]; then launchctl kickstart -k "gui/$(id -u)/claudia.front" 2>/dev/null || echo "restart the front (launchd) yourself"
	else systemctl --user restart claudia-front.service
	fi
}

install -d -m 0700 "$dir"
umask 077
[ -f "$dir/secret" ] || head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' >"$dir/secret"
host=$(hostname -s | tr -cd 'A-Za-z0-9-')
listen="$(sed -n 's/^FRONT_HOST=//p' "$env" | tail -1):$(sed -n 's/^FRONT_PORT=//p' "$env" | tail -1)"

echo
echo "Paste this into the hub's Servers page, with the address the hub reaches this front at"
echo "(the front listens on ${listen}, FRONT_HOST:FRONT_PORT in $env):"
echo
echo "  claudia-pair:${host}:$(cat "$dir/secret")"
echo
echo "Waiting for the hub (Ctrl+C stops; running this again prints the same key)…"

# The hub answers 404 until the key is pasted, then 200 { name, key } once.
while :; do
	code=$(curl -sS -o "$dir/enroll.json" -w '%{http_code}' -H 'content-type: application/json' \
		--data "{\"secret\":\"$(cat "$dir/secret")\"}" "$hub/hub/enroll" || echo 000)
	[ "$code" = 200 ] && break
	[ "$code" = 404 ] || [ "$code" = 000 ] || { echo "hub answered $code: $(cat "$dir/enroll.json")" >&2; exit 1; }
	sleep 5
done

read -r name key < <(node -e '
  const r = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
  const ok = (v, re) => { if (typeof v !== "string" || !re.test(v)) throw new Error("bad answer from the hub"); return v; };
  console.log([ok(r.name, /^[a-z][a-z0-9-]{0,30}$/), ok(r.key, /^[A-Za-z0-9_-]{40,}$/)].join(" "));' "$dir/enroll.json")

printf '%s\n' "$key" >"$key_file.new"
if [ "$(id -u)" = 0 ]; then chown root:claudia "$key_file.new" && chmod 0640 "$key_file.new"; fi
mv "$key_file.new" "$key_file"
set_env FRONT_HUB_KEY_FILE "$key_file"
restart_front
rm -f "$dir/secret" "$dir/enroll.json"
echo "Paired as $name: the front's hub key is in $key_file. Tick people for it on the hub's Access page."
