# host/: Claudia's host standard

One Ansible playbook that makes every Linux machine running Claudia the same (README decisions 51
to 55):

- **Agent users.** Each profile's chats run as `<profile>-claudia-agent`: a plain user with its own
  group and home, no sudo, no password, no SSH keys; member of `claudia-run` (it reads the saved Claude
  tokens and its person's pick).
- **break-glass.** A user with no password, one SSH key (kept in 1Password) and full sudo: the way
  in when Claudia is broken. Used by hand only, never by an agent.
- **Root helper.** `claudia-root.service` runs `/usr/local/lib/claudia-root/root.js` as root and
  listens on `/run/claudia-root/<agent user>/root.sock` (a folder only that agent user's group may
  enter). Its no-tap list is `/etc/claudia/root-no-tap`; the `sudo` shim comes first on the agents'
  PATH (`/usr/local/lib/claudia-agent-bin/sudo` → `/usr/local/lib/claudia-root/sudo-shim`), which runs
  the `sudo.js` beside it. root.js, sudo.js and the shim are copied from a built checkout on your computer (`claudia_build_dir`), never taken
  from `/srv/claudia`: the automatic update replaces `/srv/claudia`, so it cannot change the guard.
  The helper restarts when the copy changes.
- **SSH.** sshd trusts the hub's certificate authority (`/etc/ssh/claudia-ca.pub`); each agent user
  has a principals file naming only itself (`/etc/ssh/claudia-principals/<user>`); passwords and
  keyboard-interactive logins are off. ListenAddress is never touched.
- **Desktop.** On a machine with a `desktop_user`, the agent users may use that person's Wayland,
  PipeWire and PulseAudio sockets, and the profiles' chats are pointed at them (role
  `desktop_access`). Chrome's DevTools port is TCP on
  localhost and needs nothing.
- **etckeeper.** /etc in git, so every run can be undone.
- **Migration.** Once per profile, its chats, Claude Code's sessions and the runner's data are
  copied from the person's home to the agent user's, the paths inside the copy are rewritten, and the
  profile switches to the agent user.

It is applied by a person from their own computer. `deploy/update.sh` (Claudia's automatic update)
never runs it: a merged mistake in Claudia cannot loosen a server's guard.

## Running it

The inventory (servers, people, addresses, keys) stays outside this public repo. Copy
`host/inventory.example.yml` to `~/.config/claudia-host/inventory.yml` and fill it in. Then, from
the repo's root:

```sh
# a checkout with the guard built: npm ci && npx shadow-cljs release root sudo
ansible-playbook -i ~/.config/claudia-host/inventory.yml host/site.yml
# open a NEW ssh login to each server (and one as break-glass); check Claudia works; then:
ansible-playbook -i ~/.config/claudia-host/inventory.yml host/confirm.yml
```

Needs ansible-core on the computer that runs it (checked with 2.22); nothing on the servers but
Python and sudo. Only builtin modules are used. Add `--limit <host>` to do one server; the playbook does one
server at a time anyway (`serial: 1`).

Per host in the inventory: `people` (a list of `{profile, person_user}`: the profile's name, the first
word of its line in `/etc/claudia/profiles`, and the person's own Linux login; leave `person_user`
out when the person has no login of their own on that server, as then the profile cannot be migrated), and when wanted
`migrate_profiles`, `desktop_user`, `break_glass_tested`, `phase_b_remove_nopasswd`. For all hosts:
`break_glass_pubkey` and `claudia_ssh_ca_pubkey` (the run stops when either is missing or still the
example's placeholder), and `claudia_build_dir`, the absolute path of a Claudia checkout on your
computer with `out/root.js` and `out/sudo.js` built (the run stops when either is missing, or when
one needs an npm package: the guard's copy has no `node_modules` beside it). Everything else is in
`group_vars/all.yml`. Host settings in the inventory win over `group_vars/all.yml`; group settings
in the inventory file do not, so put per-server choices on the host.

## Undo and confirm

1. Every run first commits /etc as it is (etckeeper), then arms an undo: a systemd timer,
   `claudia-host-undo.timer`, that after `undo_after` (30 minutes on a server where this run moves
   profiles, 10 otherwise) runs
   `/usr/local/sbin/claudia-host-undo <that commit>`.
2. The run makes its changes. sshd is reloaded only after `sshd -t` passes and `sshd -T` shows the
   settings in effect.
3. You open a new SSH login, check Claudia, then run `host/confirm.yml`. It logs in over a fresh
   connection (that login is itself the test), stops the timer and commits /etc as confirmed.
4. Without a confirm the undo runs: it commits whatever /etc holds, deletes the files added since the
   commit, checks out every other file as it was, restores owners and modes (`etckeeper init`),
   reloads systemd and sshd, and restarts the profiles' hosts and runners when the profiles file
   changed. The history keeps both states (`git -C /etc log`).

The undo covers /etc only: users, groups, sudoers, sshd, units and drop-ins, the profiles file. Files
outside /etc stay (agent users' homes and the copied data, `/usr/local/...`, `/run/claudia-root`);
none of them gives access by itself.

A run refuses to start while an earlier run on that server waits for its confirm. When a run takes
longer than `undo_after` (a large Work folder to copy), the undo fires during it; the run notices and
stops. Raise `undo_after` (`-e undo_after=60min`) for such a server.

## Phase A and Phase B

**Phase A** (the default): everything above. The people keep their own users and their sudo as it
is.

**Phase B** takes the people's passwordless sudo away: their rules in `/etc/sudoers.d` lose
`NOPASSWD:` (each file checked by visudo first), then sudo is asked what each person may still run
without a password, and the run stops if anything is left (a group rule such as `%wheel`, or a line
in `/etc/sudoers`: fix those by hand). Per server, only after a week on Phase A and the user's go:

1. Test break-glass there: `ssh break-glass@<server> sudo -n true`.
2. Make sure each person has a password (`passwd`), or they lose sudo altogether.
3. On that host in the inventory: `break_glass_tested: true` and `phase_b_remove_nopasswd: true`.
4. Run it as break-glass (`-u break-glass`) or with your sudo password (`-K`); the run refuses to
   lock itself out.

## Moving a profile to its agent user

List the profile in `migrate_profiles` for that host. For each, once: its host and runner stop (its
running chats stop), these are copied with `rsync -a` into the agent user's home and given to the
agent user (the person's copies stay):

| from the person's home | to the agent user's home |
| --- | --- |
| `work_dir` (default `~/Work`) | `~/Work` |
| `~/.claude/` | `~/.claude/` |
| `~/.claude.json` | `~/.claude.json` |
| `runner_data` (default `~/.local/share/claudia`) | `~/.local/share/claudia` |

then the paths inside the copy are rewritten (below), the profile's line in `/etc/claudia/profiles` gets `user=<profile>-claudia-agent` (its other
words stay), the units' drop-ins are written as `deploy/shared-server/install.sh` writes them, and the
host and runner start as the agent user. The person may read the agent user's Work folder (ACLs, also
on folders made later). A profile already on its agent user is skipped, so a second run copies
nothing.

### The paths inside the copy

The copy still names the person's home. `roles/migrate/files/claudia-migrate-paths` (Python, standard
library only) rewrites it in the agent user's copy: a path that is `/home/<person>` or lies inside it
becomes the same path under `/home/<agent user>` (and `work_dir`, when set, becomes `~/Work` first).
Exact prefixes only: `/home/<person>-x/…` or a path in the middle of a text is left. It rewrites:

- the runner's store `~/.local/share/claudia/claudia.db`: `chats.folder`, `changes.path`, and every
  string in the JSON of `kv.value` (such as a chat's moved folder);
- Claude Code's session folders `~/.claude/projects/<encoded cwd>`: renamed to the new cwd's name
  (every character other than a-z, A-Z, 0-9 becomes `-`, as Claude Code names them). The old cwd is
  read from the folder's session files; without one, the folder is renamed only when its name
  begins with the encoded person's home. A new name longer than 200 characters is left (Claude Code
  shortens those with a hash);
- the keys of `projects` in `~/.claude.json`.

It leaves: the `cwd` fields inside the session files (`.jsonl`), the pictures (scenes), Claude
Code's prompt history, and symlinks. To see what it would do on a server:
`python3 claudia-migrate-paths --from /home/<person> --to /home/<agent user> --dry-run`.

## From single-user to shared (by hand, before migrating)

A machine that runs Claudia single-user (user units `claudia-front`, `claudia-runner`,
`claudia-host` under the person, settings in `~/.config/claudia/env`, data in that person's
`~/.local/share/claudia`) is switched to shared mode by hand first. Secrets are copied by you, never
by the playbook.

1. Build or fetch a release as for any shared server (see `deploy/shared-server/install.sh`), and
   make the front's system user if the machine has none: `useradd --system claudia`.
2. Write `/etc/claudia/env` from `~/.config/claudia/env`: keep the front's settings (`FRONT_HOST`,
   `FRONT_PORT`, `FRONT_NAME`, `FRONT_HUB_KEY_FILE`), `CLAUDIA_TOKEN_FILE` and the 1Password
   settings; leave out what the shared units set per profile (`RUNNER_SOCKET`, `HOST_SOCKET`,
   `RUNNER_DATA`, `RUNNER_PROFILE`, `CLAUDIA_CWD`, `CLAUDIA_CHECKOUT`, `CLAUDIA_UPDATE_TRIGGER`).
   Move the files it points to (hub key, Claude token) out of the person's home into a place the
   front (user `claudia`) and group `claudia-run` can read, as on the other shared servers.
3. Write `/etc/claudia/profiles` with one line per profile:
   `<profile> <email> /run/claudia-<profile>/runner.sock user=<person>`.
4. Stop and disable the user units, as the person:
   `systemctl --user disable --now claudia-front.service claudia-runner.service claudia-host.service claudia-update.path claudia-update.timer`.
5. Run `sudo deploy/shared-server/install.sh <release> <profile>`. The profile now runs as the
   person, in shared mode, on the same data.
6. If the old `RUNNER_DATA` or `CLAUDIA_CWD` were not `~/.local/share/claudia` and `~/Work`, set
   `runner_data` and `work_dir` on that person in the inventory.
7. Now list the profile in `migrate_profiles` and run the playbook.

The migrate role refuses while the profile's line does not say `user=<person>` or while the person's
single-user units still run.

## The desktop

With `desktop_user` set, `/usr/local/bin/claudia-desktop-access` gives every agent user `x` on
`/run/user/<uid>` and `rw` on the Wayland sockets, `pipewire-0` and `pulse/native` (not
`pipewire-0-manager`). `/run/user/<uid>` is made again at every login, so a user unit in
`/etc/systemd/user` (only for `desktop_user`), enabled in `default.target`, runs it when the
session's Wayland socket appears; the playbook also runs it once.

Each profile's session host gets a drop-in (`claudia-host@<profile>.service.d/desktop.conf`) that
points its chats at the session by full paths: `WAYLAND_DISPLAY=/run/user/<uid>/wayland-1` (the
socket's name as found when the playbook runs; wayland-1 when no session is up),
`PIPEWIRE_REMOTE=/run/user/<uid>/pipewire-0`, `PULSE_SERVER=unix:/run/user/<uid>/pulse/native`.
`XDG_RUNTIME_DIR` stays the agent's own. It takes effect when that host restarts next. A compositor restarted within one session makes a new socket the unit does not see
until the next login.

## Files

- `site.yml`: the run, in order: checks → etckeeper → arm the undo → users → root helper → ssh →
  desktop access → migrate → Phase B.
- `confirm.yml`: the confirm.
- `group_vars/all.yml`: settings shared by all servers, including the no-tap list.
- `inventory.example.yml`: an example inventory, placeholders only.
- `tasks/preflight.yml`: checks before any change.
- `roles/`: `etckeeper`, `safe_apply` (arm, confirm, undo program), `users`, `root_helper`, `ssh`,
  `desktop_access`, `migrate`, `sudo` (Phase B).
