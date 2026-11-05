# Claudia on a Mac

A Mac (Apple Silicon, one user) runs the server side as a single-user machine, like a Linux one
with user units: the front, the runner and the host, kept on main's signed build by
`deploy/update.sh`. launchd agents take the place of the systemd units; a pf anchor takes the place
of the nft rule.

| Linux (deploy/)                          | macOS (deploy/macos/)                                         |
| ---------------------------------------- | ------------------------------------------------------------- |
| `claudia-{host,runner,front}.service`    | `~/Library/LaunchAgents/claudia.{host,runner,front}.plist`     |
| `claudia-update.path` + `.timer`         | `claudia.update.plist`: WatchPaths on the trigger, hourly      |
| `EnvironmentFile=~/.config/claudia/env`  | `run.sh` reads the same file at every start                    |
| nft: the port only through the tunnel    | `guard.sh`: pf anchor `com.apple/claudia`, the hub's address only |

The agents are written by `install.sh` from `~/.config/claudia/env`; `deploy/update.sh` runs it
from each new release (the front and the runner restart, the host never). They run in the user's
GUI session: from login on, while the user is logged in.

## The front's port

The front believes the hub's `X-Claudia-Email`, so only the hub may reach it. Two things hold that:

- `FRONT_HOST` is the Mac's WireGuard address: the front listens there only. While the tunnel is
  down the address does not exist, the front exits, and launchd starts it again every 5 s until it
  can listen.
- `guard.sh` (root) loads pf rules: `FRONT_PORT` in only from `FRONT_HUB_ADDRESS` (the hub's tunnel
  address) to `FRONT_HOST`, never over loopback; everything else to that port is dropped. macOS
  accepts a packet for any of its addresses on any interface, so binding alone would let a LAN host
  that routes the tunnel address to the Mac in; the rule drops it (its own address), a forged hub
  address never completes the handshake (the answer goes into the tunnel), and other WireGuard
  peers and programs on the Mac are dropped too. The rules name addresses, not the interface: the
  WireGuard app's `utunN` changes. A root daemon (`claudia.guard`) reloads them at boot and each
  minute when missing and turns pf on (macOS ships it off); `/etc/pf.conf` is not edited.

## Set up

1. Homebrew, then `brew install node git cosign gh`. Optional: `brew install --cask 1password-cli`
   (secrets), `brew install --cask google-chrome` (the picture screenshots Claude gets back; or
   `node ~/claudia/node_modules/playwright-core/cli.js install chromium` after step 5). No Claude
   Code CLI: the release carries the Agent SDK's own Claude Code for darwin-arm64.
2. The WireGuard app with the tunnel to the gate, On-Demand on (Wi-Fi and Ethernet), so it is up
   whenever the Mac is online. Note the Mac's tunnel address and the hub's.
3. A clone of this repository anywhere (only for the first install), and the settings, written by
   hand:
   ```
   git clone https://github.com/p4ulcristian/claudia /tmp/claudia-setup
   mkdir -p ~/.config/claudia
   cp /tmp/claudia-setup/deploy/macos/env.example ~/.config/claudia/env   # then this Mac's own values
   echo "<profile> <email> <RUNNER_SOCKET>" > ~/.config/claudia/profiles
   ```
4. The guard, before the front first listens: `sudo bash /tmp/claudia-setup/deploy/macos/guard.sh`.
5. The first install, into `CLAUDIA_CHECKOUT` (a release folder, never a git checkout):
   ```
   CLAUDIA_CHECKOUT=$HOME/claudia bash /tmp/claudia-setup/deploy/update.sh
   ```
   It installs main's signed build and runs `~/claudia/deploy/macos/install.sh --update`, which
   writes and loads the four agents.
6. On the hub, the server is added (or already is) on the Servers page as "already on WireGuard"
   with `<the Mac's tunnel address>:4540` (`FRONT_HOST:FRONT_PORT`), and people are ticked for it on
   the Access page. No `deploy/pair.sh` (Linux only: wg-quick, nft): the Mac's tunnel is the
   WireGuard app's. The hub writes the Claude token in use into `CLAUDIA_TOKEN_FILE` within a
   minute.
7. A Mac that serves stays awake on power: `sudo pmset -c sleep 0` (or System Settings > Battery >
   Options: prevent automatic sleeping on power adapter when the display is off). After a reboot
   the agents start when the user logs in.

If the macOS firewall (System Settings > Network > Firewall) is on, it asks once whether `node`
may accept incoming connections: allow it (pf still lets only the hub in).

## Check

```
launchctl print gui/$(id -u)/claudia.front | grep -E 'state|pid|last exit'
tail -f ~/Library/Logs/claudia/front.log          # also host, runner, update
sudo pfctl -s info | head -1                       # Status: Enabled
sudo pfctl -a com.apple/claudia -s rules
```

The hub's Systems popup shows the Mac. From another Linux machine on the LAN, with the tunnel
address routed to the Mac (`sudo ip route add <tunnel address>/32 via <the Mac's LAN address>`),
`nc -vz -w 3 <tunnel address> 4540` must time out; so must `nc -vz <tunnel address> 4540` on the Mac
itself.

## Before main has the macOS port (testing its pull request)

main's release has no darwin-arm64 node_modules until the port is merged, so step 5 takes the
pull request's own build instead (unsigned by stage.yml for the Mac part: the check run's
artifact; `gh auth login` first):

```
sha=<the PR's head commit>; run=<its check run id>
tree=$(gh api repos/p4ulcristian/claudia/git/commits/$sha --jq .tree.sha)
mkdir -p ~/claudia /tmp/claudia-deps
gh release download tree-$tree -R p4ulcristian/claudia -p "claudia-code-$tree.tar.gz" -O - | tar -xzf - -C ~/claudia
gh run download $run -R p4ulcristian/claudia -n bundle-darwin-arm64 -D /tmp/claudia-deps
tar -xzf /tmp/claudia-deps/claudia-deps-*-darwin-arm64.tar.gz -C ~/claudia
echo $sha > ~/claudia/commit.txt
~/claudia/deploy/macos/install.sh
```

After the merge the update agent moves it onto main's signed build by itself (main contains that
commit, so it is ahead).

## By hand

- `~/claudia/deploy/macos/install.sh`: write and load the agents again (after a plist change it
  names); `--restart-hosts` also restarts the host (stops running chats).
- `CLAUDIA_CHECKOUT=$HOME/claudia bash ~/claudia/deploy/update.sh`: update now.
- `sudo ~/claudia/deploy/macos/guard.sh` after `FRONT_HOST`, `FRONT_PORT` or `FRONT_HUB_ADDRESS`
  changed; `--remove` takes the guard away.
- Stop everything: `for p in front runner host update; do launchctl bootout gui/$(id -u)/claudia.$p; done`.
