# Claudia

Chat with Claude on the left; Claude explains on the right, in a picture it draws as it works. Chat
stays short, like two friends talking; the explaining lives in the picture.

This branch is the clean-slate rewrite in ClojureScript on Node. It runs beside the TypeScript
Claudia (branch `main`) until it replaces it.

## Shape

- **hub** (the master, one, on the gate): the page, who may use which server, the pass-through.
- **front** (every server): takes the hub's calls, picks the profile. No page.
- **runner** (per profile): chats, pictures, questions, 1Password secrets, SQLite.
- **host** (per profile): the Agent SDK sessions; a runner restart keeps chats running.

The wire between them is in [PROTOCOL.md](PROTOCOL.md).

## Decisions (2026-11-02)

1. ClojureScript on Node, compiled ahead by shadow-cljs: RAM matters (the gate has under 1 GB), and the
   Agent SDK is JavaScript-only, so the slave calls it directly. Not JVM Clojure.
2. No Groundwork: Claudia has no shared database, permissions or sessions to solve; plain JSON.
3. Page: Reagent + re-frame.
4. Storage: SQLite through `node:sqlite`, one file per profile next to its Claude Code sessions
   (the chats themselves are Claude Code's own `.jsonl` files). Not Postgres.
5. A clean slate, not a mirror: v1 has chats, questions, the picture (graph, freeform, the forms
   pipeline/timeline/compare/chart/stats) and 1Password secrets; the rest comes back one at a time.
6. Runs beside the old Claudia at its own address until the switch.
7. Secrets from 1Password, never stored on a server: [plan/1passwd-plan.md](plan/1passwd-plan.md).

8. Back from the TypeScript Claudia (2026-11-03): agent chips (right above the composer since 2026-11-14) (subagents and
   background shells, their view with Steps / Files / Prompt / Report, Stop, progress bars from
   `##progress` lines, the dashed amber ring while work runs after the turn), the turn's file changes
   (a pill with +/− and diffs), the plan's 5-hour and week usage and the chat's context use (a click
   compacts), the model and effort per chat, and the `/` command menu. Edits inside Eye on Rainbow's
   worktrees are reported to it where `CLAUDIA_WORKTREES_DIR` and `CLAUDIA_EOR_URL` are set.

9. The code is small pure functions (2026-11-03, after a local `keep` shadowing `clojure.core/keep`
   crashed the hub on every start): data in, data out, at most 30 lines each, effects at the edges,
   one shared copy of every helper, no shadowed names. A PR check (lint, a ratchet on shadowed names
   and long functions that only goes down, tests, the five builds) holds it. Rules in CLAUDE.md.
   Instead of: long handlers mixing reading, deciding and writing state, and a copy of each helper
   per file.
10. Claude tokens are kept by the hub (2026-11-03): saved once in Settings, sealed in `hub.db` under a
    key that lives only in the gate's secrets (`HUB_TOKEN_KEY_FILE`), one in use for every server.
    The hub writes the one in use into each server's token file (the only copy a server keeps, since
    Claude runs there) and brings a server that was off or is new along within a minute. The rail's
    foot shows it (initials; a dashed ring while a server is not on it yet) and switches it; the
    Forms button left the rail (Forms stays in Settings). Instead of: each server keeping and
    switching its own saved tokens.

11. The page runs only its own code (2026-11-03): every library it uses comes from npm at the
    version in `package-lock.json` and is built into `out/web` (Shiki, Vega, three.js and QuickJS
    in modules loaded on first use, Shiki's grammars as its own files); the hub sends a
    Content-Security-Policy that allows the page's own scripts and WebAssembly only, and no framing
    (`claudia.shared.csp`). Claude's html pages run in `frame.html`, a sandboxed document with its
    own policy (inline scripts and cdn.jsdelivr.net, no Claudia API): a srcdoc frame would take on the
    page's policy. A `shadow-cljs watch` build loads under the policy, but its live reload (a socket
    to the shadow-cljs server) is refused: reload by hand. Instead of: importing Shiki and Vega from
    jsdelivr by major version at run time, where a bad release would run as the signed-in person.

12. Systems popup (2026-11-05): Machines in the settings menu (decision 24; before 2026-11-14 a computer on the rail) opens a card per server
    the person may use (the access grid) and, for admins, one for the gate. Each card asks every 2 s
    while open (`GET /hub/servers/<name>/system`, `GET /hub/gate/system`) and shows how old its
    numbers are; older than 8 s greys it, a server that does not answer says why. Every server's
    front reads its machine straight from the kernel (`claudia.node.proc`, `claudia.node.system`):
    each core's use as the difference of two `/proc/stat` reads 2 s apart (the first after a quiet
    spell: 1 s), split into user, system, steal and waiting on disk (not counted as busy); memory
    used = MemTotal − MemAvailable; real filesystems from `/proc/self/mountinfo`, one per device,
    space by `statfs` (used of used + available, as `df` counts it); disk and network speed from
    `/proc/diskstats` and `/proc/net/dev` over the same window (physical disks and network cards
    only); CPU and drive temperatures from hwmon; NVIDIA cards by `nvidia-smi`; Claudia's own
    processes (front, runners, hosts, and as chats everything the hosts started) by resident memory.
    It reads only while someone asked in the last 30 s. On macOS (no `/proc`) the same sample comes
    from `os.cpus()` per core (no iowait or steal there), `vm_stat` (used = app memory + wired +
    compressed, as Activity Monitor counts it), `sysctl vm.swapusage`, `netstat -ibn` (en* cards),
    `ioreg` (bytes read and written), `mount` with `statfs` (one row per APFS container, its volumes' files summed) and `ps`
    (`claudia.node.darwin`); no temperatures. Instead of: the `systeminformation`
    library, which runs `df`, `lsblk` and `nvidia-smi` per call and measures CPU since the
    previous call from anywhere in the process; an admin-only view.

13. A Mac is a server too (2026-11-05; [deploy/macos/README.md](deploy/macos/README.md)): an Apple
    Silicon Mac with one user runs the front, runner and host as launchd agents written from the same
    `~/.config/claudia/env` (`deploy/macos/install.sh`), follows main with the same
    `deploy/update.sh` (bash 3.2 and BSD tools as well as Linux's), and takes the release's
    darwin-arm64 node_modules (`claudia-deps-<lockfile hash>-darwin-arm64`, packed by check.yml,
    signed by stage.yml: the Agent SDK's Claude Code is per platform). The front listens on the Mac's
    private-network address and answers only calls carrying its hub key (decision 47); a pf anchor
    (`deploy/macos/guard.sh`, root) can further limit its port to the hub's address. Instead of: a
    Linux VM on the Mac, or building node_modules on the Mac itself.
14. Claude chooses every block (2026-11-09): the per-chat form row is gone: the right rail on
    desktops, the row over the picture on phones, the column picker, required and off kinds,
    questions off, and the auto files/git/clojure blocks. The draw tool offers every form not deleted
    in the Forms window, and Claude picks the kinds and the layout. `chat_settings` moved its one
    kept value (the forms a chat's Claude last heard of) into `kv` as `formsSeen` and was dropped.
    Why: 8 of 177 chats on one server ever set the row in three weeks; the forms themselves are
    to be cut down next. Instead of: keeping the row, or keeping it on phones only.

15. The world (2026-11-11): a whole-window 3D mode (the rail's globe or Alt+W; Escape or "Chats" goes
    back). The gate is an island with a lighthouse in the middle, every server the person may use an
    island on a ring round it joined by a bridge, every project a building on its server's island,
    and every chat (not archived, not ash) a hovering robot in its chat colour at its project's door
    (no project: the island's square), its name on a sign over it (lit while one of its chats works),
    and the project's name also on each of its chats' screens. The pieces (islands, buildings, the lighthouse, trees, rocks,
    the robot) are made in Blender by `tools/world/make-models.py` (deterministic; edit the script,
    never the files) into `resources/web/world/models/*.glb` (about 270 KB), with ambient occlusion
    baked into their vertex colours, and drawn in Claudia's chalk look: cel shading in three bands, a
    faint chalk grain fixed to the world and a chalk outline (back faces pushed out a fixed number of
    screen pixels; thin surfaces such as grass and windows left out of it). Over each robot floats its
    chat's screen (title in the chat's colour, the open question or the start of the last reply,
    what it does); screens face the camera, are drawn up to 1.4 times bigger far away, and move
    apart every frame so none covers another (`claudia.web.world.stack`). A drag slides the world
    and it glides on after letting go, the wheel zooms toward the pointer; the view never turns. A
    click on a screen (or its robot) opens the real chat in a panel beside the world, and the view
    glides so the robot stays in the part the panel leaves free; the robot gets a glowing ring and
    its screen is drawn bigger with a glowing frame (no flight from 3D to 2D: the world stays as it
    is). A click on an island's board opens that server's Systems card (the popup's card, big) the
    same way, the board framed. Escape, × or a click anywhere else in the world closes it; with no
    panel open, a click on a server's island starts a new chat there. While the world is open
    Claudia's own chat view is not drawn at all (the world shows the chat). Every island has a board on its
    cliff with the server's CPU, memory and GPU (the Systems popup's calls, every 2 s while the
    world is open; the gate's for admins) and one word for it (`claudia.web.world.health`): free
    (lush grass), busy (drying), overworked (scorched, smoking: CPU 85%, memory 90% or GPU 90% and
    over) or offline (the whole island grey, no traffic on its bridge). Layout, motion, screen
    text, stacking and health are pure data (`claudia.web.world.plan`, `.motion`, `.screen-text`,
    `.stack`, `.health`, `.state`, `.look`). The engine is
    three.js's WebGPURenderer (WebGL 2 where WebGPU is missing or its device is lost) with node
    materials in TSL: the still world is one merged mesh whose vertices carry their island and
    building (grass colour, greyness and lit windows come from uniform arrays, no material per
    island), its outline and the bridges; robots, screens, boards, beams, clouds, smoke and packets
    are InstancedMeshes; screens, boards and signs are slots of canvas atlases redrawn only when their text
    changes (`claudia.web.world.atlas` for boards and signs), at 1.5 canvas pixels per drawing unit. It
    draws at the screen's own sharpness (at most 2 pixels per CSS pixel). Its own module (`out/web/js/world.js`) on top of the 3D module,
    which holds three.js's core, so WebGPU comes from the same sources (`three/src/Three.WebGPU.js`),
    not a second copy. Instead of: real chat pages placed in 3D (CSS3D: always drawn over the
    world, no fog, heavy with many chats), the scene3d form (a picture Claude draws per chat, not a
    live view of every chat), shapes built in code, or a texture array per screen (its layers mixed
    on the WebGL 2 path).

16. Each chat has its own Claude subscription (2026-11-11): the chat's header shows whose plan it
    runs on (row 2, decision 22) and a click on it picks another saved one; the pick is
    kept with the chat and applies on its next message (its Claude Code starts again on that
    token). For that the hub sends every saved token to each server (`<token file>.d/<key>`, names
    in `<token file>.saved.json`), not only the one in use. The one in use (decision 10) is now the
    default: a new chat starts on it unless its header picked another, and stays on it; a chat without a pick (made before this,
    or its pick was removed) runs on it. The default is set in Settings; the rail's button that
    switched every chat at once is gone. Instead of: one token for every chat of every person,
    where one person's switch moved everyone's chats.

17. The chat has a hard length limit (2026-11-12): a turn's final reply may have at most 400
    characters (`claudia.slave.tools/reply-limit`). The runner checks it in the Stop hook: a longer
    reply is handed back to Claude (at most twice per turn; after that it stays) with the request to
    put what needs more room into the picture, and the page folds the handed-back reply into the
    steps. Prose has a form of its own on the right, `notes` (Markdown). Once a turn has ended, the
    page folds every text Claude wrote before the turn's last one (status lines between tool calls)
    into the step group, where one click shows them, so each turn leaves one short reply in the chat
    (`claudia.web.views.pure.steps/folded-ids`).

18. No chat is driven from outside the page (2026-11-12): the delegate API (`POST /api/delegate`,
    `POST /api/chats/:id/send`, `POST /api/chats/:id/answer`, `GET /api/chats/:id/state`) is gone.
    Chats are started, written to and answered by a person in the page (the socket's `new`, `send`
    and `answer`). Instead of: other programs and other chats starting chats in a person's list.

19. An open chat keeps its Claude Code running (2026-11-13), so another chat can always message it
    (Claude Code's SendMessage between sessions on the machine): a chat that is not archived and
    not passive (`claudia.slave.chats.view/keeps-session?`) is never closed for being quiet, and when
    the host (re)connects every open chat without a session gets it resumed, without a message.
    Archived and passive chats still let theirs go after 15 quiet minutes. Instead of: every chat
    closing after 15 minutes, which made a quiet chat unreachable until a person wrote to it. Costs
    one Claude Code process (about 250 to 450 MB) per open chat. Decision 18 still holds: no chat or
    program starts a chat or writes into one through the page.

20. Push to talk in the composer (2026-11-13): a 56 px round mic at the composer's left end, under
    the left thumb of a tablet held in landscape (the chat pane hugs the left edge; Send sits
    mid-screen on a 13" iPad). Hold it and talk: 48 bars round it follow the voice; let go and the
    clip goes to the hub (`POST /hub/listen`), which
    passes it to a speech-to-text service with OpenAI's transcription shape (`HUB_LISTEN_URL`,
    token in `HUB_LISTEN_TOKEN_FILE`). Letting go sends (2026-11-14): the draft with the heard
    text goes at once, whether the finger let go in place or dragged left; dragged right (40 px
    or more), the text only joins the draft, to be read and changed before Send. While the mic is
    held, "← send" and "add to message →" float over the messages just above the composer
    (nothing moves), the one letting go would do lit. Heard text that is empty sends nothing.
    Send is one button at the composer's right end (Stop there while Claude works and nothing is
    typed); the Send that sat above the mic is gone.
    A tap only says to hold it. The mic shows only where the browser can record and
    the hub has a listen service. Instead of: a mic beside Send (out of a thumb's reach on a
    tablet), Send turning into a mic (no talking once the box has text), a corner button over the
    picture, or Apple's on-device speech (app only, weaker on names). Files:
    `src/claudia/hub/listen.cljs`, `src/claudia/web/mic.cljs`, `src/claudia/web/views/mic.cljs`.
    The microphone stays warm (2026-11-16, after the first words of every message went missing:
    the mic opened only at the press while the button already showed red): opened at the first
    press, it keeps running for 10 minutes after the last one, and an AudioWorklet
    (`resources/web/mic-tap.js`) keeps the last second, so a clip starts half a second before the
    press. The button is amber while the microphone wakes and red once it records. It closes when
    the page is hidden; while warm the browser shows the microphone in use. The clip is 16 kHz
    mono WAV (`src/claudia/web/pcm.cljs`: resampled with a windowed-sinc low-pass), up to 24 MB
    (about 12 minutes) at the hub. Instead of: MediaRecorder from the press (WebM/MP4: smaller, but
    it cannot start before the press), or the microphone open whenever the composer shows.
    The flower (2026-11-16, after the 56 px button vanished under the finger and two voice
    messages went out swapped): held, the mic opens a 240 px flower round itself, always centred on
    the mic (320 px until 2026-11-17: on the iPad's edge it ran off the screen): a 72 px disc,
    amber while waking and red while recording, the time spoken in it; round the disc the 48 bars
    that follow the voice (back 2026-11-16: the bars, not a breathing disc, show the voice). Four
    petals open outside the bars: left sends, up throws the clip away, right adds the words to the message,
    down sends now (the running turn stops first, 2026-11-17); letting go in place sends too. Each clip let go waits in a list over
    the composer with its own hearing clock, and the words go out in the order they were spoken
    (a 15 s clip takes longer to hear than a 4 s one after it). The mic can be held again meanwhile.
    Files: `src/claudia/web/views/mic_flower.cljs`.
    No echo cancellation (2026-11-16): the microphone opens without echo cancellation and noise
    suppression. With them, iOS put the page into call audio for as long as the microphone was warm,
    and call audio's own volume stayed low with the iPad at full volume. The read-aloud voice stops
    when the mic is held, so there is no echo to remove.
    Never lost (2026-11-16, after the listen service failed on a half-minute clip and it had to be
    said again): every clip is kept on the device (IndexedDB, `src/claudia/web/clip_store.cljs`)
    until its words went out; a failure is heard once more by itself after 2.5 s, then the clip stays
    in the list with Try again and Throw away and holds the clips after it, so the order holds; after
    a reload kept clips come back that way. Words go to the composer the clip was spoken into
    (`:mic/heard` with its key). Files: `src/claudia/web/views/mic_clips.cljs`.
    My words (2026-11-17, decision `2026-11-17-listen-vocabulary-and-cleanup` in the Iris repo):
    the listen service hears with each person's own names and terms and cleans the text up. The hub
    sends the signed-in caller's email (lower case) as `speaker` with every clip and gets
    `{text, raw}` back: text cleaned up, raw as heard. The draft gets the clean text; while the
    raw words differ and the draft still holds them, a chip over the composer shows the other
    version and a tap swaps them (and back). Settings has My words: the list, Add, and × to remove,
    saved through `/hub/listen/words` (the caller's own list only; hidden without a listen
    service). When the person changes exactly one dictated word before sending, to one that looks
    like a name or a term (a capital not at a sentence's start, a capital inside, a digit, or
    - _ . inside), the composer offers "Add <word> to My words?" once: each word is offered once
    on a device (`claudia.wordsOffered`), never when the list holds it, Add or No. A one-word clip
    counts only when it was sent alone. The clip flow above is the same. Files:
    `src/claudia/hub/listen_words.cljs`, `src/claudia/web/words.cljs`,
    `src/claudia/web/views/my_words.cljs`, `src/claudia/web/views/pure/dictated.cljs`.

21. Talking mode (2026-11-13): a person speaking (the icon) in the chat's header (row 1, decision 22) turns it on and off for that
    chat (since 2026-11-14 kept with the chat, the Row's `talk`, the same on every device; before: one
    switch per device for whichever chat was open). Beside it the chat's bell (the Row's `bell`): on,
    the chat chimes, pops up and pushes to the phones when it finishes or waits; off, it does none of
    them (the phone's badge still counts it). A new chat starts with the speaker off and the bell on
    (its header's picks go with `new`); a chat only looked at (another profile's) shows neither. It only ever speaks what arrives while the person
    watches: the final reply of a turn that ended by itself (not the working messages, not a
    stopped turn) and Claude's questions, in the chat on screen, with the page in front; read by
    the hub's voice (`/hub/narrate`, the 3D narrator's), without code, tables, links or Markdown
    marks ("There's code in the chat." instead). It never listens: answers go through the mic.
    Silence at once, with nothing said late, when it is turned off, the open chat changes, the mic
    is held or the page goes to the background; a new reply cuts off the one before. Turning it
    on reads nothing back, and a chat with replies the person missed stays silent: while it is on,
    each reply has a play button to hear it. Instead of: reading the last unheard reply on
    arriving at a chat (every switch could start talking), or a hands-free conversation (the mic
    opening by itself). Files: `src/claudia/web/speech.cljs`, `src/claudia/web/views/talk.cljs`,
    `src/claudia/web/views/pure/talk.cljs`; `src/claudia/web/voice.cljs` is the one fetch of the
    hub's voice (the 3D narrator uses it too).

22. The chat's header holds every setting (2026-11-14): one panel in the chat's colour, three
    rows, the same for a new chat and a started one. Row 1: the title (a click renames it), the
    server's state, the chat's bell (a chime, popup and push when it finishes or waits), talking mode (a
    person speaking, decision 21) and Archive. Row 2: the computer it runs on, whose Claude
    subscription (decision 16), the project; each an icon (a computer, Claude's spark, a folder)
    before a pill button. Row 3: model and effort, then the context, 5-hour and week rings. Every
    pick is a pill button; a fixed value (a started chat's computer) has a dashed edge. A new chat
    picks all of them before its first message, and they go with the `new` frame, so the first
    turn already runs on them (before, model and effort followed `created`, after the first turn
    had begun). A new chat also has its colour from the moment it opens (the most different from
    what the server's other chats wear, decision 26), the header wears it, the
    project popup's palette changes it, and it goes with `new` (decision 25). The project popup starts with where the chat works: the
    folder's full path and its computer (name, system, kernel and architecture, processor and
    cores, memory, uptime; `host` in the system sample). Once started, the computer is fixed and
    every other pick changes the chat. The composer keeps only writing and sending, one row
    centred: the mic, Attach, the message, Send; the chips of background work sit right above it.
    Instead of: the computer above the composer and model and effort in its toolbar (settings
    spread over two places, account and project not settable before the chat existed), a header
    that folds into a pill once the chat starts (two layouts to learn), or the sounds switch by
    Claudia's face (two sound switches in two places). Files: `src/claudia/web/views/head.cljs`,
    `src/claudia/web/views/chat.cljs`.

23. GitHub is one login per person, kept by the hub (2026-11-14): the hub keeps each person's
    GitHub token sealed in its store (under `HUB_TOKEN_KEY_FILE`, as the Claude tokens) and logs
    gh in on their profile on every server they may use (`PUT /api/github` through the front, as
    them): at once after a save, and every minute for a server that missed it (off, new, newly
    allowed). A person with none kept yet keeps the login gh already holds on one of their
    servers (`GET /api/github/token`, read once), so a login made before is not asked for again.
    Settings shows the login and a ✓ per server, and replaces it with a new token. The hub
    refuses both `/api/github` paths from a page. No popup asks for a token any more. Instead of:
    a token pasted per server in a tab each, and a popup on every page load for a profile
    without one. Files: `src/claudia/hub/github_login.cljs`, `src/claudia/slave/tokens.cljs`,
    `src/claudia/web/views/settings.cljs`.

24. Settings start from a small menu at the bottom left (2026-11-14): a gear at the rail's foot
    (and the list's, when it is open or on a phone) opens the default plan's 5-hour and week use
    (a click: Settings), Machines (the Systems popup), Archived chats, Forms, the Desktop popups
    switch, More settings, and the version. Settings itself keeps what needs room: the Claude
    tokens, GitHub (decision 23), all ~/Work sessions, Admin and Servers for admins, each
    server's chat colours. The right half shows only the picture: the timeline view is gone.
    The window is one grid: the chalk lines run from the top to the bottom between the safe areas
    (never under the status bar or the home bar) and meet each other, the drawing board wears the
    chat's own background,
    every top starts `--edge-t` down, and the composer's row, the picture's bar and the gear share
    one bottom line (`--edge-b`, the home bar's room included, and `--foot-h`), so an iPad's
    bottom inset moves all of them together. Instead of: a gear in the list's header with one
    long Settings scroll, the Systems computer alone on the rail, and a Picture | Timeline switch.
    Files: `src/claudia/web/views/menu.cljs`, `src/claudia/web/views/settings.cljs`,
    `resources/web/css/claudia.css`.

25. Chat colours everywhere, no default look (2026-11-14): every chat has one of the chat
    colours and the window always wears one: the open chat's, or a new chat's (it has one from
    the moment it opens, decision 22). The "default look" preference, the palette's "No colour"
    and the grey fallback are gone; a runner gives each chat without a colour one when it starts
    (`colour-every-chat!`). Ash (passive) stays a colour picked by hand.
    Instead of: a grey default theme when a chat had no colour, which made the app look like two
    apps. Files: `src/claudia/shared/colours.cljc`, `src/claudia/slave/chats.cljs`,
    `src/claudia/web/subs.cljs`.

26. A crayon box of 20 chat colours (2026-11-14): cherry, chocolate, peach, tangerine, gold,
    sunflower, olive, kiwi, forest, mint, jade, aqua, sky, ocean, sapphire, periwinkle, grape, plum,
    magenta, bubblegum (and ash, passive, by hand). Picked from 33 crayons so no two look alike,
    measured in OKLab both as the crayon and as the whole window tinted with it; the closest pair
    left (aqua, mint) is further apart than any dropped crayon from its look-alike, save salmon.
    A new chat never takes the colour of the chat started last (archived or not); among the other
    colours worn least, it takes the one most different from every colour worn more and from that
    last one (`most-different`): no randomness. Worn = the chats in the list plus the 5 started
    last, archived too (2026-11-16: archiving every chat left nothing worn, so each new chat was
    cherry). The page picks a new chat's colour once its server's list is here, not before. A runner moves its chats over on start: each old
    colour becomes its crayon (red→cherry, teal→mint, pink→bubblegum, …; an ash chat's remembered
    colour too). The old names stay as CSS variables only for the code forms' own lanes and files.
    Instead of: 17 colours where neighbours (indigo, violet, blue) looked alike as a window tint,
    picked at random. Files: `src/claudia/shared/colours.cljc`, `resources/web/css/claudia.css`.

27. The page follows a new version by itself (2026-11-14): the hub names the commit it runs in
    `/hub/me`; the page keeps the one it loaded with and asks again each time a socket opens (a hub
    moved to a new image drops every socket, so they all open again). Another commit: the page
    reloads, with every composer's text kept across the reload (sessionStorage), but never while a
    new chat is on its way, a file waits to be sent, or the mic is held (it looks again after 3 s;
    a message already sent no longer holds it: one whose echo never matched kept a page from ever
    reloading). On a touch screen, pulling the rail's circles down and letting go reloads the same
    way. Instead of: reloading by hand after every ship, or a "new version" button to press. Files:
    `src/claudia/web/updates.cljs`, `src/claudia/web/views/rail.cljs`.
    Prepared first (2026-11-16): before it reloads, the page fetches the new index.html and every
    script and style it names into the browser's cache (up to 20 s), then reloads at once; the
    reload only revalidates them, so it is as quick as opening the page, not a 5 MB download. The
    drafts are read at that moment, and a mic that is waking (amber) also holds it.

28. Small manners (2026-11-14): archiving a chat asks first ("Archive this chat?", the one confirm
    card, `src/claudia/web/views/confirm.cljs`; unarchiving asks nothing); every error line goes by
    itself after 5 s or on a click (a newer error stays its own 5 s); each chat on the rail is as tall
    as the next (its title cut after two lines, one line keeping the room of two), so the spacing
    between circles never changes. The iPhone app (1.3.1) grants the hub's page the microphone
    itself, so WebKit no longer asks on every page load (each new version reloads the page); iOS
    still asks once for the app. The PR check runs the tests as their own job beside the build.

29. A release builder, apart from the hub (2026-11-14): one machine at a time reads GitHub (main's
    pushes and the heads of this repository's pull requests, ahead of their merge), builds, signs
    each file with its own key (OpenSSH `ssh-keygen -Y`, namespace `claudia-release`) and brings the
    files to the hub; the hub keeps them and hands them out, and tells servers and the gate to fetch
    a tree once its code and both platforms' node_modules are there. The hub signs and checks
    nothing: every server (`deploy/update.sh`) and the gate's hub helper check each file against
    `deploy/release-signers` on GitHub's main before installing, so GitHub stays the one place that
    says which builder counts, and a hub can hold an update back but never change one. There is no
    second signature from a person: a builder taken over can ship to every machine. So the builder
    runs on a machine only Paul is root on: destiny since 2026-11-16 (was Peter's box, where everyone
    with sudo could have signed). Making another machine the builder: run
    `sudo deploy/release-builder/install.sh https://<hub>` there, paste the builder key it prints in
    Settings (Servers page, Release builder: the old builder's token stops counting at once), and
    merge a PR adding its key to `deploy/release-signers`. node_modules go up only when the lockfile
    is new. A file on the hub counts as there only when its signature is the builder's own key's;
    one signed with another key (an earlier builder's) is built and signed again (2026-11-17: the
    node_modules signed on Peter's box stayed on the hub after the move, and the gate refused the
    hub of 194952a). The builder keeps a shadow-cljs server running between releases (a warm JVM: 35 s instead
    of 53 s for the five builds; five builds side by side were slower), started again only when the
    packages or shadow-cljs.edn change. The builder is also main's merge gate: before it builds a
    commit it runs clj-kondo, the ratchet and the tests (compiled in the warm server, run beside the
    release builds) and sets the commit status `builder` (pending, then success or failure and why),
    with a fine-grained token that can write this repository's commit statuses and nothing else.
    `builder` is the check main requires; GitHub's `check` and `test` still run but are not required
    (stage.yml's fallback releases still come from them). A commit that fails is never brought to the hub. GitHub's signed releases (stage.yml) stay the fallback. Instead of: GitHub's runners only
    (~6 min from a push to green, its slow days), a self-hosted GitHub runner (tried: slower over a
    home line, 382 s against 207 s), or the builder pushing to servers itself (it would need to reach
    them; its fence lets it reach only the internet). Files: `deploy/release-builder/`,
    `deploy/bundle.sh`, `src/claudia/hub/releases.cljs`, `src/claudia/hub/announce.cljs`.

30. One reading font (2026-11-14): Atkinson Hyperlegible Next (the 2025 redo, any weight) for the
    page, the picture's labels and Claude's replies, and the person's own messages too (they were in
    Kalam, handwriting, where I, l and 1 look alike); still in the accent colour, right-aligned.
    Kalam stays for headings and the chalk labels. Every chat on the rail and every row of the list is the same height
    with the same gap, passive chats too (they no longer sit apart at the bottom; a chalk line marks
    where they start), titles cut to fit. Above the composer only the running agents (the finished ones' pill is gone,
    the agents list keeps them) and no chalk line; the line between chat and picture has a 20 px
    handle a finger can take (the drag takes the pointer, so a touch no longer scrolls instead).

31. A new chat is on the rail at once (2026-11-14): starting one (New chat, Alt+N, or opening Claudia on a new chat)
    puts its circle at the top of the rail straight away, in the colour its header wears, titled
    "New chat"; sent, it shows the first line until the runner's `created` and the list take over.
    Opening another chat before sending anything takes the circle away again (nothing was made).
    Instead of: the circle appearing only once the first message had made the chat.

32. The update pill (2026-11-14): at the rail's foot, only while an update moves or just finished.
    The builder tells the hub each step it tells GitHub (checking and building, then success or
    failure and why); from the push to main on, the hub asks every server's front which commit it
    runs (GET /api/version, every 2 s, for up to 3 minutes; one that does not answer the moment the
    update begins is asleep, and nobody waits for it: it updates itself when it wakes) and marks the hub itself done when it
    starts on the commit it waited for (kept in <data>/progress.json across that restart). The pill
    says "Building <commit> · 0:42", "Updating 3/5 · 1:10" (a clock running from the build's start,
    then from the merge, 2026-11-16), then "Live in 16 s" (or how many were not reached)
    for 90 s; a click lists every step with its seconds. Under the gear, very small, when
    the version on screen went live ("8495c52 · 5 min ago": `/hub/me`'s commit and `updatedAt`, the merge of the
    release the hub runs, else when the hub started). Files: `src/claudia/hub/progress.cljs`,
    `src/claudia/web/views/update_pill.cljs`, `src/claudia/web/views/pure/update_pill.cljs`.

33. Back fast after a sleep (2026-11-14): a page away for more than 5 s (a laptop asleep, a phone
    locked) opens every socket again the moment it comes back, instead of trusting one that still
    says open: after a sleep or a network change it is often dead, and the browser took 2–3 s to
    notice before reconnecting. The network coming back (`online`) does the same.

34. Lighter on a phone line (2026-11-14): the gate compresses the page (zstd/gzip: main.js 5.3 MB
    → 1.35 MB, and it comes again after every update) and the hub compresses the page's sockets
    (permessage-deflate with small windows, ~20 KB of memory per socket; frames under 1 KB as they
    are). Opening a chat shows its end, and things that grow after they are drawn (images, a
    picture's frame, the fonts) keep the end in view while the person is at the bottom.
    The socket's window is limited on the hub's side only: a limit on the browser's side
    (`clientMaxWindowBits`) made ws refuse Safari and Firefox, which offer plain
    permessage-deflate, so no chat opened on an iPhone or iPad (fixed the same day).

35. A broken page is stopped before the merge and rolled back after it (2026-11-15, after #171 shipped
    a page whose every first frame the runner refused: no chat loaded for 1 h 47 min, and every
    machine was up, so no check saw it). Before: the smoke test (`bin/smoke.mjs`) runs the built hub,
    front and runner on loopback with throwaway data and one seeded chat and uses the page in
    headless Chromium, on a desktop and a phone screen: the chat list comes, the chat opens and shows
    its messages, the page tells the hub it works, no frame is answered with an error, nothing throws.
    The release builder runs it after the tests (its browser: playwright-core's headless shell; its
    system libraries from `install.sh`), and so does GitHub's `check`. After: every page load says
    once whether this release works for it (`claudia.web.health`, `POST /hub/health`): it worked (a
    chat list came), or a socket opened and no list came within 30 s, its own code threw first, or
    no socket opened within 30 s (counted only when one of the person's servers answers); nothing
    while hidden or offline. It is the page build's first module (`js/health.js`), so it runs and
    reports when the rest of the code breaks on load. In the hub's first 15 minutes on a commit, two
    broken loads of that commit and no working one roll it back (`claudia.hub.health`): the hub asks
    the gate's helper (`rollback <commit>` in `HUB_UPDATE_REQUEST`) and pokes every front
    (`{ rollback }` → `rollback <commit>` in `CLAUDIA_UPDATE_TRIGGER`), once. Each machine goes back
    to the release it kept before that commit (`deploy/update.sh`: `<release>.old`; the gate: the
    previous pin), only while that commit runs and only one step (the one before is not kept once it
    is back), and never installs that commit again; main moving past it moves everyone forward. Pages
    reload to the release that is back (decision 27). "Only forward" otherwise stands. Instead of: a
    check that the processes answer (all did), a probe from outside with a login of its own, or a
    rollback by hand (it needed a terminal and someone at it).

37. GitHub through the hub's GitHub App (2026-11-16): only the hub keeps a long-lived GitHub secret.
    A person presses Connect in Settings (GET `/hub/github/connect`), authorizes the app on GitHub
    and comes back (`/hub/github/callback`); the hub keeps their user token (8 hours) and refresh token
    (6 months) sealed, renews a token that runs out within 30 minutes, and hands each person's current
    token to their profile on every server they use (`PUT /api/github` as them: gh and git's helper).
    Which repositories it reaches is chosen on GitHub when the app is installed (Settings links
    there). SSH keys and the logins kept before (`/hub/github-login`) go on as before. Instead of: a
    personal access token per person pasted into every server (long-lived, on every machine).

38. Each person's own chat order, and groups (2026-11-16): the list and the rail show the chats in
    the order the person put them; a chat stays where it was dragged, a new message never moves it, and
    a new chat appears on top. Dragging (a finger holds a row 350 ms; a mouse moves it): let go on the
    upper or lower third of a chat, it goes before or after it; on its middle, the two become a group
    (like an iPhone folder) whose name is typed at once. In the list a group is a header that folds,
    with its name (double-click renames), how many chats, and a + that starts a chat in it with the
    group's picks (the last ones a chat in it started with); that chat goes on top of the group. On
    the rail a group is a stack of its chats' colours; pressed, a chooser card opens beside the rail
    with each chat's full title and the +. The order is kept on the hub per person (`GET`/`PUT
    /hub/chat-order`, the whole list each time, `claudia.shared.order`), so every device shows the
    same; a page asks again every 20 s and when it comes back to the front. Searching, a project chip
    and the archived list show the chats found as before; another person's chats (a view) keep the
    plain order. Instead of: the order the chats were started in, or moving a chat up on each message.
39. Every block is a form (2026-11-16): a block is `{ id, name, width, form, data }`; the graph and
    Claude's own HTML page are the forms `graph` and `html`, beside the predefined ones. The draw
    call has only `title`, `clear`, `blocks` and `removeBlocks`; every block updates by the same
    rule (item lists upserted by id, `removeItems`), with two additions: a graph's arrow is named
    `"from->to"` by the runner (Claude never names arrows), and new `html` starts its parts again.
    The page has one render path (`claudia.web.views.codeforms/form-view`). `graph` and `html` are
    listed in the Forms window and cannot be deleted. Saved pictures were rewritten once into this
    shape when each runner's store first opened (`claudia.slave.upgrade`, `PRAGMA user_version` 1):
    blocks with a kind, and pictures from before blocks (their cards became notes, a page card html);
    the page's old-picture view is gone. Templates keep their table (a template is html data).
    Instead of: three block kinds (graph, html, form), the draw call's own graph fields (`graph`,
    `nodes`, `edges`, `groups`, `remove`), and the page reading every shape ever saved.

40. Buttons where touches land (2026-11-16, #197): on screens where
    touch is the main input (`pointer: coarse`: phones and iPads, with or without a trackpad), the
    most used buttons sit where people tap best; a desktop with a mouse keeps its layout. Source:
    Steven Hoober's measurements (millions of touches on phones and tablets): misses are about 7 mm
    in the middle of a screen, 9 mm along the edges and 12 mm in the corners, and people move what
    they want to the middle to tap it; a big tablet held up is held at its sides partway up, so the
    thumbs rest on the side edges from the middle to the upper third, and the bottom edge is the
    hardest reach (88% of tablet use is seated; a big tablet lies flat in 40% of uses and stands in
    a holder in 22%). So:
    - iPad: the left edge is for chats, the right side for the mic and the picture's ‹ › (2026-11-17: Stop (while a turn runs) left of the mic, Settings right of it (a tap again closes it; the rail has no gear on the iPad), search, My words and archive over it, ‹ New chat › under it, all see-through at rest; the mic 128 px in so its flower fits; My words, shown when the hub has a listen service, opens a panel over the dimmed page, as search does, to see, add and remove words). The rail's
      circles (no + since 2026-11-17: a new chat starts from the edge's New chat, Alt+N or the list) sit centred at middle height, the open chat kept in the middle; with more
      chats than fit, the rail fills the edge and scrolls the open chat to the middle.
    - Phone: the mic sits in the bottom middle, above the text row; the chat list's + sits in the
      bottom middle too (bottom middle is each screen's main action), the list ending in room as
      tall as the + so the last chat scrolls clear of it.
    - Send and Stop stay at the text box's right end on every screen: Send is for typed text (the
      hands are at the keyboard, the on-screen keyboard lifts the box), letting go of the mic sends.
    - The main buttons (mic, +, ‹ ›, the rail's circles) are at least 12 mm wide, the corner miss:
      72 pt on a phone, 64 pt on an iPad (56 pt was 9.3 mm on an iPhone).
    Instead of: the mic in the composer's bottom-left corner and the list's + in the bottom-right
    corner (the least accurate spots), the rail from the top corner, the picture's ‹ › only in the
    bottom bar, the main buttons along an iPad's bottom edge (a phone habit; a held tablet's thumbs
    are rarely there), Apple's top toolbar (a convention, not a reach finding), and the same places
    for a mouse (the research is about fingers).
41. The app records the mic (2026-11-17): in the iPhone/iPad app the composer's mic is recorded
    by the app (NativeMic.swift, AVAudioEngine; the page's claudia.web.mic-native asks it over a
    message handler) and the page gets the clip as 16 kHz WAV and the bars' spectrum. Every press
    checks the app's audio session and engine and starts them again when they stopped. In a browser
    the page records as before (getUserMedia + AudioWorklet), and a fresh opening that gives no
    sound is tried again up to three times in the same press. Why: the hub's mic log showed the
    web view's microphone opening live after a switch to another app and giving no sound, press
    after press, for up to half a minute; the page cannot reach the app's audio session. Rule:
    whatever the app can do better than the web view, the app does, and the page keeps working in a
    browser. Instead of: keeping the page's microphone in the app and only waking the app's session
    before each opening (WebKit owns that session; no guarantee it hands the input back).
42. A Copy pill instead of the iOS edit menu (2026-11-17; TAKEN BACK the same night in app 1.4.2:
    with the menu turned off, selecting text on the user's iPad lagged and mostly never started (the
    hub logged 2 selections out of many tries), worse than the menu over the handle; the app shows
    iOS's own menu again and the page shows no pill, since the pill only shows where the app answers
    `claudiaText`): in the iPhone/iPad app the iOS edit
    menu is off outside text fields (ClaudiaWebView.swift), and a selection that rests gets one
    Copy pill above it (claudia.web.copy-pill), which copies through the app (`claudiaText`,
    UIPasteboard). In a text field the menu stays (Paste). Why: iOS 27 opens the edit menu right
    under the selected text, in Safari too, so a finger reaching for the lower blue handle lands on
    the menu; neither a page nor WKWebView can move that menu. Cost: Look Up, Translate and Search
    Web are gone in the app. A browser keeps the iOS menu (a page can't turn it off) and shows no
    pill. Instead of: keeping the iOS menu (the bug stays), a Copy button on every message (part of
    a message still meets the bug).
43. A held selection holds the chat column still (2026-11-17): while a text selection is held in
    the chat column, the column does not scroll itself to its end (streamed text, images loading);
    once the selection is let go it catches up (claudia.web.selection). Each held selection tells
    the hub's log (the mic log, event `selection`) how long it was held, how many self-scrolls it
    held back and how often the column's text changed under it. Why: on an iPad selecting text
    lagged and stopped at times, while a chat was answering: every streamed piece moved the text
    under the finger dragging a handle. Instead of: guessing further from the simulator, whose
    scripted touches on a still test chat never showed it.
44. A selecting finger is never held (2026-11-17): the page cancels a touch's moves when nothing
    under it can scroll that way (so iOS never pans the page itself, claudia.web.viewport), but never
    while a selection is held (its handles) or after the finger rested 300 ms (a long press: iOS
    picking and widening a selection). Why: a cancelled touchmove stops iOS's selection gestures,
    so selecting on an iPad stalled or never started depending on which way the finger first moved;
    Discourse met the same in its composer and lets touchmove through while a selection exists.
45. One search panel (2026-11-17, the user's picks one by one): a panel over the dimmed page, not
    the whole screen (claudia.web.views.search-panel). One list of every chat, open and archived:
    titles, projects, what was said. On every screen: the iPad's edge search, the rail's search
    (only where there is no right-edge column), the list's search button, ⌘K / Ctrl+K. Before a
    word is typed: the ten archived chats last active (a row keeps no archiving time). A find: the
    title in the chat's colour, project, when, the lines that matched lit; a tap opens that chat at
    that message. Project chips under the field. ✕, Escape or a tap on the dimmed page closes it,
    the typed words kept. The list's search box went, and the rail no longer filters itself.
    Instead of: Open / Archived tabs, an empty panel until typing, a full screen, swipe to close.
46. The rail says how each chat stands (2026-11-17, the user's picks): bigger circles (42 px), no
    badges on them; a green ring turns round the circle while the chat works (a turn or its agents).
    Under the name (two lines, as before) a third line in words: “4:12 · 2 agents” while
    running (a live clock), “? asks · 3m” in amber while a question waits, “done · 12m”
    when it finished since you looked, “2h ago” / “Tue” / “12 Nov” when quiet
    (claudia.web.views.pure.sidebar/status-line). The list and the group card keep their badges.
    No card with more (started, project) on hover or long-press: dropped by the user the same day.
47. The hub proves itself with a key, not with the network (2026-11-17, the user's call to move every
    machine off the gate's WireGuard onto Headscale): every call the hub makes on a front carries
    `X-Claudia-Hub-Key`, that server's own key: HMAC-SHA256 of its name under the hub's secret
    (`HUB_KEY_FILE`), so one server's key opens no other front. The front (`FRONT_HUB_KEY_FILE`)
    answers 401 to any call without it, compared in constant time, and never passes it on to a
    runner. Before, the front believed `X-Claudia-Email` from whoever reached its port, and only a
    WireGuard tunnel plus a firewall rule kept others out. With Tailscale in userspace mode a
    network's connections arrive from localhost, where every user of the machine could have called
    the front as anyone. Pairing no longer makes a network: `deploy/pair.sh` prints a one-time secret,
    the admin pastes it with the address the hub reaches the front at (any private network), and the
    server fetches its key once. The WireGuard address pool, peer files and the gate's peer helper are
    gone. Instead of: one shared key for every server (a server's root would hold every front's key),
    or mutual TLS (certificates to issue and renew per server for the same proof).

## Hub operations (2026-11-03, ported from the TypeScript Claudia's decisions 22, 23, 24, 27)

- **Servers and access**: the hub's own SQLite store (`HUB_DATA/hub.db`, `src/claudia/hub/store.cljs`)
  is the source of truth for the servers, the people and the People table (per person: the servers
  they use, a tick = their own profile there; whose chats they may write to; admin). A `hub.json`
  (`HUB_CONFIG`) is imported once into an empty store, then no longer read. Admins (the store's admin
  flag, or the login group `HUB_ADMIN_GROUP`) change it in Settings → Admin (People and Servers).
  A write tick (2026-11-16) lets a person open, start and send in another person's chats on every
  server where both have a profile: the hub names those people to each front (`X-Claudia-Writes`),
  which then passes the view on without `X-Claudia-Read-Only`; Claude runs as the owner's Linux user.
  Without a tick another's chats stay read only, and secrets are only ever read for one's own profile. A server gets its hub key with `deploy/pair.sh` (decision 47: a
  one-time secret printed as a key, pasted into Servers with the address the hub reaches its front
  at; the server fetches its key with the secret, once). Pairing a known server again gives it its
  key again.
- **Push**: the hub tells a person's iPhones (APNs, token auth with jose; `HUB_APNS_DRY_RUN=1` logs
  instead) when a chat of theirs whose bell is on (decision 21) finished a turn, asks a question, or waits on a secret. It learns
  that like the page does: one socket per person with a phone and server the grid allows, as that
  person (their own chats only). Alert texts are sealed (AES-256-GCM, node:crypto) under the key the
  app made; every alert carries the badge (waiting plus finished-unseen chats across servers); a
  change of the count alone goes as a badge-only push; one summary Live Activity per phone. While the
  person works at a Claudia page (`POST /hub/presence`; a browser or, since 2026-11-16, the iPhone
  and iPad app too), alerts are held; when they leave, the newest per chat that still matters goes.
  One device rings (2026-11-16, after every open page rang at once): a page plays a chat's sound and
  popup only while it is in use itself (in front, touched in the last minutes), or when none of the
  person's other pages is (`GET /hub/presence?page=<id>` -> `{elsewhere}`).
- **Following main** (built before the merge, 2026-11-03): `check.yml` checks a PR at its head
  commit and packs what it built as one bundle keyed by that commit's git tree. When the check is
  green (a PR into main from this repository, or a push to main), `stage.yml` (run on main, so its
  definition is main's) takes the run's bundle, checks its `tree.txt` against GitHub's record of the
  run, and publishes the slave release `claudia-tree-<tree>.tar.gz` on the GitHub release
  `tree-<tree>` (with the same build in two: `claudia-code-<tree>.tar.gz`, a few MB, and
  `claudia-deps-<lockfile hash>.tar.gz`, its node_modules, which a server downloads only when the
  lockfile changed and keeps in its state folder) and the hub image `ghcr.io/<owner>/claudia-hub:tree-<tree>` (COPY only from the
  bundle), both signed keyless by cosign as `stage.yml@refs/heads/main` (the image with
  `tree=<tree>`). GitHub's webhook (`POST /hub/github`, HMAC with `HUB_GITHUB_SECRET_FILE`): a stage
  run done → the hub tells every front `{stage: tree}` and the gate `stage <tree>` (lines added to
  `CLAUDIA_UPDATE_TRIGGER` and `HUB_UPDATE_REQUEST`), and they download and verify that build ahead;
  a push to main → `{main: commit}` and `update <commit> <tree>`. Each server's `deploy/update.sh`
  (path unit on the trigger, hourly timer) and the gate's helper take main's commit from git's ref
  list (waiting up to 40 s for a pushed one), its tree from the API (each server asks GitHub about a commit once and keeps the answer: without a token it gets 60 calls an hour), and install that tree's build:
  fetched ahead, so a merge of an up-to-date PR only swaps folders and restarts (seconds); else
  downloaded when stage.yml has published it for main's own check (minutes). Only forward; the
  front and the runners restart, never the hosts. No call fails while they do (2026-11-16): the
  hub tries a front that refuses it again every 0.3 s for up to 15 s, and a front a runner whose
  socket refuses or is missing, for any call without a body (GET, HEAD, a WebSocket handshake), so
  a restart makes a call wait a moment instead of failing; a front that is off or asleep times out
  as before. The hub on the gate runs in two slots and switches without a gap (the gate repo). A single-user machine's `CLAUDIA_CHECKOUT`
  must be a release folder (a git checkout is never replaced): first install with
  `CLAUDIA_CHECKOUT=<folder> deploy/update.sh`, then enable `claudia-update.path` and `.timer`
  (on a Mac the same first install loads the launchd agents, the update agent included:
  [deploy/macos/README.md](deploy/macos/README.md)).

## The 3D scene, the phone and alerts (2026-11-03, ported from the TypeScript Claudia's decisions 26 and 29)

- The scene3d form: parts (box, sphere, cylinder, cone, pyramid, torus, capsule, plane, ring, extrude, lathe, tube, rod, group, person) in a three.js view the person turns, zooms and pans; arrows (flowing dashes), labels, a legend, a click on a part opens its facts with Explain (asks Claude in the chat). Diagram look by default (flat colour bands, chalk outlines), a realistic one with materials and shadows. Labels are callouts, as in an atlas: never on the model, in a column on each side of it (clear of the legend, the subtitles and the bar), each with a thin line to a dot on its part; a label whose dot is hidden behind something solid is not shown, and one that does not fit is left out. Without a story every label shows; in a story, the step's focus, else what the step brings in, else (an overview step) every label, and always the part under the pointer. A story steps through it (show, hide, focus, camera, poses) with subtitles and, once turned on, a narrator voice from the hub (`/hub/narrate`, `HUB_TTS_URL`), in the story's `language` (an ISO 639 code: the voice service gets it, and a described `voice` is cloned from a reference clip spoken in that language, so Hungarian lines get a Hungarian accent; a language the hub has no reference sentence in is spoken by the service's default voice). A `script` runs in QuickJS (WebAssembly; no window, no network) for parts made by code and motion every frame; solid parts that pass through each other glow red and are counted. The film button in the bar makes the story's video in the background (the page may close at once): the page sends the scene as shown to the hub (`POST /hub/videos`), the hub hands it to the person's runner and, when it has a voice, says the lines and brings the clips (always: the page's voice switch is for listening now); the runner's own headless page plays the story on a stepped clock (every frame 1/30 s after the last, however slow drawing is), paints each frame with its labels, legend and subtitles, and ffmpeg makes them an H.264 MP4 with the voice as AAC, kept with the chat (`RUNNER_DATA/videos`). The bar shows how far it is, then a button that plays it over the page with Download (a web-app window shows no downloads of its own; the file is named after the scene), on any device and after a reload, and a desktop popup when popups are on. A scene without a story gets 15 s. Needs ffmpeg on the server (`CLAUDIA_FFMPEG`, else `ffmpeg` on the PATH). Real human anatomy: a part `{shape: "anatomy", ref, side?, system?}` becomes the structures it names (bones, muscles, nerves, ligaments, muscles' origin and insertion patches; by English or Latin name, id, region, compartment or nerve territory), each loaded as its own small model from [claudia-anatomy](https://github.com/p4ulcristian/claudia-anatomy) (Z-Anatomy, CC BY-SA 4.0); the hub (`/anatomy/<version>/…`, kept in `HUB_DATA/anatomy`) and the runner's render page (kept in `~/.cache/claudia/anatomy`) fetch each file once from the pinned version (`claudia.shared.anatomy`). One 3D scene per picture. It stays a general tool: small generic primitives, nothing topic-specific. three.js, QuickJS and the view are their own module (`out/web/js/scene3d.js`), loaded on first use. Files: `src/claudia/web/scene3d/` (engine, view, person, poses, story, ink, overlap, vm, voice, clock, maker, frames, paint, videos, icons, callouts, anatomy; pure maths, data, labels, bar, video, structures), `src/claudia/slave/video.cljs`, `src/claudia/hub/videos.cljs`, `src/claudia/hub/narrate.cljs`.
- People: a `person` part is a rigged CC0 MakeHuman body (`resources/web/person/person.glb`, about 400 KB, made by the TypeScript Claudia's `tools/person/make-person.py`), posed live: named poses measured from the body (stand, t, reach, wave, point, hands_on_hips, hold, walk, sit, sit_floor, kneel, squat, bend, lie) and any target overridden; arms and legs are exact two-bone chains, hands, feet and the gaze can target a part; parts can ride on a joint (`parent: "anna.handR"`).
- Phone: the list, the chat and the picture are screens, each sliding over the one before; sideways swipes follow the finger (`src/claudia/web/swipe.cljs`): on the chat right goes to the list and left pulls the picture in, on the list left brings the open chat back, on the picture and on Settings right goes back; past 40% or flicked it completes, else springs back; never in fields, on what scrolls sideways itself (code, tables, graphs, the 3D view), with two fingers or on a mostly vertical move. A Home Screen web app (`resources/web/app-icon/`: manifest and icons; standalone safe areas, 100lvh).
- The chalk hover tip (a clipped chat title, or any `data-tip`), the logo mark in Settings and the favicon.
- Sounds per chat colour (a bell when a chat finishes, two rising notes when it waits on you) for every chat whose bell is on (decision 21), and desktop popups (the browser's notifications, asked once; a per-device switch in Settings) for the same chats; the open chat only when the window is not in front (`src/claudia/web/alerts.cljs`).

## Code forms and templates (2026-11-03)

Ported from the TypeScript Claudia (its decisions 17-21): the `files`, `editor` and `flow` forms are
always on; `git`, `clojure` and `calls` are opt-in per runner (`CLAUDIA_FORMS=git,clojure,calls`;
clojure and calls need `clj-kondo` on the runner). Forms are described in `claudia.shared.forms`
(the draw tool, the system prompt and the Forms gallery read it). Code is coloured by Shiki
(VS Code's grammars and Dark+ theme; 2026-11-03: highlight.js coloured differently), built from npm
into the page's own files and loaded on first use, each grammar when its language is first shown; the editor is CodeMirror 6. Templates
(`save_template`, `templates`, "save as template" on an html badge) live in the runner's SQLite.
Built-in forms can be deleted and restored in the Forms window (per profile; Claude's draw tool
leaves them out). Claude picks the kinds and the layout of every picture itself (decision 14).
Claude Code cuts long tool descriptions, so the forms' data is also in the system prompt.

## Build

```
npm ci
npx shadow-cljs release host runner front hub web
```

## Back from the TypeScript Claudia (2026-11-03)

- Picture history: a picture per turn (SQLite `pictures`); the bar under the picture steps through them (‹ › ←/→, a scrubber), the newest is live, older ones read only; fold (Alt+P); the timeline view was removed 2026-11-14 (picture only); the camera copies or saves the picture as PNG.
- Every draw answers Claude with a screenshot of the picture: playwright-core opens the page's render mode (`claudia.web.render`) from this checkout's `out/web` in a headless Chromium (`/opt/claudia-tools/ms-playwright`, else Playwright's own, else a system Chrome or `CLAUDIA_CHROME`).
