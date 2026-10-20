#!/usr/bin/env bash
# Install or update Claudia on a shared server (run as root): code in /srv/claudia, the page as user
# `claudia`, one runner per line of /etc/claudia/profiles as user claudia-<profile>.
#
#   sudo deploy/install.sh <checkout>       copy the code from <checkout>, then sync users and units
#
# Before the first run, write (not in this repo):
#   /etc/claudia/env       GATE_HOST=<address the gate connects to>, CLAUDIA_NAME, CLAUDIA_PROFILES,
#                          CLAUDIA_TOKEN_FILE=/var/lib/claudia/claude-token, PATH for the runners' tools
#   /etc/claudia/profiles  `<profile> <sign-in email>` per line
# Optional: /etc/claudia/setup-profile <profile>, run after a profile's user is made (tools, repos).
# After the first run, claudia-update.timer keeps the server on GitHub's main (deploy/update.sh).
# Restarting runners stops their chats: only runners whose code changed are restarted, and only
# with --restart-runners.
set -euo pipefail
src="${1:?usage: install.sh <checkout> [--restart-runners]}"
restart_runners="${2:-}"
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
[ -f /etc/claudia/env ] && [ -f /etc/claudia/profiles ] || { echo "write /etc/claudia/env and /etc/claudia/profiles first" >&2; exit 1; }

getent group claudia-run >/dev/null || groupadd --system claudia-run
id claudia >/dev/null 2>&1 || useradd --system --user-group --home-dir /nonexistent --shell /usr/sbin/nologin claudia

# The shared token: the page writes it, the runners (group claudia-run) read it.
install -d -o claudia -g claudia-run -m 2750 /var/lib/claudia

# Code: root-owned, read-only for everyone else.
install -d -m 0755 /srv/claudia
rsync -a --delete --chown=root:root --chmod=D755,F644 \
  --exclude .git --exclude node_modules --exclude plan "$src"/ /srv/claudia/
(cd /srv/claudia && HOME=/root /usr/local/bin/bun install --frozen-lockfile --production >/dev/null)

install -m 0644 /srv/claudia/deploy/claudia-page.service /etc/systemd/system/claudia-page.service
install -m 0644 /srv/claudia/deploy/claudia-runner@.service /etc/systemd/system/claudia-runner@.service
install -m 0644 /srv/claudia/deploy/claudia-update.service /etc/systemd/system/claudia-update.service
install -m 0644 /srv/claudia/deploy/claudia-update.timer /etc/systemd/system/claudia-update.timer
systemctl daemon-reload

# One Linux user per profile; home 0700 so profiles can't read each other.
while read -r name email _; do
  case "$name" in ''|\#*) continue ;; esac
  user="claudia-$name"
  if ! id "$user" >/dev/null 2>&1; then
    useradd --create-home --user-group --shell /bin/bash --comment "Claudia profile $name ($email)" "$user"
    chmod 0700 "/home/$user"
    install -d -o "$user" -g "$user" -m 0755 "/home/$user/Work"
    [ -x /etc/claudia/setup-profile ] && /etc/claudia/setup-profile "$name"
  fi
  usermod -aG claudia-run "$user"
  systemctl enable --quiet "claudia-runner@$name.service"
  if ! systemctl is-active --quiet "claudia-runner@$name.service"; then systemctl start "claudia-runner@$name.service"
  elif [ "$restart_runners" = "--restart-runners" ]; then systemctl restart "claudia-runner@$name.service"; fi
done < /etc/claudia/profiles

systemctl enable --quiet claudia-page.service
# From here on the server follows GitHub's main by itself (deploy/update.sh).
systemctl enable --quiet --now claudia-update.timer
systemctl restart claudia-page.service
echo "Claudia installed; runners: $(systemctl list-units --plain --no-legend 'claudia-runner@*' | awk '{print $1}' | tr '\n' ' ')"
