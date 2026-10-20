# Plan: a second Claudia on a shared server, profiles follow the sign-in

Goal: besides the owner's desktop Claudia, one on a shared team server, where the signed-in email
picks the profile (whose chats, whose files, whose GitHub login). Decided 2026-10-20.

## Decisions

1. On the shared server, Claude works on that server's files only (the team's repos, worktrees).
   It keeps working when the owner's desktop machine is off.
2. Not a move: both run, from one repo with the same code. An instance differs only by its settings
   (profiles list, token file, name, listeners); the desktop window is the owner's profile.
3. Each profile's Claude runs as its own Linux user (`claudia-<profile>`): own home, ~/.claude,
   memory, git and SSH keys. Its runner is a systemd unit as that user, behind a unix socket only
   the page can reach (no root helper).
4. The team's login group signs in, and every profile can do real work on the team's main repo:
   its own clone and worktrees, the toolchain, tests (incl. a throwaway Postgres), push and PRs.
5. Each profile pushes and opens PRs as its person's own GitHub account (token pasted once in Settings).
6. One Claude subscription per instance: every profile runs on it and anyone signed in can replace
   the token in Settings (write-only there). This also replaces the desktop widget's account switch.
7. Per-profile preferences, first: chat colours (a colour per new chat, or the default look).
8. The instance's name shows as a subtitle under the logo (`CLAUDIA_NAME`).

## Defaults (not asked)

- Chats belong to their profile: nobody sees another profile's chats.
- Profiles are listed by the admin (`/etc/claudia/profiles`); a sign-in without one gets a "no profile" page.

## Not yet

- Previews of a profile's worktrees: the team server's preview runner holds keys no profile should
  get, so a profile's preview has to run as that profile; needs changes in the preview service.
