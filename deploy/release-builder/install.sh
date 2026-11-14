#!/usr/bin/env bash
# Makes this Linux machine Claudia's release builder (README decision 29). Root:
#
#   sudo deploy/release-builder/install.sh https://<hub address>
#
# Makes the user claudia-release-builder (no sudo, home 0700, no login shell), its tools (Node, a
# JDK and clj-kondo, checksums pinned below), its signing key, its secret, its network fence (internet only:
# nothing on this machine's loopback, LAN, WireGuard or Tailscale) and its unit; then prints:
#   - the builder key (claudia-builder:<host>:<secret>): paste it in the hub's Settings, Release builder;
#   - the line for deploy/release-signers: a PR adding it lets servers and the gate trust this builder.
# Running it again keeps the key and the secret, and prints both lines again.
set -euo pipefail
hub=${1:?usage: install.sh https://<hub address>}
hub=${hub%/}
[[ $hub =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?$ ]] || { echo "the hub's address: https://host" >&2; exit 1; }
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
here=$(cd "$(dirname "$0")" && pwd)
user=claudia-release-builder
home=/home/$user
node_version=26.13.1
node_sha=5c063e46bab0fbc1c17cc445d7ff129cbdcd0471be6ed83b6ab864f8143bc864
jdk_url=https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz
jdk_sha=68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb
# The same clj-kondo as the PR check (.github/workflows/check.yml).
kondo_version=2026.05.25
kondo_sha=ba8742f90555332852a3f63260611ca75cc7ba4e2b6dfe601bf5011bb9402b45

for t in git curl tar xz unzip ssh-keygen nft systemctl; do command -v "$t" >/dev/null || { echo "missing: $t" >&2; exit 1; }; done

id "$user" >/dev/null 2>&1 ||
	useradd --create-home --home-dir "$home" --shell /usr/sbin/nologin --user-group \
		--comment "Builds Claudia releases (README decision 29)" "$user"
chmod 0700 "$home"
as() { runuser -u "$user" -- "$@"; }

# tool <url> <sha256> <folder>: a tarball, checked, unpacked into $home/tools/<folder>.
tool() {
	local d
	d=$(as mktemp -d)
	as curl -fsSL -o "$d/t" "$1"
	echo "$2  $d/t" | as sha256sum -c - >/dev/null
	as rm -rf "$home/tools/$3"
	as mkdir -p "$home/tools/$3"
	as tar -xf "$d/t" -C "$home/tools/$3" --strip-components=1
	as rm -rf "$d"
}
[ "$(as "$home/tools/node/bin/node" --version 2>/dev/null)" = "v$node_version" ] ||
	tool "https://nodejs.org/dist/v$node_version/node-v$node_version-linux-x64.tar.xz" "$node_sha" node
as test -x "$home/tools/jdk/bin/java" || tool "$jdk_url" "$jdk_sha" jdk
if [ "$(as "$home/tools/kondo/clj-kondo" --version 2>/dev/null)" != "clj-kondo v$kondo_version" ]; then
	d=$(as mktemp -d)
	as curl -fsSL -o "$d/k.zip" "https://github.com/clj-kondo/clj-kondo/releases/download/v$kondo_version/clj-kondo-$kondo_version-linux-static-amd64.zip"
	echo "$kondo_sha  $d/k.zip" | as sha256sum -c - >/dev/null
	as rm -rf "$home/tools/kondo" && as mkdir -p "$home/tools/kondo" && as unzip -q "$d/k.zip" -d "$home/tools/kondo"
	as rm -rf "$d"
fi

as test -f "$home/signing-key" || as ssh-keygen -q -t ed25519 -N '' -C "$user@$(hostname -s)" -f "$home/signing-key"
as test -f "$home/token" || as sh -c "umask 077; head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=' > '$home/token'"
as sh -c "umask 077; printf 'HUB_URL=%s\n' '$hub' > '$home/env'"
install -o "$user" -g "$user" -m 0755 "$here/builder.sh" "$home/builder.sh"

install -m 0644 "$here/fence.nft" /etc/nftables-claudia-release-builder.nft
install -m 0644 "$here/claudia_release_builder.slice" /etc/systemd/system/claudia_release_builder.slice
install -m 0644 "$here/claudia-release-builder.service" /etc/systemd/system/claudia-release-builder.service
systemctl daemon-reload
systemctl enable claudia-release-builder.service >/dev/null
systemctl restart claudia-release-builder.service

host=$(hostname -s | tr -c 'A-Za-z0-9.\n-' '-')
echo
echo "Paste this in the hub's Settings, Release builder:"
echo "  claudia-builder:$host:$(cat "$home/token")"
echo
echo "For the status \`builder\` (the check main requires): a fine-grained GitHub token, this repository's"
echo "  commit statuses only, in $home/github-token (0600, owned by $user)."
echo
echo "And add this line to deploy/release-signers in a PR (servers and the gate trust it once merged):"
echo "  claudia-release-builder namespaces=\"claudia-release\" $(cut -d' ' -f1,2 "$home/signing-key.pub") builder-$(date +%Y-%m-%d)"
