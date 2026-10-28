# Plan: one Claudia UI, many servers

Goal: one Claudia to open from anywhere, that reaches every server a person may use (the owner's desktop,
the team server, a laptop later), with access set per person and per server. New servers join by
installing Claudia and pasting the key it prints. Planned 2026-10-28; nothing built yet.

## Decisions

1. The client is one UI that talks to every server's API: today's page becomes the client, each
   server keeps its runner(s). Servers report an API version; the UI marks a server that needs an update.
2. The UI is hosted on the VPS (the gate): static files served by its Caddy at one address. The gate
   also holds what it needs to reach the servers; browsers hold no server keys.
3. Several people sign in, with the gate's existing login.
4. Access is set per person × server, and a grant means the person works there as themselves: their
   own profile (own user, memory, GitHub token, chats). First grid: the owner → desktop, team server,
   laptop; the two teammates → team server only.
5. Servers reach the gate over WireGuard (the desktop, the team server and the laptop already are peers).
   The gate's firewall lets only gate → server:4510 per peer. WireGuard proves a request comes from
   the gate, so a server believes who the gate says is calling.
6. A new server (not yet on WireGuard) is added from the UI: installing prints a key (its WireGuard
   public key + a one-time secret), pasted into the gate's Servers page; the gate adds the peer through
   a small privileged helper (admins only) and keeps it in its own state; the server fetches its
   address with the secret. A new peer gets only gate → server:4510.
7. One chat list across all servers a person can reach: each chat wears its server's mark, a filter
   narrows to one server, a new chat asks which server (the last used by default), and the chats of a
   server that is off are greyed out.
8. The gate UI is the only UI, at the address the team server had. The desktop's own address and
   its local window go away (the desktop window opens the gate address).
9. Reading follows the grid: a grant on a server also shows the other profiles' chats there, read
   only. No grant, nothing from that server.
10. A grant on a server where the person has no profile waits ("needs a profile on <server>") until
    someone with root there runs one command to create it. The gate never creates users on servers.

## Build, in order

1. Server side: the page/proxy (server.ts) takes the caller from the gate over WireGuard (person +
   profile), reports its API version, and serves only the API (no page).
2. The gate's Claudia hub (a container on the gate): serves the UI, keeps the servers list and the
   access grid, and passes each request on to the right server with who is calling.
3. The UI as a client of many servers: server marks, one merged list, new-chat server choice, offline
   servers greyed, and the Servers and Access pages (admins only).
4. Pairing: `claudia pair` on a server, the gate's WireGuard helper, and the address pick-up.
5. Move over: the team server's address to the hub, remove the desktop's own address and its
   local window, then the laptop as a server.
