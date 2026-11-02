# Plan: 1Password for Claudia, no secrets on the shared server

Planned 2026-11-02. Nothing built yet.

## Goal

Claude, working in a chat on any Claudia server (including the shared team server), can use a password
or token from 1Password without that secret ever being stored on that server. The person approves each
use in the chat.

Rules (the user's):

1. No password is stored on the shared server.
2. A request triggers 1Password; the person allows it in the chat.
2.1. If the secret is not in 1Password, the person types or pastes it in the chat. It is not stored on the server.
2.2. Claudia hands the secret to the place that needs it, once.

## Decisions

1. **Desktop-app unlock.** `op read` on the person's own machine, unlocked with the 1Password app
   (biometrics). The credential never leaves the 1Password app. Not a service-account token (no human
   approval, and the token itself would be a password on the server) and not Privileged Access /
   Credential Broker (business feature, fit unverified, heavy for a small team).
2. **The account is explicit, per profile.** Paul's chats use Paul's 1Password account, Peter's use
   Peter's. Every read passes `--account <that account>` (with several accounts signed in, `op`
   otherwise picks by its own default). Set in Claudia's Settings, per profile: the vault server (the
   machine whose 1Password app that person unlocks) and the account's sign-in address or ID. The Allow
   card always shows the account it will read from.
3. **A person's vault server is a machine of theirs in the swarm** (e.g. the desktop, later a laptop).
   Nobody's vault is unlocked from another person's machine. A profile without a vault server has only
   the paste path until their own machine joins as a server.
4. **Claude never sees the value.** A new workspace tool, `run_with_secret(ref, env_name, reason,
   command)`: the runner asks, gets the value, runs the command with it in that command's environment
   only (memory, never a file), and redacts the value from the output, the transcript and the diffs.
5. **End to end across the swarm.** The runner makes a one-time keypair per request and puts the public
   key in the card. The vault server encrypts the value to that key (a vetted library: libsodium sealed
   box, not hand-written). The hub and the browser only carry ciphertext. The request is single use and
   expires.
6. **The person decides in the chat.** An Allow card (item reference, why, which account, which
   server) like the existing question and permission panels. Allow triggers the unlock on the vault
   server. Deny answers Claude with a refusal.
7. **Phone.** The card can be allowed from the phone, but the biometric unlock needs a person at the
   vault server's machine, so it works at the desk only. Away from it: "paste it" in the card (copy the
   item in the 1Password phone app, paste it into the card). One-time, same encrypted path, not stored.
   No way found to ask the 1Password iOS app for a secret directly (not verified).
8. **Nothing is kept.** Not in `app.db`, not in session files, not in logs. Only the reference, the
   reason, who allowed it and when are logged.

## Request path

1. Claude calls `run_with_secret` in a chat on server S (runner).
2. The runner makes the keypair and shows the Allow card (or the paste field) in the page.
3. The person presses Allow. The page asks the person's vault server V through the hub
   (`/s/<V>/api/secret/read`, the hub says who is calling, the grid and the profile's setting decide
   whether V is theirs).
4. V runs `op read <ref> --account <their account>`; the desktop shows the biometric prompt.
5. V encrypts the value to the request's public key and returns it; the page forwards it to S's runner.
6. The runner decrypts, runs the one command with the value in its environment, redacts, answers Claude.

## Build, in order

1. Audit: list secrets that sit on the shared server today (tokens in `~/.config`, env files).
2. Settings: per-profile 1Password account and vault server (page + runner, saved beside the Claude tokens).
3. Vault endpoint on a server: install check for `op`, `/api/secret/read` (own profile only, pinned
   account, ref allowlist optional), encryption to the request's key.
4. Runner: `run_with_secret` tool, keypair, pending request, redaction, expiry.
5. Page: Allow card and paste field; works on the phone layout.
6. Tests with a fake `op` (no 1Password on the shared server); then one real test at the desktop.
7. Move the old server secrets into 1Password and delete them from the server (needs the person's go).

## Open

- Peter's and Ananda's vault servers do not exist yet (their own machines must join the swarm).
- `op` desktop approval lasts about 10 minutes per process and ends when the app locks: decide whether
  to say so on the card.
- The 1Password SDK desktop integration is beta; the `op` CLI is the stable path, so start there.

Sources: 1Password CLI app integration, environment variables, SDK desktop app integrations, SDKs with
AI agents (all on 1password.dev).
