#!/usr/bin/env bash
# Join this server to a Claudia hub (run as root, after deploy/install.sh):
#
#   sudo deploy/pair.sh https://<hub address>
#
# Makes a WireGuard key pair and a one-time secret, prints the pairing key to paste into the hub's
# Servers page, then waits until the hub has added this server as a WireGuard peer of the gate and
# sets up the tunnel: interface `claudia`, this server's address from the hub, the gate as its only
# peer. Claudia's page then listens on that address only (GATE_HOST in /etc/claudia/env), and a
# firewall rule lets port 4510 in only through the tunnel, so nobody on this machine can call the
# page pretending to be the hub.
#
# Needs: wireguard-tools (wg, wg-quick), curl, nft.
set -euo pipefail
hub="${1:?usage: pair.sh https://<hub address>}"
hub="${hub%/}"
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
for t in wg wg-quick curl nft; do command -v "$t" >/dev/null || { echo "missing: $t" >&2; exit 1; }; done
[ -f /etc/claudia/env ] || { echo "install Claudia first (deploy/install.sh)" >&2; exit 1; }
[ -e /etc/wireguard/claudia.conf ] && { echo "already paired (/etc/wireguard/claudia.conf); remove it to pair again" >&2; exit 1; }

dir=/etc/claudia/pairing
install -d -m 0700 "$dir"
umask 077
[ -f "$dir/private" ] || wg genkey > "$dir/private"
wg pubkey < "$dir/private" > "$dir/public"
[ -f "$dir/secret" ] || head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' > "$dir/secret"
host=$(hostname -s | tr -cd 'A-Za-z0-9-')

echo
echo "Paste this into the hub's Servers page:"
echo
echo "  claudia-pair:${host}:$(cat "$dir/public"):$(cat "$dir/secret")"
echo
echo "Waiting for the hub (Ctrl+C stops; running this again prints the same key)…"

# The hub answers 202 until the gate has the peer, 200 with the tunnel's details after.
while :; do
  code=$(curl -sS -o "$dir/enroll.json" -w '%{http_code}' -H 'content-type: application/json' \
    --data "{\"secret\":\"$(cat "$dir/secret")\"}" "$hub/hub/enroll" || echo 000)
  [ "$code" = 200 ] && break
  [ "$code" = 404 ] || [ "$code" = 202 ] || [ "$code" = 000 ] || { echo "hub answered $code: $(cat "$dir/enroll.json")" >&2; exit 1; }
  sleep 5
done

# The hub's answer: { name, address, port, gate: { publicKey, endpoint, address } }. Read with the
# one JSON reader every Claudia server has: bun.
read -r name address port gate_key gate_endpoint gate_address < <(/usr/local/bin/bun -e '
  const r = await Bun.file(process.argv[1]).json();
  const ok = (v, re) => { if (typeof v !== "string" || !re.test(v)) throw new Error("bad answer from the hub"); return v; };
  console.log([
    ok(r.name, /^[a-z][a-z0-9-]{0,30}$/), ok(r.address, /^\d+\.\d+\.\d+\.\d+$/), ok(String(r.port), /^\d{1,5}$/),
    ok(r.gate?.publicKey, /^[A-Za-z0-9+\/]{42}[AEIMQUYcgkosw048]=$/), ok(r.gate?.endpoint, /^[A-Za-z0-9.:\[\]-]+:\d{1,5}$/),
    ok(r.gate?.address, /^\d+\.\d+\.\d+\.\d+$/),
  ].join(" "));' "$dir/enroll.json")

cat > /etc/wireguard/claudia.conf <<EOF
# Claudia: this server ($name on the hub) <-> the gate. Written by deploy/pair.sh.
[Interface]
PrivateKey = $(cat "$dir/private")
Address = $address/32

[Peer]
PublicKey = $gate_key
Endpoint = $gate_endpoint
AllowedIPs = $gate_address/32
PersistentKeepalive = 25
EOF
chmod 0600 /etc/wireguard/claudia.conf
systemctl enable --now wg-quick@claudia

# Port 4510 only through the tunnel (and so only from the gate, its one peer).
install -m 0644 /dev/stdin /etc/claudia/guard.nft <<EOF
table inet claudia_guard {
  chain input {
    type filter hook input priority -10; policy accept;
    tcp dport $port iifname != "claudia" drop
  }
}
EOF
nft=$(command -v nft)
install -m 0644 /dev/stdin /etc/systemd/system/claudia-guard.service <<EOF
[Unit]
Description=Claudia: page port reachable through the hub's tunnel only
Before=claudia-page.service
After=nftables.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStartPre=-$nft delete table inet claudia_guard
ExecStart=$nft -f /etc/claudia/guard.nft
ExecStop=$nft delete table inet claudia_guard

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now claudia-guard.service

# The page listens on the tunnel address.
if grep -q '^GATE_HOST=' /etc/claudia/env; then sed -i "s/^GATE_HOST=.*/GATE_HOST=$address/" /etc/claudia/env
else echo "GATE_HOST=$address" >> /etc/claudia/env; fi
systemctl restart claudia-page.service
rm -f "$dir/secret" "$dir/enroll.json"
echo "Paired as $name ($address). Tick people for it in the hub's Access page."
