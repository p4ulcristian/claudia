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
7. Secrets from 1Password, never stored on a server: [plan/1passwd-plan.md](plan/1passwd-plan.md), read by the key broker on the gate since decision 66.

8. Back from the TypeScript Claudia (2026-11-03): agent chips (right above the composer since 2026-11-14) (subagents and
   background shells, their view with Steps / Files / Prompt / Report, Stop, progress bars from
   `##progress` lines, the dashed amber ring while work runs after the turn), the turn's file changes
   (removed by decision 97), the plan's 5-hour and week usage and the chat's context use (a click
   compacts), the model and effort per chat, and the `/` command menu. Edits inside Sentinel's
   worktrees are reported to it where `CLAUDIA_WORKTREES_DIR` and `CLAUDIA_SENTINEL_URL` are set.
   An agent's own shells and agents are part of it while it runs; Claude Code keeps a subagent's
   background shell running after the subagent finished, so one that outlives its agent is a chip of
   its own (2026-11-20: a finished fork hid its running shell while the rail said "1 agent"). The
   chips, the list and the rail's count and clock read the same rows (`claudia.slave.tasks/tops`).

9. The code is small pure functions (2026-11-03, after a local `keep` shadowing `clojure.core/keep`
   crashed the hub on every start): data in, data out, at most 30 lines each, effects at the edges,
   one shared copy of every helper, no shadowed names. A PR check (lint, a ratchet on shadowed names
   and long functions that only goes down, tests, the five builds) holds it. Rules in CLAUDE.md.
   Instead of: long handlers mixing reading, deciding and writing state, and a copy of each helper
   per file.
10. Claude tokens are kept by the hub (2026-11-03): saved once in Settings, sealed in `hub.db` under a
    key that lives only in the gate's secrets (`HUB_TOKEN_KEY_FILE`). The hub writes them into each
    server's token files (the only copies a server keeps, since Claude runs there) and brings a
    server that was off or is new along within a minute. Which one a chat runs on: decisions 16 and
    57 (the "one in use for every server", its switch on the rail's foot and later in Settings, is
    gone since decision 57). Instead of: each server keeping and switching its own saved tokens.

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
    in `<token file>.saved.json`). What a new chat and a chat without a pick run on: decision 57 (the
    shared default this decision first kept, set in Settings, is gone). Instead of: one token for
    every chat of every person, where one person's switch moved everyone's chats.

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
    the mic (320 px until 2026-11-17: on the iPad's edge it ran off the screen; a 300 px canvas
    since 2026-11-20 so the move ring of decision 73 fits): a 72 px disc,
    amber while waking and red while recording, the time spoken in it; round the disc the 48 bars
    that follow the voice (back 2026-11-16: the bars, not a breathing disc, show the voice). Four
    petals open outside the bars: up sends, right throws the clip away, down sends now (the running
    turn stops first, 2026-11-17), left adds the words to the message; letting go in place sends
    too (2026-11-20, the user's call; before: left sent, up threw away, right added). Each clip let go waits in a list over
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
    Where to speak (2026-11-18, the user held the iPad with the microphone covered): in the app,
    each press's start answer says which microphone iOS records from (`mic`: its port, and for the
    built-in one the data source with its location Upper/Lower and orientation Front/Back/Top/Bottom,
    `NativeMic.micInfo`); while recording, a green "speak here" glow lights the screen edge nearest
    it, turned with the screen (`screen.orientation.angle`). A data source can be several
    microphones combined, so it names a side, not one hole. Headsets and AirPods show nothing; a
    browser cannot tell, so it shows nothing. The hub's mic log gets `mic` with each start.
    Listen back (2026-11-18): removed by decision 96.
    My words (2026-11-17, decision `2026-11-17-listen-vocabulary-and-cleanup` in the Iris repo):
    the listen service hears with each person's own names and terms and cleans the text up. The hub
    sends the signed-in caller's email (lower case) as `speaker` with every clip and gets
    `{text, raw}` back: text cleaned up, raw as heard. The draft gets the clean text; while the
    raw words differ and the draft still holds them, a chip over the composer shows the other
    version and a tap swaps them (and back). My words: the list, Add, and × to remove, saved
    through `/hub/listen/words` (the caller's own list only; hidden without a listen service); since
    decision 95 in Settings' Dictation section only, and words come from cards Claude puts in the
    chat (the composer's "Add <word> to My words?" after a corrected word is gone). The clip flow
    above is the same. Files:
    `src/claudia/hub/listen_words.cljs`, `src/claudia/web/words.cljs`,
    `src/claudia/web/views/my_words.cljs`, `src/claudia/web/views/pure/dictated.cljs`.

21. Chats' voices (2026-11-20, the user's design; before: talking mode, 2026-11-13, a speaker
    switch per chat that read only the open chat's replies, in one voice, a new reply cutting off
    the one before). Every chat has a voice, Off by default: the voice pill (row 2 of its header,
    decision 22) picks Off or one of six named voices (`claudia.shared.voices`: a name and a
    description; the hub makes ONE reference clip per description and clones every line from it,
    `/hub/narrate`, so a voice is always the same person), each with a sample to hear. Beside it the
    chat's bell (the Row's `bell`): on, the chat chimes, pops up and pushes to the phones when it
    finishes or waits; off, it does none of them (the phone's badge still counts it). Since
    decision 90 the bell is the notify pill, which also picks the sound it rings. A new chat
    takes the last chat's voice and bell (its header's picks go with `new`); a chat only looked at
    (another profile's) shows both and changes neither. Chats whose speaker was on got the first voice (Nora).
    Since decision 93 a chat's reply is a voice message that plays only on a tap; what follows is
    how it was before. Every chat with a voice speaks, open or not: when its turn ends by itself (not stopped, not
    failed) the runner sends `speak` with the whole final reply (or a new ask's questions) to the
    one page its person touched last (`here`: a touch, click or key tells every server, at most
    every 15 s, and a socket that opens says how long ago, so a restarted runner still knows), so
    not every open device talks at once. That page reads the replies one at a time in arrival
    order, each once, each in its chat's voice, its colour's short name first ("Red here.",
    decision 89; not when that chat is open), a Hungarian reply in Hungarian
    (`pure/talk.cljs`'s line and `language-of`), without code, tables, links or Markdown marks
    ("There's code in the chat." instead). While a reply is read the mic is that chat (decision
    73): the chat's colour, bars that move, its name and voice over it; while its sound is still
    being made, three dots in turn and "getting ready" instead of the bars (2026-11-21, the user:
    the bars moved before it talked), and the rail's bars below come only once it plays. A tap on it stops that
    one reply and the next one comes; a hold stops it and records into the open chat as always
    (talking to the chat that speaks instead is for later); nothing is read while the mic is held,
    and what arrives meanwhile waits and comes after it is let go. A voice turned Off drops that
    chat's waiting replies; the page going to the background drops them all (nothing is said
    late). It never listens: answers go through the mic. A reply's play button (while the chat has
    a voice) reads it at once. While a reply is read, the chat's circle on the rail and in the list
    wears the voice's bars round it in its colour, following the voice as the held mic's bars follow
    yours (2026-11-21, the user's pick of four: bars round it, ripples, bars inside, sound waves;
    `src/claudia/web/views/voice_ring.cljs`, the reply played through an AnalyserNode). One sound at a
    time (2026-11-21, the user's call): a reply read aloud and a 3D story's narrator each claim
    the sound as they start, and the one playing
    before stops (a reply stopping that way drops the replies waiting after it, as the page going
    to the background does; `src/claudia/web/sound.cljs`). A reply is heard from its first sentence
    (2026-11-21): the voice service makes a clip a sentence at a time, the hub passes each piece on
    as it comes (`/hub/narrate` answers chunked, and keeps the clip on disk only once it came
    whole: a failed or cut one leaves no file) and the page plays each piece on the AudioContext
    right where the one before ends, so they join without a gap; the reply is over when its last
    piece ends, and a tap, hold, Off or the page going away cancels the fetch with the pieces. A clip
    already kept comes whole. The 3D narrator still takes whole clips: a story times its steps by
    each clip's length, known only once it is all there. Instead of: a separate speaker button by the mic (the mic already
    sits under the thumb, and the person talking must silence the tablet anyway), every device
    talking at once, cutting a reply off with the next. Files: `src/claudia/shared/voices.cljc`,
    `src/claudia/web/speech.cljs`, `src/claudia/web/here.cljs`, `src/claudia/web/views/talk.cljs`,
    `src/claudia/web/views/pure/talk.cljs`, `src/claudia/web/views/mic.cljs`; `src/claudia/web/voice.cljs`
    is the one fetch of the hub's voice (the 3D narrator uses it too).

22. The chat's header holds every setting (2026-11-14; three rows by who, settings and usage since
    2026-11-19, the user's approved layout): one panel lightly tinted with the chat's colour (its
    background 7% of the colour, its edge 45%), the same for a new chat and a started one. Row 1:
    the title (a click renames it) and the server's state only while it is off, offline or
    reconnecting; nothing else. Row 2: pills, one component and one look (rounded, a 1 px line,
    a 16 px icon in the muted colour before the value, ▾ after it when it can be changed),
    wrapping on a narrow column: the server and the project (decision 90; a started chat without
    a folder shows its server's work folder, `CLAUDIA_CWD` as `GET /api/folders` answers it); whose
    Claude subscription (decision 16; only when tokens are saved); model · effort in one pill
    ("Opus 5.5 · high"; its popup lists the models and the efforts, no native selects); the
    prompt profile (decision 60); the voice (Off or a name, decision 21); notify and the colour
    (decision 90). All their popups share one style. Row 3: on the left the rings (the context for a started chat, a click
    compacting it; the plan's 5-hour; its week), each an 18 px ring followed by its percent, its
    tip naming it; on the right buttons with words (decision 90): Files, Sudo (its owner, a
    started chat, decision 52) and Archive (a started chat). A read-only view has no buttons and
    its pills change nothing. A new chat picks them before its first message, and they go with the `new` frame, so
    the first
    turn already runs on them (before, model and effort followed `created`, after the first turn
    had begun). A new chat also has its colour from the moment it opens (the most different from
    what the server's other chats wear, decision 26), the header wears it, the
    colour pill's palette changes it, and it goes with `new` (decision 25). Once started, a
    server pick moves the chat (decision 82) and every other pick changes the chat. The composer keeps only writing and sending, one row
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
    (and the list's on a phone) opens your own plan's 5-hour and week use (decision 57)
    (a click: Settings), Machines (the Systems popup), Archived chats, Forms, the Desktop popups
    switch, More settings, and the version. Settings itself: decision 58 (only the Claude tokens and
    GitHub, as a popup). The right half shows only the picture: the timeline view is gone.
    Your profile circle (2026-11-18): the gear became a circle with your initials, or the initials
    of the person whose chats you look at (dashed, amber: read only); its menu starts with Whose
    chats (yours, then every other person's, read only), which left the list's title menu (that
    keeps the servers).
    The rail is the only chat list on a desktop and an iPad (2026-11-18): the wide list that opened
    over it (Claudia's face top left, Ctrl+B) is gone there; a phone keeps its full-screen list.
    Archived chats open in the chat column, full height, and Back (or Escape) returns to the chat
    that was open (Settings: a popup since decision 58). The rail's top holds Claudia's face, bigger and with no frame
    round her, so her animations show whole (a click opens her chat, decision 48), and under it 2D and
    3D as two small circles; then, where there is no right-edge column, search and New chat.
    Instead of: a second, wide list beside the rail, and Settings squeezed into a 380 px drawer.
    The window is one grid: the chalk lines run from the top to the bottom between the safe areas
    (never under the status bar or the home bar) and meet each other, the drawing board wears the
    chat's own background,
    every top starts `--edge-t` down, and the composer's row, the picture's bar and the gear share
    one bottom line (`--edge-b`, the home bar's room included, and `--foot-h`), so an iPad's
    bottom inset moves all of them together. Instead of: a gear in the list's header with one
    long Settings scroll, the Systems computer alone on the rail, and a Picture | Timeline switch.
    Files: `src/claudia/web/views/menu.cljs`, `src/claudia/web/views/settings.cljs`,
    `src/claudia/web/views/shell.cljs`, `src/claudia/web/views/rail.cljs`,
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
    The gradients beside the box: decision 80. One colour per open chat and the short names: decision 89.
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
    with each chat's full title and the +. Since decision 81 the rail drags the same way. The order
    is kept on the hub per person (`GET`/`PUT
    /hub/chat-order`, the whole list each time, `claudia.shared.order`), so every device shows the
    same; a page asks again every 20 s and when it comes back to the front. Searching, a project chip
    and the archived list show the chats found as before; another person's chats (a view) keep the
    plain order. Instead of: the order the chats were started in, or moving a chat up on each message.
    Ungrouping by a button (2026-11-20, the user: "a button to ungroup any of them and all of them,
    an icon"): every chat in a group's chooser card has a ↥ that takes it out to just after the
    group, the chat menu has "Take out of group" for a chat in one (list, rail, card), and a group's
    header (the list's and the card's) has an ungroup icon that puts its chats where it was, in its
    order (`claudia.shared.order/take-out`, `ungroup`). A group left with no chats goes.
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
    most used buttons sit where people tap best (since decision 109 a desktop has the iPad's layout
    too, its buttons mouse-sized). Source:
    Steven Hoober's measurements (millions of touches on phones and tablets): misses are about 7 mm
    in the middle of a screen, 9 mm along the edges and 12 mm in the corners, and people move what
    they want to the middle to tap it; a big tablet held up is held at its sides partway up, so the
    thumbs rest on the side edges from the middle to the upper third, and the bottom edge is the
    hardest reach (88% of tablet use is seated; a big tablet lies flat in 40% of uses and stands in
    a holder in 22%). So:
    - iPad: the left edge is for chats, the right side for the mic and the picture's ‹ › (since 2026-11-21 four slots over the mic and four under it on the rail on the right wall, none coming or going, decision 92; since 2026-11-19 one column on that rail, decision 73; before, 2026-11-17: Stop (while a turn runs) left of the mic, Settings right of it (a tap again closes it; the rail has no gear on the iPad), search, My words and archive over it, ‹ New chat › under it, all see-through at rest; the mic 128 px in so its flower fits; My words, shown when the hub has a listen service, opens a panel over the dimmed page, as search does, to see, add and remove words). The rail's
      circles (no + since 2026-11-17: a new chat starts from the edge's New chat or Alt+N) sit centred at middle height, the open chat kept in the middle; with more
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
    (only where there is no right-edge column), the phone list's search button, ⌘K / Ctrl+K. Before a
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
    (claudia.web.views.pure.sidebar/status-line). No spinning (2026-11-18): instead of the turning
    ring, twelve green dots on a working chat's circle are a seconds dial: the dot of the clock's
    seconds under the name is lit (5 seconds a dot, so it steps every 5 seconds), drawn in place,
    never rotated. The chat has its own clock (its turn) and every agent its
    own (from when it started): while the turn runs the line shows the chat's clock, once only
    agents are left the longest-running agent's (“6:30 · 2 agents”, the row's `agentsFor`); each
    agent's own clock stays on its chip over the composer. A chat that finished since you looked also gets a
    big green check drawn through its circle from the middle (2026-11-18), so done stands out. The list and the group card keep their badges.
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

48. Claudia's own chat (2026-11-18, the user's picks one by one): her face at the rail's top opens a
    chat with Claudia herself, one per profile, kept out of every chat list. She manages the
    person's chats (the user calls them agents) on every server they have a profile on: lists,
    starts, writes to, archives and stops them (and since decision 88 renames, recolours, groups
    and orders them), only when asked, never on her own. Archiving can be
    undone, so she archives without asking; stopping a running turn cannot, so `stop_chat` asks the
    person first (a permit ask). She runs nothing in the background: her session runs with
    `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1` and without Monitor, ScheduleWakeup, CronCreate,
    CronDelete, RemoteTrigger and Workflow. She lives on the runner that has `CLAUDIA_HUB_URL`,
    `CLAUDIA_SERVER_NAME`, `CLAUDIA_HUB_KEY_FILE` and `CLAUDIA_OWNER_EMAIL` (destiny); its meta frame
    says `claudia`, and the page's `claudia` frame there makes or finds her chat. Her tools
    (`src/claudia/slave/claudia.cljs`) call the hub's `POST /hub/server/claudia` with that server's
    name and hub key (the one key of decision 47, now also proving a server to the hub) and the
    person; the gate passes that path without a login, the hub checks the key and that the person
    has a profile on that server, then carries the operation out on each of the person's servers
    (`/api/chat-ops/…` on the runners, `src/claudia/slave/chat_ops.cljs`). Only her chat has these
    tools: decision 18 still holds for every other chat and program. Instead of: her actions
    carried out by the person's open page (works only while a page is open), or every chat able to
    drive others (what decision 18 removed).

49. Claudia's new look (2026-11-18, the user's picks one by one): a cool programmer girl, cyber-noir:
    orange bob, small oval sunglasses, a black leather jacket, the Claude spark as her hair clip.
    Painted once with FLUX.2 [dev] with her glasses on (`resources/web/face/noir-up.webp`, background
    cut away); over it the lenses and the spark are drawn in code. She stays still and cool: no
    hops, squashes or turning, the spark never spins; only her sunglasses talk
    (`claudia.web.face.lens`): code rain while chats work (faster when busy), a symbol (! ? z), and
    words typed out in green instead of speech bubbles. A chat's news names the chat ("IRIS DONE ✓",
    "IR ASKS YOU ?", "SENTINEL FAILED ✗") for 7 s; a click on her then opens that chat (otherwise her own
    chat, decision 48). While words are on her lenses she leans out of the rail three times bigger
    so they can be read. Her day (her own clip `:daytime`): COFFEE in the morning, LUNCH, FOCUS, GG
    TODAY, GO TO BED at night. Her brain (moods, events, idle clips: `claudia.web.face.mind`,
    `clips`) stayed; the cute effects (hearts, sparkles, notes) show nothing now. Instead of: a
    second picture with the glasses pulled down and drawn eyes following the mouse (built, then
    dropped by the user the same day), a 3D model (built from code: far below the painting; an
    image-to-3D tool is the way if it comes back), words in bubbles beside her, a spark drawn by the
    image model (it never got the logo's shape; the real path is drawn on top).

50. Claudia's own chat wears her look (2026-11-18, the user's call): every chat has a colour; hers
    is a gradient instead, deep green at the top to near black at the bottom, over the whole window
    (the rail, the chat and the picture are see-through over it), with her own green theme whatever
    colour her row has (`:root[data-claudia]`, set while `:open-claudia?`). Behind everything a few
    faint columns of code fall (`claudia.web.views.claudia-rain`: 9 columns, 6 glyphs each, at most
    9 % opacity, about eight frames a second, still when the reader asks for less motion). Instead
    of: a flat colour like every other chat, or a dense rain that would fight the text.

51. Every server runs chats as an agent user, never as a person (2026-11-18, the user's call after a
    security review: a tricked agent running as a person with passwordless sudo is root on every
    machine that person's SSH keys reach). On each Linux server a profile's host and runner run as
    `<profile>-claudia-agent` (`paul-claudia-agent`, `peter-claudia-agent`, `ananda-claudia-agent`), a
    plain user without sudo and without the person's SSH keys, files or logins. The chats' folder,
    Claude Code's sessions and the runner's data move from the person's home to the agent user's
    (`host/` moves them once). The people keep their own Linux users and logins for work by hand.
    Root comes only through decision 52, other servers only through decision 53. Instead of: the
    person's own user (`user=<person>` in `/etc/claudia/profiles`), or `claudia-<profile>` (the old
    default name, which does not say it is an agent). The desktop (single-user today) moves to agent users too, with the agent users given its
    person's screen and sound by ACLs on the Wayland and PipeWire sockets (the user's call); the
    MacBook stays single-user (macOS has no shared mode in Claudia yet).

52. Root only through the root helper (2026-11-17/18, the user's picks: the root switch design).
    `claudia-root` runs as root on every server and listens on one socket per agent user,
    `/run/claudia-root/<user>/root.sock`, in a folder only that user may enter, so the socket proves who
    calls. The helper and the shim are copied to `/usr/local/lib/claudia-root/` by `host/`
    (decision 54), never by the automatic update, so the guard changes only when a person applies it.
    A `sudo` shim comes first on the sessions' PATH, so Claude keeps typing `sudo`; it sends the
    command, its folder and the chat's key (`CLAUDIA_CHAT_KEY`, set per session by the host) and
    streams the output back. The proof of the person's word lives in the front (user claudia, which
    no agent user reaches: every call to it needs the hub key, decision 47), never in the runner,
    which runs as the agent user and could say anything: the switch is kept by the front, the person's
    Allow and switch go to the front, the helper asks the front on a socket only root opens, and the
    front takes an Allow only from the owner for the exact command it asked about. The runner only
    names the chat for a key and shows the card, so a tricked agent can at worst point its command at
    another chat of its own profile. The helper runs it when it matches the no-tap list exactly
    (`/etc/claudia/root-no-tap`: restart and status of known services, journalctl, nft list ruleset,
    ss -tlnp; never file writes, unit edits, package installs or shells), or when the chat's root
    switch is on (a red badge on the chat, on until the person turns it off), or when the person taps
    Allow on the "Run as root" card for that one command (unanswered in 10 minutes = no). Since
    decision 79 turning the switch on and Allow need the owner's passkey. The chat's
    owner may always turn root on or tap Allow. Every root command is logged (decision 59) with the chat and the
    answer. The key ties a command to a chat; processes of one agent user can read each other's
    environment, so the user boundary, not the key, is what stops an escape. Root is the whole
    machine: other people's services included (the user's choice, decision 4 of the design). Instead
    of: passwordless sudo, or a root shell per chat.

53. Servers reach each other with one-hour SSH certificates (2026-11-18). The hub holds an SSH
    certificate authority (`HUB_SSH_CA_FILE`, ed25519, signed with sshpk; `claudia.hub.ssh-ca`). The
    front asks for the certificates (`claudia.slave.sshfront`), because it runs as user claudia and
    holds the server's hub key, which no agent user may read: on start and every 45 minutes, for each
    profile whose Linux user in `/etc/claudia/profiles` (`user=`, never a runner's word) is an agent
    user, it asks that profile's runner for its public key (the runner keeps an ed25519 key in
    `~/.ssh/claudia_agent`, made the first time), asks the hub for the certificate (`POST
    /hub/server/ssh-cert`, with its server's name and hub key as in decision 47; a failure is logged
    and tried again in 5 minutes), and hands it to the runner, which writes it to
    `~/.ssh/claudia_agent-cert.pub` and keeps a managed block in `~/.ssh/config` (`Host *`: that key
    and certificate, `IdentitiesOnly`, `User` the same agent user), so `ssh <server>` lands as the same
    agent user (`claudia.slave.ssh-cert`). The runner never calls the hub for it. The hub signs only for
    `<profile>-claudia-agent` when that profile name is a person's in the People table (a Profile
    column, set by an admin or in `hub.json`) and that person has a profile on the asking server.
    The principal is exactly that name; valid from 5 minutes before to 60 minutes after signing; key
    id `<server>/<user>/<time>`, which sshd logs; `permit-pty` only, no agent, port or X11
    forwarding. Every server trusts the authority (`TrustedUserCAKeys` from `GET /hub/ssh-ca.pub`,
    `AuthorizedPrincipalsFile /etc/ssh/claudia-principals/%u`, an agent user's name only), so an
    agent lands on another server as its own agent user, where sudo meets that server's root
    helper. No agent holds a long-lived SSH key. Instead of: the person's keys (the hole decision 51
    closes), keys copied to every server per agent user, or the runner asking the hub itself (it runs
    as the agent user, which would then hold a key that speaks as the hub's server).

54. The host standard is Ansible in `host/`, applied by a person, never by `deploy/update.sh`
    (2026-11-18). One playbook makes every Linux server the same: the agent users, `break-glass`,
    the root helper and its no-tap list, the sudo shim, the SSH certificate trust, sshd without
    passwords, etckeeper. It lives in this repo, so it changes with the code, but the automatic update
    never runs it: a merged mistake in Claudia cannot loosen a server's guard. A run arms an undo
    first (`systemd-run`, back to the etckeeper commit before the run after 10 minutes) and is
    confirmed at the end; an unconfirmed run undoes itself. Phase B (`phase_b_remove_nopasswd`)
    removes the people's passwordless sudo, only after a week of the new setup and the user's go. The
    inventory (servers, people, addresses) stays outside the repo. Instead of: Claudia's own update
    setting up its host (the guarded program writing its own guard), or NixOS (a reinstall of every
    machine).

55. `break-glass` is the way in when Claudia is broken (2026-11-18). A user on every server with no
    password, one SSH key kept in 1Password on the user's devices, and full sudo; used by hand only,
    never by an agent. It is tested on a server before Phase B runs there.

56. Replaced by decision 66 (2026-11-19). 1Password from any device (2026-11-18, the user's call; replaces decisions 1 and 7 of
    [plan/1passwd-plan.md](plan/1passwd-plan.md)): a read-only 1Password service account for one
    vault, "Claudia", which holds only machine tokens agents use, never people's logins. Its token is
    kept encrypted under a key that only the person's passkey can produce (WebAuthn PRF), so a
    server alone cannot read the vault: each read is an Allow plus Face ID on the iPad, iPhone or
    desktop, and opens that chat's reads for an hour. Not built with decisions 51 to 55; a track of
    its own.

57. Each person's chats run on the token that person picked last (2026-11-18, the user's call after
    a shared default broke everyone's new chats: one person set the shared token in use to their
    account, Anthropic refused that account, and every person's new chats failed). There is no
    shared default token any more, and nothing about one in Settings. A token is chosen only in a
    chat's header (decision 16). A new chat, and any chat without a pick (or whose pick was
    removed), runs on the token its owner (the chat's profile's person) picked last in any chat's
    header, on any server; the hub keeps that per person (`claude_token_picks`) and writes it to
    each server where the person has a profile (`<token file>.picks.json`, by profile; `PUT
    /api/claude-token/picks`). Before a person has ever picked, it is the saved token whose key is
    their profile name (decision 51), if one is saved; else the chat has no token, its header says
    "Pick one", and its message gets a clear error asking for a pick. A new chat takes its pick on
    being made and keeps it, so a later pick elsewhere moves only chats without one; it no longer
    carries the last chat's subscription. One person's pick never changes another person's chats:
    a pick in another person's chat (a write tick) moves only that chat. Work without a chat (the
    limits probe, Claudia's own chat) runs on the runner's profile's person's pick. Saving a token
    switches nothing; any saved token may be removed. On the first start of this hub nobody's old
    default is carried over: the shared one (`claude_token_use`) is dropped, and each person starts
    on their profile-named token if saved. Each server's old shared token file goes on the first
    picks write. Instead of: one default for everyone, which one person could set to a token that
    then failed for all.

58. Settings is a small popup with only the Claude tokens and GitHub (2026-11-18, the user's call):
    centred on a desktop and an iPad, a sheet from the bottom on a phone; Escape, a click outside or
    × closes it; opened as before (the menu's plan card and More settings, the iPad edge's cog).
    It holds the saved Claude tokens with add and remove (decision 57: no default) and GitHub as
    before (decision 23 or 37), plus a one-line Admin link for admins, since People and Servers
    have no other way in. The brand (wordmark, version, the token chip), My words, the all ~/Work
    sessions switch, the Admin and Servers rows, and the server tabs with "Signed in as … profile
    …" left Settings; their features stay (My words on the iPad edge, Admin through the link).
    Instead of: Settings as a full pane over the list or in the chat column (decision 24).

59. One activity log agents can't edit (2026-11-18, the user's call). Every action can be traced:
    who typed (the person), whose chat (the profile, whose name it acts in), which agent Linux user
    ran it, and what it did. Isolation inside a desktop does not matter; the record does. The log is
    journald, one line per action, `activity {json}` (`claudia.shared.activity`). A line counts by
    journald's trusted fields, never by its text: the kernel stamps `_UID` and systemd stamps
    `_SYSTEMD_UNIT` from the sender's cgroup. A session's tool processes run under
    `claudia-host@<profile>`, so they cannot write a line that shows under `claudia-runner@<profile>`,
    `claudia-front` or `claudia-root`; an `activity` line from any other unit does not count.
    - `claudia-runner@<profile>` (the agent user): every tool call it sees in a session's stream,
      sub-agents' included, `{kind:"tool", chat, profile, agentUser, tool, id, agent (the sub-agent's
      call, when one), input}` (input short: the Bash command, the file path, the URL, the pattern;
      else the input as JSON; at most 500 characters); every tool result `{kind:"result", chat, tool,
      id, ok, exit}` (exit: a Bash command's exit code when known); every message a person sends,
      new chats and Claudia's chat operations included, `{kind:"message", chat, profile, by, via}`.
      Authoritative for what the agent did: no tool process writes as this unit, as long as the
      kernel forbids ptrace of a process that is not one's child (Yama `ptrace_scope` 1 or more;
      the runner and the tools run as the same agent user).
    - `claudia-front` (user claudia): `{kind:"connection", by, profile, via}` for each WebSocket it
      passes on (it passes the frames untouched, so it logs the connection, not each message), and
      `{kind:"call", by, profile, via, method, path}` for each HTTP call other than GET and HEAD it
      passes on (Claudia's chat operations included); the person's root taps `{kind:"root", chat,
      profile, agentUser, argv, decision ("allowed", "denied", "switch on", "switch off"), by}`; each
      SSH certificate it hands to a runner `{kind:"ssh-cert", agentUser, keyId}` (the key id sshd
      logs, decision 53). Authoritative for who: `by` is the email the hub vouched for (decision 47),
      `via` is `own`, `write-tick` or `view`.
    - `claudia-root` (root): each root command `{kind:"root", chat, profile, agentUser, argv,
      decision ("no-tap", "switch", "allow", "refused: …", "gone before the answer"), by, exit}`;
      `by` is the person who allowed it (the Allow tap, or who turned the chat's switch on), as the
      front told the helper on root's own socket. argv keeps at most 64 words of 500 characters.
      Authoritative for what ran as root.

    Who typed a message: the front sets `X-Claudia-By` and `X-Claudia-Via` on what it passes to a
    runner and drops every `X-Claudia-*` a caller sent, so nothing arriving through the front names
    another sender. The runner's `by` repeats that. An agent user can reach its own runner's socket
    without the front, and such a message's line can say anything, so the runner's `by` counts only
    with the front's `connection` or `call` line by that person for that profile. Reading:
    `journalctl -o json -u claudia-front -u 'claudia-runner@*' -u claudia-root | jq 'select(.MESSAGE|startswith("activity "))'`,
    keeping only lines whose `_SYSTEMD_UNIT` is one of those units (`select(._SYSTEMD_UNIT ==
    "claudia-front.service" or ._SYSTEMD_UNIT == "claudia-root.service" or
    (._SYSTEMD_UNIT|startswith("claudia-runner@")))`) and reading the entry after the prefix
    (`.MESSAGE[9:]|fromjson`). This holds for the system units (`deploy/shared-server`); programs
    run as one person's user units (`journalctl --user`) have no such record, since a user may move
    its own processes between its own units' cgroups. How long the journal keeps the lines is set
    by `host/`. Instead of: a log
    file per program (a file the agent user could reach or fill), or the runner's word alone for
    who typed (it runs as the agent user).

60. Prompt profiles (2026-11-18, the user's call). A server keeps named prompt profiles (name, at
    most 60 characters; text, at most 20,000), added, edited and deleted in the Prompts window
    (the prompt popup's "Edit profiles…"). A chat picks one in its header beside model and effort:
    a button names the pick ("Prompt: Reviewer", or "built-in"), and a click opens a popup with
    the locked built-in part on top (a summary; Show full text gives its exact words), then None
    and the profiles with their first lines, then Edit profiles… (`settings.prompt`; a new chat carries the last chat's pick like its model). The
    profile's text goes after Claudia's part of the system prompt (headed `# Prompt profile:
    <name>`), so the picture, secrets and folder rules stay; Claudia's own chat still gets her
    part last. A live chat takes a new pick, or a change to its profile's text, after its turn and
    the work it started: its session starts again resumed, same conversation (the prompt's
    version is per chat). A deleted profile leaves its chats on no profile. Instead of: a profile
    that replaces Claudia's part (a profile without the drawing rules leaves the picture empty
    and the 400-character stop hook still runs) or the whole system prompt (it drops Claude
    Code's own tool and safety guidance).

61. Bookmarked folders live on the hub, per person (2026-11-18, the user's call: many folders on
    many machines, kept tidy from one place). A bookmark is `{ server, folder }` (an absolute path)
    with an optional label; the hub keeps each person's list like the chat order (`bookmarks`,
    keyed by email; `GET`/`PUT /hub/bookmarks { items }`), cleaned by `claudia.shared.bookmarks`:
    absolute paths only, no `.` or `..` parts, each server and folder once, labels of at most 80
    characters, at most 200. Every device shows the same list. In the Folders modal one click
    pins: the ☆/★ at the right end of every tree row, the ★ on a bookmark card (unpin) and Pin/Unpin
    in the folder's panel; the modal shows that server's bookmarks as cards on top.
    A bookmark whose folder is gone shows greyed as "missing" (the runner's `POST
    /api/folders/missing`).
    Instead of: bookmarks per device in the browser's storage, or per server in each runner.

62. The folder tree is react-arborist (2026-11-18, the user's call): the npm library (4.0.1, with
    its peers react-dnd and react-dnd-html5-backend 16) used from Reagent with `:>`, rooted at "/"
    with the work folder's path open at first, each folder's sub-folders loaded when it is first
    opened (`GET /api/folders`). Only the rows are ours; arborist keeps the open state, the keys
    and draws only the rows on screen; its filter keeps the loaded folders whose name holds the
    filter field's text, and a pasted or typed absolute path (Enter) is revealed instead. Dragging
    and renaming are off. Everything else in the Folders modal (the bookmark cards, the detail
    panel, the buttons, the Trash list, the dialogs) is our own Reagent in the chalk look
    (`claudia.web.views.folders`, `.folder-tree`, `.folder-detail`, `.folder-trash`;
    `resources/web/css/folders.css`). Where it opens (2026-11-18, the user's call): the folder pill
    in the chat header's row 2 (decision 22) and the chat menu's Folders…, for a new chat and a
    started one, not while only looking at another person's chats; a big modal over the page on
    that chat's server, with the chat's folder (else the work folder) selected and revealed in the
    tree. On top that server's bookmarks as cards:
    the project's logo as the chats show it (`GET /api/project`, `/api/project/logo`; a folder icon
    when none), name, path, ★, "missing" greyed; a click highlights the card and reveals and
    selects its folder in the tree; each card has its own Select. Select, and Use for this chat in
    the selected folder's panel, sets the chat's folder and closes the modal: a new chat's folder
    pick, or a started chat moved there (the `folder` frame). New chat here stays a separate
    button; Escape or × closes the modal, and New chat here or a click on one of its chats closes
    it too. Tree rows show no logo (asking each
    visible project for its logo is one call and one logo search per row).
    Instead of: a tree of our own.

63. Replaced by decision 69 (2026-11-19). Deleting goes to Claudia's own Trash per server (2026-11-18, the user's call). Move to Trash
    moves the folder to `RUNNER_DATA/trash/<id>/<its name>` with a row in the runner's store
    (`trash`: where it was, when, its size, who); `fs.rename`, and across filesystems (EXDEV) a
    recursive copy, then the original removed (a whole copy whose original could not all be removed
    stays in the Trash and the error says so). Restore moves it back (its parent made when gone) and
    refuses while anything sits at the old path. The runner purges items older than 7 days, at start
    and every hour. Delete now skips the Trash. Both ask first in a dialog naming the full path (symlinks
    followed), the size and the git work that exists only there: files not committed, commits ahead
    of the upstream, local branches without a live upstream, in the folder's repo and in repos up to
    four folders below it (`GET /api/folders/facts?deep=1`). The Trash view lists each item with
    Restore and Delete now, and Empty Trash with the total size. A size is `du -s` (`-sb` on Linux,
    `-sk` on macOS) run in the background with a 5-minute limit and kept 10 minutes; the page asks
    again every 2 s while it is measured. Instead of: deleting at once.

64. Its never-deleted list replaced by decision 69 (2026-11-19). The folder browser reaches the whole system (2026-11-18, the user's call). It shows whatever the
    runner's user may read and deletes whatever that user may write: the OS permissions decide, and
    EACCES or EPERM come back as "Permission denied" (403). A profile's home folder does not limit
    it. Dot folders are hidden behind a "show hidden" switch. Whatever the permissions, the runner
    never deletes "/", any top-level folder (`/etc`, `/usr`, `/var`, `/home`, `/root`, `/tmp`…, every
    folder right under "/"), macOS's `/private/*`, the home folder, the work folder
    (`CLAUDIA_CWD`) or any folder above them, and `RUNNER_DATA` or Claudia's install (the folder
    holding the running `out/`), inside them or above them (`claudia.slave.folders.rules`). Every
    path is resolved (`..`, every symlink followed) before any check; the tree lists real folders
    only, not symlinks to them. The calls are the profile's own person's only: the front refuses
    `/api/folders…` (403) to a view and to a write tick, and the runner refuses any call whose
    `X-Claudia-Via` is not `own`; a read-only view keeps its limits (`claudia.slave.reach`).
    Instead of: the browser kept inside `CLAUDIA_CWD` or the home folder.

65. (Replaced the same day by decision 74: a new chat starts in the last chat's place.) A new chat starts with no folder picked (2026-11-19, the user's call: a chat that silently took
    the folder of the chat on screen, or the one this browser remembered, worked in the wrong
    project). A new chat no longer copies the open chat's folder, the last new chat's, or a chat
    group's; the folder is not among the carried picks (`claudia.web.events/carried-keys`) and not
    in what `claudia.newPicks` keeps. Its header's folder pill says "Pick a folder". Sending its
    first message without a folder picked sends nothing: the draft stays and the Folders modal
    opens on its server; Select there (a bookmark card's or Use for this chat) picks the folder and
    then sends the draft; closing the modal leaves the draft as it was. A folder still comes with
    a fork (its source's), Folders' New chat here, and Claudia's chat operations' `new { folder }`;
    a `new` without a folder still runs in `CLAUDIA_CWD` (the runner is unchanged). Instead of: the
    folder carried from the chat on screen or the last new chat.

66. Secrets come from a key broker on the gate, approved with a passkey (2026-11-19, the user's
    picks one by one; replaces decision 56 and decisions 1 to 3 and 7 of
    [plan/1passwd-plan.md](plan/1passwd-plan.md)). One central place holds the keys, so the
    servers are only workers. The keys live in one 1Password vault, "Claudia" (machine tokens only,
    never people's logins). Only the key broker reads it (`src/claudia/broker`, `out/broker.js`),
    with a read-only service account limited to that vault (`BROKER_OP_TOKEN_FILE`, the official
    `@1password/sdk`). The broker runs on the gate from the hub's own image, as its own container
    and user, beside the hub and not inside it. It listens on a unix socket only the hub's
    container reaches, and the hub never holds the token. Every read needs a fresh passkey
    signature. The page asks the broker for a challenge bound to that read (reference, the
    runner's one-time key, the ask), the person signs it with Face ID, Touch ID or a PIN, and the
    broker checks the signature (`@simplewebauthn/server`, user verification required, each
    challenge used once, 5 minutes). Only then does it read the value and seal it to the runner's
    one-time key (libsodium, as before), so the hub and the page carry only ciphertext. Passkeys
    are Claudia's own, for the hub's host: not the gate login's (Pocket ID's), so a stolen login
    cookie approves nothing. Any provider works (iCloud Keychain, 1Password, Google, …; no
    attestation, no provider list), and a person keeps several, any one of which approves
    (Settings → Passkeys). The first needs a one-time code made on the gate (`broker.js enroll
    <email>`, 30 minutes, 5 tries). Every later one, and removing one, is approved by a passkey
    already kept. The iPhone and iPad app carries `webcredentials:` for the hub's host as well
    as Pocket ID's. The runner's own `op read` on a desktop (the vault server,
    `CLAUDIA_OP_ACCOUNT`, `CLAUDIA_VAULT_SERVER`) is gone; pasting a value still works. What a
    hacked hub can still do: it serves the page, so it can show one request and have the broker
    approve another, once per tap. It cannot read the vault without a tap. Instead of: the token
    sealed under each person's passkey with PRF (decision 56: Claudia would own recovery), the
    hub reading 1Password itself (a hacked hub would read the whole vault), a proxy that makes
    the calls with the keys (HTTP only), or a hardware SSH key (the user has none and wants none).
    Who may use which key (2026-11-19, the user's call): the broker keeps its own admins (`broker.js
    admin <email>` on the gate; never the hub's word) and a tick per key and person. Admins use
    every key; anyone else only the keys ticked for them, checked before the passkey asks and again
    at the read. Settings → Keys shows admins the vault's keys (titles only) and the people; ticks
    change on the page and Save sends the batch, approved with the admin's passkey.
    One tap per key per chat for an hour (2026-11-19, the user's call: every key the same, no
    per-key setting): after a passkey approves a read, that chat may read that key again for an
    hour without a tap; a different key, or another chat, asks again. The broker keeps these hours
    in memory only (a restart asks again). A secret card first asks the broker for a read within
    the hour and falls back to the passkey.
    Next, not built here: the gate's SSH login as `core` by a 5-minute certificate the broker
    signs after a passkey (after a tested `break-glass` user on the gate, decision 55).
67. Agents keep bookmarks too (2026-11-19, the user's call: "build it"). Every chat on a runner
    that reaches the hub as its owner (the settings of Claudia's own chat, decision 48:
    `CLAUDIA_HUB_URL`, `CLAUDIA_SERVER_NAME`, `CLAUDIA_HUB_KEY_FILE`, `CLAUDIA_OWNER_EMAIL`) gets
    two workspace tools: `list_bookmarks` and `bookmark_folder { folder?, server?, label?, remove? }`
    (`claudia.slave.bookmarks`). They change the same per-person list the Folders modal pins
    (decision 61), through `POST /hub/server/claudia` ops `bookmarks` and `bookmark`, so the gate
    passes no new path. On this server the folder may be `~/…` or relative to the chat's folder
    and must exist; on another of the person's servers it must be absolute. The system prompt
    says to bookmark only when asked. The Folders modal loads the list again whenever it opens, so
    an agent's change shows there at once. Instead of: a new hub path (one more gate rule for the
    same caller), or the runner writing the page's `PUT /hub/bookmarks` (it holds no login).

68. The Folders modal has three panes: tree, contents, preview (2026-11-19, the user's pick of three
    layouts). Under the bookmark cards: the tree on the left, in the middle what the selected
    folder holds (folders first, then files), as a grid (images as their own thumbnails up to
    8 MiB, other files as their extension's tag) or a list (name, size, modified), the choice kept
    in the page's storage; on the right a quiet pane: the picked file previewed (images, video and
    sound to play, PDF in the browser's viewer, Markdown rendered, any other file up to 2 MB read
    as text and coloured as the files form colours code), or with nothing picked the folder itself
    (its facts in label-value lines, the actions, the recent chats, its README). A click picks, a
    second click on the same folder within 450 ms, or Enter, opens it (timed by the page, not the
    browser's dblclick: the first click redraws the panes, and touch screens send none). The runner lists a folder's contents
    (`GET /api/folders/contents`, at most 5000 entries) and sends one file's text or bytes
    (`GET /api/folders/file`): bytes only for the kinds it previews, in byte ranges, under a CSP
    that runs nothing, so an HTML or SVG file never runs as a page. Instead of: a Quick Look
    overlay over a full-width grid, or the preview under the contents.

69. Folders never delete (2026-11-19, the user's call: "don't delete files, Claude can do it
    anyway"; replaces the Trash and Delete now of decision 63 and the never-deleted list of
    decision 64). The Folders modal has no Move to Trash, Delete now, Trash view or delete dialog,
    and the runner has no call that deletes, moves or trashes (`/api/folders/trash`, `delete`,
    `restore`, `purge`, `trashed` and the facts' `deep`, `kept` and `inner` are gone). To delete a
    folder, the person asks Claude in a chat, where the step shows in the conversation and the
    activity log. Folders trashed before stay in `RUNNER_DATA/trash/` until 7 days after they were
    trashed, when the runner's hourly purge removes them; then the Trash is empty for good.
    Instead of: a file manager's select-and-Delete with a Trash.

70. Files lives in the right half, toggled from the header (2026-11-19, the user's call: "Files is
    so good that I want it instead of the picture, with a toggle; the button stays where it is").
    The folder pill in a chat's header (row 2) no longer opens a modal: a click opens Files in the
    right half, or closes it when it shows (since decision 90 the Files button in row 3 does, filled
    while Files shows; a sheet on the Drawing board since decision 91). Files is the same
    browser as before (bookmark cards, tree, contents as grid or list, preview), on the open
    chat's server with its folder (else the work folder) selected, started again when another chat
    opens; Select works on the open chat. The choice is kept per person on the hub (decisions 75
    and 90). The chat menu's Folders… is now Files…: it opens that chat with
    Files. A new chat's first message without a folder (decision 65) opens Files instead of the
    modal; Select sends it and Files' sheet closes. A read-only view shows no Files.
    On a phone, Files brings the right half up. Instead of: the Folders modal over the page, or a
    Picture | Files switch in the right half's bar.

71. Its one pill split again by decision 90 (2026-11-21): a server pill and a project pill, and
    Files a button in row 3. The computer and the folder are one pill, and Files picks the computer (2026-11-19, the user's
    call: "project and server pair needs to be one; both should open the file browser; the file
    browser should have a server toggle"). Row 2's computer pill and folder pill became one place
    pill, "destiny ~/Work/claudia", and a click toggles Files (decision 70); the computer popup is
    gone. Files' top bar names its computer and, under it, one quiet line of that computer's
    facts (system, kernel, processor, memory, uptime, from `GET /hub/servers/<server>/system`). For
    a new chat with more than one computer the name is a toggle of the person's computers (an off
    one dimmed): a pick makes it the new chat's computer, drops the folder picked on the one before,
    and starts the browser again there with that computer's own bookmarks (decision 61). A started
    chat's computer stays fixed (decision 72: the toggle browses another computer). So a new chat's first message without a folder (decision 65)
    opens Files, where the person picks the computer and the folder, and Select sends it. Instead
    of: a computer pill with its own popup beside the folder pill.
72. The File Browser browses any computer, also for a started chat (2026-11-19, the user's call:
    "the title should be File Browser and we should be able to toggle the server"). The right
    half's heading says File Browser. For a started chat the top bar's toggle lists all the
    person's computers too, the chat's own marked "· this chat"; a pick only browses that computer
    (its work folder, its own bookmarks): the chat stays on its own computer. While another
    computer is shown, a bookmark's Select and the folder's Use for this chat are gone (a chat
    cannot move to another computer); New chat here and Pin stay. Opening the browser again, or
    another chat, starts on the chat's own computer. Instead of: a started chat's computer fixed
    in the browser.
73. The mic rail and the half flower (2026-11-19, the user's call: "a second sidebar on tablets
    where the microphone button would be ... it would touch the wall"; "the buttons should make up
    one line and the microphone button should be in the center"; "drag ... out of the inner and outer
    circle ... would move the circle towards my finger"; "drag should drop the recording"; "kept
    per device"; "on mobile it stays the same"). On a touch tablet a rail 88 px wide stands on the
    right wall with the page beside it, never under it: one column, the mic in its middle at
    mid-height; over it Search, Machines, Prompts, My words; under it Stop, New chat, ‹, › (since
    2026-11-21, decision 92: four slots each side, none coming or going; before: over it search,
    My words, archive, Stop, each shown only when it could act; under it New chat, then ‹ and ›,
    2026-11-19 later: "below the mic should be the plus, the left and right below them"); at its
    bottom the foot the chats' rail had on the left, the update pill, your profile circle (its menu
    opening to the left holds Settings, so the rail has no cog) and the version's line ("so the
    chats have more place"). A desktop keeps them at the chats' rail's foot. Held on the rail, the flower is half: its petals and the
    voice's bars only to the left, still centred on the mic, top to bottom cancel, add, send, now,
    each a quarter of the left half; in place or to the right sends. Round the petals a ring
    (124 to 148 px from the mic's centre, all the way round) says move (2026-11-20, the user's call;
    before: an unseen flick past 130 px in the first half second of a hold): the finger reaching
    the ring at any time while holding keeps the recording going (2026-11-20 later, the user's
    call; before: the clip was dropped) and the mic follows the finger on a spring
    (`motion`'s useSpring, 2026-11-19 later: "it should be more smoothly following ... like it would
    have some personality"): it trails the finger and overshoots a little, the whole flower still
    there and recording (petals, bars, the red disc with its clock), the ring lit round it
    (2026-11-20 later, the user: "it doesn't show the recording anymore" and "the petals disappear
    while moving ... I could choose again to cancel"; before: a blue disc saying move). When the
    finger rests (pure/settle-ms, 350 ms, within 8 px) the mic is put down under it and the hold
    chooses again from there: a petal, letting go in place sends, the ring moves on. Let go while
    still moving, the clip is heard and sent as when let go in place, and the mic glides there and
    stays, its slot in the rail a dashed ring (since 2026-11-21 a "↺ mic back" button, decision
    92). It may
    sit anywhere its whole button is on the screen, the rail included (2026-11-20; before: 124 px
    from the walls and the rail so the whole flower fitted). It glides home only when the button
    let go covers half its slot's area or more (two circles' overlap; before: within 40 px of the
    slot or anywhere on the rail, and "it jumps too easy back on its place" before that). Out of
    the rail its flower is whole, four ways ("when it's not in sidebar, it should already be in
    flower with the four sides"); on the rail, half. Near an edge the flower stays centred on the
    mic and is cut off by the screen: a petal or part of the ring past the edge cannot be reached,
    letting go in place still sends, and the mic can be moved away through the ring's other side.
    Its spot is kept on the device (localStorage, as fractions of the screen, clamped again when
    the screen turns so the button stays on it). The phone keeps its mic in the bottom middle with the whole flower and no
    move. Instead of: the 3x3 grid floating 18 px from
    the edge over the page, the mic 128 px in so the whole flower fitted.
74. A new chat starts in the last chat's place, and the File Browser has no folder section
    (2026-11-19, the user's call: "new chat should start last chat"; "remove the three buttons ...
    I want to remove that section"). Replaces decision 65. New chat (and a group's +) starts on the
    computer and in the folder of the chat open (its own folder, else that computer's work folder),
    or with no chat open in the last new chat's (`claudia.newPicks` keeps the folder again); the
    header's project pill shows it (decision 90), and the File Browser changes it while working (a bookmark's
    Select). A new chat that still has no folder (a fresh browser) asks for one on its first
    message as decision 65 said. The File Browser's right pane shows only the picked file's
    preview, else the folder's README: the folder section (its name, facts, Use for this chat, New
    chat here, Pin, recent chats) is gone, and with it the runner's `GET /api/folders/facts` (the
    folder's size by `du`, its chats, its git summary). Pinning stays on the tree's ☆ and the
    cards' ★. Instead of: a new chat with no folder, and a folder section under the preview.
75. The right half's views, kept per person (the dropdown and one view at a time replaced by
    decision 91's sheets, 2026-11-21) (2026-11-19, the user's call:
    "Search should do too. Settings too. All the views that we have in pop up should do this"; "a
    drop down so we could change between settings, file browser, and all the other windows"; "the
    drawing table or file browser, whichever is chosen, that should be per profile"). The right
    half shows the Drawing board (Claude's picture) or one of File Browser, Search, Settings, My
    words, Machines, Prompts, Forms (`claudia.shared.views`; since decision 91 as sheets on the
    board, no dropdown). The buttons that opened pop-ups open the view instead (the header's Files
    button toggles Files, decision 90; the edge's Search, Machines, Prompts and My words open
    theirs, decision 92; ⌘K, the menu's Machines, Forms, Prompts, My words and More settings, a
    prompt pill's edit). What shows is the person's, on
    the hub (`GET/PUT /hub/right-view`, table `right_views`), so every device shows the same; a
    read-only view of another person's chats shows no File Browser. Search stays beside the chat a
    find opens. On a phone a view brings the right half's screen up. Small pop-ups tied to a button (the header pills, menus, the
    confirm card) stay; image zoom stays full screen. Instead of: Search and My words over the
    dimmed page, Settings, Prompts and Forms as centred windows, Machines in the menu's place, and
    the right half's view kept per browser.
76. Claude's questions sit in the chat (2026-11-19, the user's call: "even questions should be in
    the chat"). A question, a secret card and an OK card follow the chat's last message, the chat
    following them to the bottom, while the right half keeps its view beside them; the keys stay
    (1-9 pick, Enter answers), and a message typed in the composer still answers the first
    question. Instead of: a modal over the whole window with "See picture" and a pill to bring it
    back.

77. Agents list the keys they may use (2026-11-19). Agents had to guess `op://Claudia/<item>/<field>` for `run_with_secret`, and guesses hit
    "no such item". Every chat now has the workspace tool `list_secrets` (no arguments): the items
    of the vault the chat's person may use (decision 66: admins every key, others the ticked ones),
    each with its fields' labels, their sections' names and a ready reference, never a value. The
    key broker answers `/keys/mine` (hub `GET /hub/keys/mine`, the email the login vouched for)
    without a passkey tap: it shows only names, and only of keys the person may already use. Field
    labels need one 1Password item read per item (one `getAll`); the broker maps each item to ids
    and titles at once, so no value reaches the answer, a log or its cache, and reads an item again
    only when it changed. The runner holds no login, so it sends `keys_ask` to the person's open
    pages (every page of that runner that is not read-only, the chat open or not); the first page
    fetches the list and answers `keys` with no card to press. No page connected, or none answering
    in 60 seconds, no key broker, or a broker without a token: the agent gets a refusal that says
    which. The prompt's Secrets section tells agents to call `list_secrets` before
    `run_with_secret`. Instead of: the runner calling the hub itself (it has a hub key only on the
    server with Claudia's own chat, decision 48, and no person's login), or the agent reading the
    vault with `op` (decision 66 took the token off the servers).

78. GitHub for services through the hub's GitHub App (2026-11-19, the user's call: "move all
    three"): a program that is no person (a bot, CI slots) gets 1-hour installation tokens of the
    app, never a personal token. The hub signs as the app (`HUB_GITHUB_APP_ID`,
    `HUB_GITHUB_APP_PRIVATE_KEY_FILE`, from the gate's secrets), asks GitHub for a token that reaches
    only the repositories named for that service (`HUB_GITHUB_APP_SERVICES`:
    `<service>@<server>=<owner>/<repo>,…|<permission>:<read|write>,…`) with only that service's
    permissions, replaces it 15 minutes before it runs out, and hands it to
    the service's server (`PUT /api/github/service-token`, the hub's own call on the front). The
    front writes it to `FRONT_GITHUB_SERVICES_DIR/<service>/token`, only for a service whose folder
    root made (owner claudia, the service's group, setgid), so only that service reads it; what it
    does on GitHub shows as the app's bot. Nobody approves anything per token: installing the app on
    the account once is the only step. If the hub cannot reach GitHub or the server for an hour, the
    service has no valid token until it can. The app asks for Actions write (runs cancelled and
    dispatched), Statuses read, Administration write (runner registrations) and Secrets read (secret
    names) for this. Instead of: a fine-grained personal
    token on the server's disk, long-lived, acting as the person.

79. Root needs the owner's passkey, through the key broker (2026-11-19, the user's call: "turn it on
    with the passkey", root stays on until turned off; the broker checks it, not each server).
    Turning a chat's root switch on and every Allow on a "Run as root" card need a grant the key
    broker signed. The page asks the server's front for a one-time nonce bound to the profile, the
    chat, the action (`on` or `allow`) and, for Allow, the card's ask (`POST /api/root/challenge`),
    then the broker for a passkey challenge bound to that nonce (`kind: "root"`). After the passkey
    signs (user verification required), the broker signs the grant with its own ed25519 key
    (`BROKER_DATA/root-grant.pem`, `claudia.broker.grant`): who approved, the nonce, profile, chat,
    action, ask, 5 minutes. The front checks it with the broker's public key pinned in
    `/etc/claudia/env` (`FRONT_BROKER_GRANT_KEY`, placed by host apply, decision 54, never by the hub
    or the automatic update), uses the nonce once, and takes it only when the email is the target
    profile's email in the server's profiles file. So a stolen login cookie (no passkey) and a
    hacked hub (no broker key) give no root. Turning root off and Deny need no passkey; the no-tap
    list (decision 52) still runs without a tap. The challenge is fetched when the card appears and
    every 4 minutes, so the tap opens the passkey at once (WebKit). What stays open: whoever takes
    the broker too could sign grants; a chat already switched on stays root until it is turned off;
    a hacked hub serves the page, so it could show one chat and have the passkey approve another,
    once per tap (as decision 66). Instead of: each server checking the passkey itself (every new
    passkey would need host apply on every server), or a passkey per command while the switch is on.

80. Gradient chat colours beside the solid ones (2026-11-20, the user's call: "next to the solid
    colours, add gradient colours too, for extra"). Eight gradients, each between two crayons of the
    box (decision 26), top left to bottom right: berry (plum→cherry), ember (cherry→sunflower),
    sunset (tangerine→magenta), citrus (sunflower→kiwi), meadow (kiwi→jade), lagoon (aqua→ocean),
    dusk (sapphire→magenta), candy (bubblegum→periwinkle); picked so their middles are at least 0.1
    apart in OKLab (the closest, ember and sunset, 0.114). A gradient chat's window background runs between its two crayons, each as dark as a
    solid chat's background, with the rail, the list and the picture see-through over it (as in
    Claudia's own chat, decision 50). Its circle (rail, list, group stack, swatch, header dot) is
    chalked in the gradient (`--g-<name>`). The theme (chalk, accent, text, panels, lines) is mixed
    from one stand-in colour, the gradient's middle in OKLCH (`--c-<name>`), the same way a solid
    colour mixes it, so text keeps a solid chat's contrast; the stand-in also paints the robot in
    the world and the tints. The bell rings its first crayon's note. Both palettes (the header's
    colour pill, the chat menu) show the 20 solids (and ash), then the gradients on rows of their
    own. A chat's colour is still one name, so old rows stay valid and every server's
    `chat-colour?` (the runner's PATCH, a new chat's picked colour, the crayon-box move on start)
    takes the new names; the hub passes colours through untouched. (Since decision 89: 16
    gradients, and a new chat takes a gradient once every solid is worn by an open chat.) Before
    that a new chat (and a fork, a woken chat without a remembered colour) got a solid colour only: `most-different` scores
    colours by OKLab distance between single colours, and a gradient is picked by hand, like ash.
    A gradient chat counts as wearing both its crayons, so a new chat after it never takes either.
    Instead of: gradients in the random pick (two-colour distance has no meaning in the box's
    measure), or a gradient theme mixed from both hues per surface (text over a changing tint).
    Files: `src/claudia/shared/colours.cljc`, `src/claudia/web/views/pure/words.cljs`,
    `resources/web/css/claudia.css`, `resources/web/css/chatops.css`.

81. The rail widens into cards, shows each chat's server, and orders and groups by dragging
    (2026-11-20, the user's call: the server only in the hover text never shows on a touch screen;
    "rows must not move vertically when switching"; drag-to-group on the rail as in the list). Three
    parts:
    - The server's mark: with more than one server, every circle on the rail (and every chat in a
      group's chooser card) carries its server's short mark at its bottom right, light on the rail's
      dark and ringed in the chat's colour, so it reads on every chat colour; a chat's root # moves
      to the circle's bottom left. A mark is the label's first letter, else its words' initials,
      else its first two to four letters, unique among the person's servers (Smoke and Second: Sm,
      Se); the list's and the header's server marks use the same ones
      (`claudia.web.views.pure.rail/server-marks`).
    - Wide rail with cards: the rail's right edge is dragged wider (a mouse or a finger) and the rail
      follows. From 170 px on, each circle becomes a card: the circle at the left, beside it the
      title on one line; the server (with more than one), the project or folder and the model; the
      last reply's line or what it works on; its state in words (running, waiting for answer, done,
      failed, stopped, new) with the running clock or how long ago. Let go, the rail snaps to the
      circles (88 px, under 170) or the nearest card width (260 or 340 px) that leaves the page 600
      px; the width is kept on the device (localStorage) and a window too small for it shows the
      widest that fits. Every row (a chat, a group, the chat being started) is the same height
      (`--rail-row-h`) on the circles and on the cards, so no row moves up or down when the rail
      switches. A group is a card too: its stack, its name, how many chats, their titles, its most
      pressing state; on the circles its line under the name says how many ask, else how many run,
      else how many chats. The runner's rows name the model (`model`, PROTOCOL.md): the one picked
      for the chat, else the one its session runs with, else the server's default.
    - Dragging on the rail: the list's dragging (decision 38, `claudia.web.views.chat-drag`) works
      on the rail's circles and cards and on the chats of a group's chooser card: the upper or lower
      third of a chat places before or after it, its middle makes a group, a group's stack takes it
      into the group (its edges: before or after the group), a chat in a group's card goes before or
      after it in that group (never a group in a group: the list's own middle drop on a grouped chat
      used to put it in two groups). Dragged with a mouse and let go outside the rail (over the page
      or outside the window), the chat tears out into a window of its own, as before (the copy under
      the pointer says New window); a finger never tears out (a tablet shows one window). The tear
      opens the window in the drag's own pointer events instead of the browser's drag and drop, so
      one drag does both; a blocked popup leaves the chat where it was.
    Instead of: the server only in the hover text, a fixed 88 px rail, and a rail drag that could
    only tear out. Files: `src/claudia/web/views/pure/rail.cljs`, `src/claudia/web/views/rail.cljs`,
    `src/claudia/web/views/rail_card.cljs`, `src/claudia/web/views/rail_width.cljs`,
    `src/claudia/web/views/rail_group.cljs`, `src/claudia/web/views/chat_drag.cljs`,
    `src/claudia/web/views/tearoff.cljs`, `src/claudia/slave/chats.cljs`,
    `resources/web/css/claudia.css`, `resources/web/css/chatops.css`.

82. A chat moves to another server (2026-11-20, the user's design). The chat menu's Move to
    server… (only on the person's own chats, not on a view or a write tick, not on Claudia's own
    chat, and only with another server where they have a profile) opens the confirm card: the
    server it goes to, the folder there (the chat's folder from the home folder, `~/…`, so it lands
    in the same place by default; empty: that server's work folder), and what stays: the working
    folder's files, running helpers and background work. The hub (`POST /hub/chat-move`,
    `claudia.hub.move`) drives both runners through their fronts as the person, over the hub's own
    channel (no new port): the target says the folder is there; the source holds the chat (its
    Claude Code closes, nothing is sent into it, for 15 minutes at most); the copy streams from the
    source into the target, the hub keeping none of it: the runner's row (title, colour, picture,
    talk and bell), its settings, context, last turn and agents' list, its pictures (its file
    changes too until decision 97), and Claude Code's session file with everything under `<id>/` (subagents, tool
    results), placed in the target runner's own project folder (its working folder's slug). The
    target reads its copy back and the hub checks it against the source: the same number of
    messages, the same last message, every file's size and SHA-256, as many pictures. Only then does the target take the chat in and the source archive its own, marked as
    moved (never deleted; a move back replaces that copy unless it was written to since), with its
    root switch turned off: the switch is the source front's, and a moved chat starts with root off
    and a new chat key. Any failure stops the move: the target drops its copy, the source keeps the
    chat, and the card shows why. A chat that works (a turn, agents or background shells) or waits
    on a question is refused, never stopped: stopping would end work the person did not ask to end.
    Claude hears of the move with the next message: "This chat moved from the server A to the server
    B. Its folder is now X on B … Paths and files in the earlier history are A's: the working
    folder's files, running helpers and background work stayed on A." (moved away and back before
    it heard of it: only where it works now). Unlike a folder move's reminder, the chat shows these
    exact words: as a note at the end of the chat until the next message, then as a note right
    before that message (`<claudia-moved>` in the message Claude gets, read back from the session
    file). Uploads, 3D videos and agents' shell output stay on the source too. Instead of: a new
    chat on the target with a summary (Claude would lose the conversation), copying by SSH between
    the servers (the agent users' certificates are Claude's, not the person's, and would open a
    path between servers), the hub keeping the copy (the gate's memory), or a hidden reminder (the
    person could not see what Claude was told).

83. Queued messages: shown once, taken back with ×, sent with now (2026-11-20, the user's call:
    "sending with now sends all queued messages now, together with the new one, in order").
    Merged: Claude Code takes messages queued during a turn in together as ONE user entry, their
    texts joined by line breaks, its uuid the last send's and `mergedSendUuids` the earlier ones'
    (Claude Code 2.1.326). The runner counts every id an entry stands for as in the session file,
    so those sends leave the sent-but-unsaved list (they stayed there and were shown again at the
    chat's end on every load), and the page shows the entry once with each send's voice
    (`Msg.merged`, PROTOCOL.md). Take back: a queued message has a × beside its tag (not for a
    read-only viewer); the runner asks Claude Code to drop it (the Agent SDK's
    `cancel_async_message` control request, `Query.cancelAsyncMessage` in the session host), and
    it leaves the chat when Claude Code says it dropped it. One Claude Code has taken in already
    stays (the × goes when it shows as taken in); one not in Claude Code's queue yet (still on its
    way) is dropped once Claude Code reports it queued. The session host learns the call only
    when it restarts (hosts never restart in an update): until then the × answers that the host
    needs a restart. Now: a message sent with now goes into Claude Code's queue behind the
    messages queued before it; once Claude Code reports it queued and the running turn has begun
    (its init, or Claude writing), the runner stops the turn with a plain interrupt, and Claude
    Code runs every message still queued in the next turn, in order, as one (the interrupt's
    `still_queued`). If the running turn takes the message in before the stop (between two tool
    calls), the turn is not stopped: it is answering it. While helpers run, now is a plain send
    (a stop would stop them too, decision of 2026-11-17): it queues behind the others and Claude
    Code takes them all in together at its next step. Archiving a running chat stops it with
    `cancel_queued` (the messages queued, a now message among them, are dropped and leave the
    chat), so nothing it queued runs after the archive. A message Claude Code drops for good
    (`command_lifecycle` cancelled, discarded or refused while queued) leaves the chat. What is
    not covered: a stop that crosses a running turn taking the now message in at that moment
    leaves that message seen by Claude but unanswered (Claude Code's own fix for it,
    `requeue_if_interrupted_within_ms`, is internal). Instead of: holding queued messages in
    Claudia until Claude Code is about to take them (Claude Code takes messages in between tool
    calls, which Claudia cannot see coming; the SDK's own removal makes holding unneeded); stopping
    the turn first and sending the now message after its end (the queued ones ran first in a turn
    of their own and the now message queued behind it, as before); stopping with `cancel_queued`
    and sending everything again (Claude Code skips a user message whose uuid it has seen, and new
    ids would lose each message's voice and images).

84. One media viewer for every photo and video, with Save that works on an iPad (2026-11-20, the
    user's call: "fix image previews and video previews, so I can download them on my iPad, they
    show up nice on iPad, with an X, centred and zoomable"). A message's photos, a photo or video
    Claude showed, the composer's photos (a video's frames), the files form's and the Folders
    preview's images and the 3D scene's video all open in `claudia.web.views.viewer`: over the
    whole window (put in the body, or the element in full screen), dark backdrop, the item centred
    and fitted inside the safe areas, a bar on top (title, 2 / 5, Save, a 48 px ×; its left edge
    clear of the iPad window buttons). A photo pinches, double-taps, wheel- and trackpad-zooms
    (Safari's gesture events too), pans while zoomed and never leaves the stage (zoom 1 to 8x); one
    finger on an unzoomed photo swipes to the next or previous one (also the on-screen arrows and
    the arrow keys) or drags down to close. A video plays inline with the player's own controls,
    no zoom (it would fight the controls); in the chat it shows as its first frame with a play
    button. It closes on ×, Esc, a tap beside the item and back: opening adds a history entry at
    the same address (`claudia.web.back`), so the back button or iOS's edge swipe closes it and
    never leaves the chat; every popstate listener asks `back/took-pop?` first. Save fetches the
    file as the img or video does (same address, the login cookie) and, on an iPhone or iPad
    (Safari, the home-screen web app and the iPhone app's web view alike, which show or take no
    downloads), hands it to the share sheet (`navigator.share({files})`: Save Image or Save Video
    to Photos, Save to Files); elsewhere it downloads. The share sheet opens only right after a
    tap, so a photo is fetched as soon as it shows and the tap hands it over at once; a video is
    fetched on the first tap with its progress on the button, and if iOS finds that tap too long
    ago the button says to tap again. Files are named after the shown file (its path's last part),
    photo-1, photo-2 for a message's photos, the scene's title for its video, with the extension of
    the type the server sent. Instead of: `<a download>` (does nothing in a home-screen web app or
    in the app's web view), a zoom-only image overlay and the video playing only in place, and the
    3D scene's own player.

85. Sentinel is one key per person, kept by the hub (2026-11-20): Claudia is just a client of
    Sentinel's API. A person makes a key on Sentinel's API keys page (`snt_` and 43 base64url
    characters; read+write lets chats start worktrees and previews) and pastes it once in Settings,
    beside GitHub. The hub keeps it sealed in its store (under `HUB_TOKEN_KEY_FILE`, as the GitHub
    login of decision 23) and hands it to the person's profile on every server they may use
    (`PUT /api/sentinel-key` through the front, as them): at once after a save, and every minute for a
    server that missed it, the same moments as the GitHub login. The runner keeps it in
    `~/.config/sentinel/key` (0600, that Linux user's only) and starts every chat with `SENTINEL_KEY`
    and `SENTINEL_URL`; worktree reports send it as `Authorization: Bearer` (the old
    `x-sentinel-token` is gone since 2026-11-20). The page sees only the key's first 8
    characters; a removed key is remembered as removed, so a server that was off drops it later.
    Nobody approves a use: chats run unattended (at night, in the background), and the key's own
    scope on Sentinel is the limit. The hub refuses `/api/sentinel-key` from a page. Instead of: one
    shared token on every server acting as nobody in particular, a key per chat asked through the
    key broker (decision 66) with a passkey for each use, or Claudia calling Sentinel on the chats'
    behalf. Files: `src/claudia/hub/sentinel_key.cljs`, `src/claudia/slave/sentinel_key.cljs`,
    `src/claudia/shared/sentinel_key.cljc`, `src/claudia/slave/worktree.cljs`,
    `src/claudia/web/views/settings.cljs`.

86. A new Agent SDK takes each new session, with no host restart (2026-11-20, the user's call). The
    host loaded the SDK once at its start, and `deploy/update.sh` never restarts hosts (a restart
    stops every turn and background job), so servers ran an old SDK until a restart by hand, while
    a new SDK comes about daily. Now each session loads its SDK when it starts (`op: "start"`,
    `claudia.slave.sdk-load`): the version in the release's `node_modules`, the release being the
    folder the host was started from by path, so after an update it is the new one. It is imported
    by absolute path with `?v=<version>` (Node keeps one module per URL), with the zod beside it. A
    running session keeps the module it started with; the host keeps every loaded one in memory and
    itself never restarts for an SDK. Where the files live: a release folder is deleted two updates
    later (`<release>.old`, `/srv/claudia.old`), and that was not made safe by proof (a session's
    Claude Code binary keeps running from a deleted file on Linux and macOS, but a fallback reloads
    from the files and the SDK finds its binary by path), so the files stay instead. The host loads
    from a store beside the release, `<release>.sdk/<version>/node_modules` (`deploy/sdk-store.sh`,
    run by update.sh on single-user Linux and Mac and by the shared server's install.sh before the
    runners restart): the release's node_modules as hard links (`cp -al`, on Linux and on macOS
    alike: a Mac's store copy had the release's inodes, 2026-11-20), else APFS clones, else a copy.
    A version leaves the store only when it is not one of the three stored last and no
    process's command line names its folder (each session's Claude Code binary runs from there). A
    developer's checkout has no store: its own node_modules, which `npm ci` replaces under running
    sessions as before. Idle chats move: the host says each session's SDK version and the version a
    new session gets (`sessions`, PROTOCOL.md), and the runner closes a session that is older when
    its chat is between turns, not settling and has no background task running (at a runner
    connect, which every update brings, a turn's end, and once its background work settled); an
    open chat resumes at once on the new SDK, a passive one with its next message, from its `.jsonl`
    as always. Never mid-turn or with work running. Failures: an SDK that fails to load, or whose
    query fails before its first message, falls back to the version the host started with (else the
    newest other loaded one); the messages sent so far go to the fallback, the runner shows the
    error in the chat (`{ ev: "sdk", error }`), and new sessions skip that version until another is
    installed (the top `sdk` is then the fallback, so idle chats are not closed over and over).
    The model picker follows too: the runner's probe of models and slash commands loads the version
    the host names for new sessions (the installed one before the host has answered), with the
    same code (`claudia.slave.sdk-load`), and probes again whenever that version changes.
    This code itself reaches a host only with one restart by hand, like any host change; on a
    single-user Linux server the update that brings it still runs the old update.sh, so its store
    fills from the next update on (until then sessions load the release's own node_modules).
    Instead of: restarting idle hosts after an update (background jobs would stop), or a host per
    SDK version (a runner would talk to several).

87. Claudia keeps every server on the newest Agent SDK herself (2026-11-20, the user's call). With
    decision 86 a new SDK took new sessions with no restart, but still reached servers only through
    a merge to main (a Renovate PR, about one SDK release a day), and the user holds every merge.
    Now the release builder (decision 29) looks at npm every 5 minutes
    (`deploy/release-builder/sdk-channel.sh`, run by builder.sh between rounds): when npm's latest
    dist-tag of `@anthropic-ai/claude-agent-sdk` is newer than the newest the hub has from it and
    not below what main's package-lock pins (`claudia.shared.sdk-channel/publish?`, run from main's
    own signed build: `out/sdk.js`), it takes main's package.json and package-lock.json, moves only
    the SDK to that version (`npm install --package-lock-only`: the SDK's own dependencies follow,
    the rest stays as main pins), and per platform installs a clean `npm ci --omit=dev` as
    `deploy/bundle.sh` makes a release's node_modules: linux-x64 and darwin-arm64
    (`claudia.shared.sdk-channel/builds`, the one list: names, npm's flags, what is left out).
    Verified before it is packed: the SDK's and the platform package's integrity in the lockfile is
    the registry's `dist.integrity`, npm ci checks every tarball against the lockfile, and `npm
    audit signatures` checks the registry's signature of every installed package (and the
    provenance attestations of those that publish them; the SDK publishes none today). Then
    linux-x64 leaves out the musl Claude Code binary, which npm puts in on any Linux (the lockfile
    names no libc): every Claudia server is glibc Linux or a Mac (checked 2026-11-20); the linux-x64
    tarball is 195 MB without it, 359 MB with it (0.3.329). A release's own node_modules still carry it. Each platform's node_modules and `sdk.txt` become
    `claudia-sdk-<version>-<platform>.tar.gz`, signed with the builder's key like every release
    file, and go to the hub; `POST /hub/releases/sdk` then keeps the version (`sdk.json`, the newest
    5 with their files) and pokes every server (`sdk <version>`). On each server `deploy/update.sh`
    (single-user Linux, `--shared` and a Mac alike), first on every run (a poke or the hourly timer),
    asks the hub (`GET /hub/releases/sdk`: versions only, so the hub can hold one back but never
    change one), and when this release's `out/sdk.js` says to store it (not marked broken, not
    stored yet, not older than the release's own) fetches its platform's tarball, checks the
    signature exactly as for a release file (`deploy/release-signers` on GitHub's main) and the
    version inside, and moves it into the SDK store (`deploy/sdk-store.sh <release>
    <node_modules>`: `<release>.sdk/<version>`, the same retention: the three stored last that are
    not marked, and any version a process runs from). Nothing restarts: a new session now takes the
    newest installed version not marked broken, the release's own or a store copy
    (`claudia.shared.sdk-channel/usable-newest`; decision 86 took the release's own), read each
    time; the host looks at the store every 10 s and tells the runner when that changed (`{ ev:
    "sdk-newest" }`), so the model probe moves and idle chats move as in decision 86. The front
    says the version a new session gets (`sdk` in `/api/me` and `/api/version`), shown beside the
    commit under the gear. Rolling back: Settings, Release builder, Roll back (asked first) sends
    `POST /hub/admin/sdk-rollback`; every server is poked with `sdk-rollback <version>` (and the
    hub's answer lists it), marks it in `<release>.sdk/broken`, and its new sessions skip it (the
    next newest installed one), idle chats on it move off it, and it is never stored again; the next
    npm version moves everyone forward, like decision 35. A release that pins a newer SDK than the
    channel's still wins (newest wins). What it needs once: the builder's `install.sh` run again
    (it installs `sdk-channel.sh`), a release with `out/sdk.js` on each server (until then update.sh
    says the channel is not there), and one host restart by hand for this host code (decision 86).
    Instead of: merging each Renovate PR (the user holds merges), each server installing from npm
    itself (every server would trust npm directly and need its tooling; the builder already is the
    one machine whose signature servers trust), or a separate signing key for the channel (one key,
    one list of signers).

88. Agents rename and recolour chats themselves, and Claudia's own chat cleans up all of them
    (2026-11-20, the user's calls: the user should not have to tidy chats by hand; "rename_chat" and
    "set_chat_colour" as two tools). Two parts:
    - Every chat's agent has two workspace tools that act only on the chat it runs in:
      `rename_chat { title }` (1-200 characters, trimmed) and `set_chat_colour { color }` (a solid,
      a gradient or ash). Their descriptions say they change only this chat's title or colour in the
      list, nothing else; the prompt says to use them when the user asks, or `rename_chat` to give
      the chat a telling title. The runner knows which chat a tool call comes from, so there is no
      chat id to pass or to get wrong; it makes the same change as the chat menu's `PATCH
      /api/chats/:id` (`claudia.slave.chats/edit!`), and a title or colour outside those bounds is
      refused, not cut (`claudia.shared.chat-edit`).
    - Only Claudia's own chat (decision 48) acts on every chat of its person on every server.
      `list_chats` now also gives each chat's reference (`<server>/<id>`), colour, folder, last
      activity and start, and the person's chat order and groups (decision 38). The new
      `organize_chats { steps }` (at most 200) does, in turn: `edit` (one chat's title, colour,
      archived; a `PATCH /api/chats/:id` on that chat's server as the person, through the hub's
      call to that front), `new_group`, `rename_group`, `delete_group` (its chats stay where it
      was) and `move` (a chat into a group or to the top level, or a group, before a named item).
      The hub checks every step (`claudia.hub.organize`): a chat must be in the person's own list
      from every server, so another person's chat is refused; the order steps run on the person's
      list on the hub (the chats not placed yet first settled as the page shows them, as a drag
      does), which is kept once; pages see it at their next 20 s refresh. Each step answers ok, a
      new group's id, or what was wrong; one wrong step does not stop the others. Her prompt says:
      her own chat first, others only for a cleanup the person asked for.
    Instead of: one `edit_this_chat { title?, color? }` (the user's later call: two plain tools),
    every chat able to change other chats (decision 18), or a hub op per change (one call per
    cleanup, with each step's result).

## Hub operations (2026-11-03, ported from the TypeScript Claudia's decisions 22, 23, 24, 27)

- **Servers and access**: the hub's own SQLite store (`HUB_DATA/hub.db`, `src/claudia/hub/store.cljs`)
  is the source of truth for the servers, the people and the People table (per person: the servers
  they use, a tick = their own profile there; whose chats they may write to; admin; their profile
  name, whose agent user alone gets SSH certificates, decision 53). A `hub.json`
  (`HUB_CONFIG`) is imported once into an empty store, then no longer read. Admins (the store's admin
  flag, or the login group `HUB_ADMIN_GROUP`) change it in Settings → Admin (People and Servers).
  A write tick (2026-11-16) lets a person open, start and send in another person's chats on every
  server where both have a profile: the hub names those people to each front (`X-Claudia-Writes`),
  which then passes the view on without `X-Claudia-Read-Only`; Claude runs as the owner's Linux user.
  Without a tick another's chats stay read only, and secrets are only ever read for one's own profile.
  Everyone reads everyone's chats (2026-11-18): a signed-in person may look at other profiles' chats,
  read only, also on a server where they have no profile of their own (`/hub/me` `viewServers`; the
  hub passes only `/api/me` and views there, the front answers with no target otherwise). Their own
  chats stay on their own servers. A server gets its hub key with `deploy/pair.sh` (decision 47: a
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
  front and the runners restart, never the hosts (a new Agent SDK takes their next sessions, decision 86; a newer one from npm comes with no merge, decision 87). No call fails while they do (2026-11-16): the
  hub tries a front that refuses it again every 0.3 s for up to 15 s, and a front a runner whose
  socket refuses or is missing, for any call without a body (GET, HEAD, a WebSocket handshake), so
  a restart makes a call wait a moment instead of failing; a front that is off or asleep times out
  as before. The hub on the gate runs in two slots and switches without a gap (the gate repo). A single-user machine's `CLAUDIA_CHECKOUT`
  must be a release folder (a git checkout is never replaced): first install with
  `CLAUDIA_CHECKOUT=<folder> deploy/update.sh`, then enable `claudia-update.path` and `.timer`
  (on a Mac the same first install loads the launchd agents, the update agent included:
  [deploy/macos/README.md](deploy/macos/README.md)).

## The 3D scene, the phone and alerts (2026-11-03, ported from the TypeScript Claudia's decisions 26 and 29)

- The scene3d form: parts (box, sphere, cylinder, cone, pyramid, torus, capsule, plane, ring, extrude, lathe, tube, rod, group, person) in a three.js view the person turns, zooms and pans; arrows (flowing dashes), labels, a legend, a click on a part opens its facts with Explain (asks Claude in the chat). Diagram look by default (flat colour bands, chalk outlines), a realistic one with materials and shadows. Labels are callouts, as in an atlas: never on the model, in a column on each side of it (clear of the legend, the subtitles and the bar), each with a thin line to a dot on its part; a label whose dot is hidden behind something solid is not shown, and one that does not fit is left out. Without a story every label shows; in a story, the step's focus, else what the step brings in, else (an overview step) every label, and always the part under the pointer. A story steps through it (show, hide, focus, camera, poses) with subtitles and, once turned on, a narrator voice from the hub (`/hub/narrate`, `HUB_TTS_URL`), in the story's `language` (an ISO 639 code: the voice service gets it, and a described `voice` is cloned from a reference clip spoken in that language, so Hungarian lines get a Hungarian accent; a language the hub has no reference sentence in is spoken by the service's default voice). A `script` runs in QuickJS (WebAssembly; no window, no network) for parts made by code and motion every frame; solid parts that pass through each other glow red and are counted. The film button in the bar makes the story's video in the background (the page may close at once): the page sends the scene as shown to the hub (`POST /hub/videos`), the hub hands it to the person's runner and, when it has a voice, says the lines and brings the clips (always: the page's voice switch is for listening now); the runner's own headless page plays the story on a stepped clock (every frame 1/30 s after the last, however slow drawing is), paints each frame with its labels, legend and subtitles, and ffmpeg makes them an H.264 MP4 with the voice as AAC, kept with the chat (`RUNNER_DATA/videos`). The bar shows how far it is, then a button that plays it in the media viewer with Save (decision 84: the share sheet on an iPhone or iPad, a download elsewhere; the file is named after the scene), on any device and after a reload, and a desktop popup when popups are on. A scene without a story gets 15 s. Needs ffmpeg on the server (`CLAUDIA_FFMPEG`, else `ffmpeg` on the PATH). Real human anatomy: a part `{shape: "anatomy", ref, side?, system?}` becomes the structures it names (bones, muscles, nerves, ligaments, muscles' origin and insertion patches; by English or Latin name, id, region, compartment or nerve territory), each loaded as its own small model from [claudia-anatomy](https://github.com/p4ulcristian/claudia-anatomy) (Z-Anatomy, CC BY-SA 4.0); the hub (`/anatomy/<version>/…`, kept in `HUB_DATA/anatomy`) and the runner's render page (kept in `~/.cache/claudia/anatomy`) fetch each file once from the pinned version (`claudia.shared.anatomy`). One 3D scene per picture. It stays a general tool: small generic primitives, nothing topic-specific. three.js, QuickJS and the view are their own module (`out/web/js/scene3d.js`), loaded on first use. Files: `src/claudia/web/scene3d/` (engine, view, person, poses, story, ink, overlap, vm, voice, clock, maker, frames, paint, videos, icons, callouts, anatomy; pure maths, data, labels, bar, video, structures), `src/claudia/slave/video.cljs`, `src/claudia/hub/videos.cljs`, `src/claudia/hub/narrate.cljs`.
- People: a `person` part is a rigged CC0 MakeHuman body (`resources/web/person/person.glb`, about 400 KB, made by the TypeScript Claudia's `tools/person/make-person.py`), posed live: named poses measured from the body (stand, t, reach, wave, point, hands_on_hips, hold, walk, sit, sit_floor, kneel, squat, bend, lie) and any target overridden; arms and legs are exact two-bone chains, hands, feet and the gaze can target a part; parts can ride on a joint (`parent: "anna.handR"`).
- Phone: the list, the chat and the picture are screens, each sliding over the one before; sideways swipes follow the finger (`src/claudia/web/swipe.cljs`): on the chat right goes to the list and left pulls the picture in, on the list left brings the open chat back, on the picture and on Settings right goes back; past 40% or flicked it completes, else springs back; never in fields, on what scrolls sideways itself (code, tables, graphs, the 3D view), with two fingers or on a mostly vertical move. A Home Screen web app (`resources/web/app-icon/`: manifest and icons; standalone safe areas, 100lvh).
- The chalk hover tip (a clipped chat title, or any `data-tip`), the logo mark in Settings and the favicon.
- Sounds per chat colour, in each chat's own sound (decision 90; one strike when a chat finishes, two rising notes when it waits on you) for every chat whose bell is on (decision 21), and desktop popups (the browser's notifications, asked once; a per-device switch in Settings) for the same chats; the open chat only when the window is not in front (`src/claudia/web/alerts.cljs`).

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
npx shadow-cljs release host runner front hub broker root sudo web sdk
```

89. One colour, one chat, and every colour has a short name it says (2026-11-20, the user's call:
    "one color cannot be used more times", "it should say its color first, like red here", "we do
    need short names"). No two open (not archived) chats wear one colour while one is free, across
    every server of the person: a new chat takes a free colour (`colours/most-different`: a solid
    while one is free, else a gradient; none the 5 chats started last wear, nor their crayons, while
    another is free; the furthest in OKLab from every colour worn), the page picking from every
    server's list and picking again when a list that arrives later wears it; both palettes show the
    colours another open chat wears struck through and not pickable; a chat back from the archive,
    or woken from grey, whose colour an open chat wears takes a free one; and once every list is in,
    two open chats in one colour (from before) give the newer one a free colour (`recolour-plan`,
    the same on every device, so two pages agree). A runner alone (a chat made over HTTP, a fork, a
    wake) sees only its own server's chats. Every colour may be worn again once all are taken
    (decision 26's least-worn pick). Ash (grey, passive) may be worn by many. For room, 8 more
    gradients (decision 80), 36 colours in all: coral (peach→bubblegum), rust (chocolate→gold),
    moss (olive→forest), spring (sunflower→aqua), tide (mint→sky), reef (forest→ocean), storm
    (ocean→grape), orchid (plum→bubblegum), their middles still at least 0.1 apart in OKLab (the
    closest now tide and lagoon, 0.108; the first picks Frost jade→periwinkle and Coral peach→cherry
    looked like lagoon and ember). Every colour has a short name, shown in the palettes' tips and
    said by its voice before each reply ("Red here. …", decision 21) unless that chat is the one
    open when the reply is read (2026-11-21, the user's call: "when we are already on that chat, it
    shouldn't"; `talk/spoken`): Red, Brown, Peach, Orange,
    Gold, Yellow, Olive, Lime, Green, Mint, Jade, Aqua, Sky, Blue, Indigo, Lilac, Purple, Plum, Pink,
    Rose; Berry, Coral, Ember, Rust, Sunset, Citrus, Moss, Meadow, Spring, Tide, Teal, Lagoon, Storm,
    Dusk, Orchid, Candy; Grey for ash (`colours/names`). The stored ids stay (cherry, ocean, reef …),
    so no row moves; reef shows as Teal because `teal` is an old colour a runner turns into mint.
    Instead of: renaming the ids (every row and picture moved), a chat saying its long crayon name
    ("Periwinkle here"), or many chats in one colour once more than 20 were open.
    Files: `src/claudia/shared/colours.cljc`, `src/claudia/web/events.cljs`,
    `src/claudia/web/views/head.cljs`, `src/claudia/web/views/chatmenu.cljs`,
    `src/claudia/slave/chats.cljs`, `resources/web/css/claudia.css`.
90. The header's pills name the server, the project, the sound and the colour, and row 3's
    buttons have words (2026-11-21, the user's design). Row 2, every one a pill with a ▾ popup:
    the server (a computer and its label; the popup lists the person's servers, the chat's ticked,
    an off one dimmed: a new chat's pick is where it starts, as Files' computer toggle; a started
    chat's pick opens the move card of decision 82 with that server as where it goes; passive in a
    read-only view, for a new chat with one server, and where a move is not allowed: a view, a
    write tick, Claudia's own chat); the project (a folder and the name of the person's bookmark of
    the chat's folder on that server, decision 61, else the path with `~` for the home; a new chat
    with none: "Pick a project"; the popup lists that server's bookmarks, the name bold and the
    path in mono under it, the chat's ticked: a new chat's folder pick, a started chat's folder
    move); the subscription, model · effort, prompt profile and voice as before; notify (a bell and
    the sound's name, a struck bell and "Off" when off; the popup lists Bell, Marimba, Chime, Drop
    and Knock, each with a ▶ that plays it once without picking it, then Off: no sound, no popup,
    no phone push); the colour (a dot in it and its short name, decision 89; the popup is the
    palette). Row 3 keeps the rings on the left; on the right, rounded rectangles with an icon and
    a word: Files (toggles the File Browser in the right half, filled while it shows; not in a
    read-only view, which shows the picture only), Sudo off / Sudo on (the root switch, filled red
    while on) and Archive / Unarchive. The sounds are made by the page with Web Audio, no files
    (`claudia.web.alerts`), each pitched by the chat's colour as the bell was, so two chats still
    differ: Bell is the old chime (a sine and its faint octave), Marimba a short wooden strike (its
    fourth and tenth partials), Chime a long ring of inharmonic partials, Drop a quick upward
    glide, Knock a short falling triangle; a finished turn strikes once, a wait strikes twice going
    up. A chat keeps its sound beside its bell: the runner's `chats.sound` (default `bell`), the
    Row's `sound`, the `settings` and `new` frames' `sound?` (`claudia.shared.sounds`; an unknown or
    missing one is the Bell), so an old `bell: true` reads as the Bell and the hub's pushes still
    follow `bell` alone. A pick sets both (a sound: bell on with it; Off: bell off, the sound
    kept); a new chat's pick goes with `new` and the next new chat takes it over like the bell; a
    group's picks keep it; a moved chat takes it along (it is a column of the copied row). Instead
    of: the place pill (computer and folder in one, a click toggling Files, decision 71), sound
    files, and round icon-only buttons for colour, bell, root and archive. Files:
    `src/claudia/web/views/head.cljs`, `src/claudia/web/views/head_pill.cljs`,
    `src/claudia/web/views/head_place.cljs`, `src/claudia/web/views/notify.cljs`,
    `src/claudia/web/views/pure/head.cljs`, `src/claudia/web/alerts.cljs`,
    `src/claudia/shared/sounds.cljc`, `src/claudia/web/move.cljs`, `src/claudia/slave/store.cljs`,
    `resources/web/css/claudia.css`.

93. Voice messages (2026-11-21, the user's design, one call at a time; the mic, the circles' gestures
    and playing by themselves changed by decision 94). A chat with a voice that
    ends a turn by itself (not stopped, not failed) or asks no longer talks at once on the page
    touched last (decision 21): it leaves a voice message. The runner keeps it (table
    `voice_messages`: its reply or ask, voice, time) and sends every page of its person the whole
    list (`voices`, PROTOCOL.md) after `hello` and whenever it changes, so every device shows the
    same. Round the mic, one circle per chat with messages waiting (the user: "round mic only"; the
    chats' rail adds nothing: its circles already show the talking bars), in the chat's colour, the
    count on it when more than one, a little bigger for more to say (30 to 40 px), the chat waiting
    longest first, up to six; on the tablet's rail they fan out above and below the mic's left,
    elsewhere above it, leaving straight out free for the speaker's name, the hint and the clips.
    Nothing plays by itself: only a tap on a circle plays (the user: "full control"; a tap on the
    mic stops what plays, else says to hold, as before). A tap plays that chat's messages one after
    the other, oldest first, then stops: another chat's wait for their own tap. One read to its end
    is heard: the page sends `heard` and it goes on every device; a swipe on a circle takes the
    chat's messages away unheard the same way; a long press opens the chat; a tap on the circle
    playing stops it (it stays). Holding the mic hides the circles; they come back, still unheard.
    A hold while a message plays stops it and replies to that chat, not the one open: the flower's
    bars and disc wear that chat's colour (`--rec`) and "→ <title>, your reply" sits over the mic;
    the rest of its line is dropped (unheard ones stay). A message waits at most a day; a chat whose
    voice goes Off, or that is archived, drops its own. Instead of: auto-play on one device (decision
    21), circles on the chats' rail or both places, a tap on the mic playing the chat waiting
    longest, a circle per message, playing every chat's messages in a row. The `here` frame chooses
    nothing now and is still accepted so older pages get no error. Files:
    `src/claudia/slave/voice_messages.cljs`, `src/claudia/slave/store.cljs`,
    `src/claudia/slave/runner.cljs`, `src/claudia/web/views/voice_circles.cljs`,
    `src/claudia/web/views/pure/voicemail.cljs`, `src/claudia/web/speech.cljs`,
    `src/claudia/web/events.cljs`, `src/claudia/web/views/mic.cljs`.

94. The mic floats with its little mics, and new messages play by themselves (2026-11-21, the
    user's calls one at a time, after decision 93 was in use). On a touch tablet the mic leaves the
    rail ("the microphone should not be a part of the rail anymore so it can stay with the circles
    with the petals"; on phones too since decision 110): it floats over the page, bottom right beside the rail until dragged through
    its move ring (decision 73's spring), its spot kept on this device, always the whole flower,
    four petals; the rail keeps its eight slots without it (decision 92) and the "mic back" circle
    is gone. The big mic is the open chat; every other chat with voice messages waiting is a little
    mic round it, a full ring of up to eight (a fan of six above the mic on phones and desktops),
    half a step off straight up so the speaker's name, the hint and the clips sit to the mic's left
    past the ring. A tap on a little mic plays that chat's messages; a tap on the one playing skips
    to the next (the one cut off counts as heard; no previous: the user's call). A hold opens the
    same flower round that circle, in its chat's colour, and the words go to that chat without
    opening it (the user: "from the circle we just talk back to them"); chats open from the rail.
    While one plays or is held, the other circles step away. A swipe no longer takes messages away
    and a long press no longer opens the chat (a hold talks now). New messages play by themselves
    as soon as their sound is ready (the user: "I want the voices to start talking whenever they're
    ready"), on the one page its person touched last (`here`; the runner adds `play` to that page's
    `voices`), so one device talks: a message that comes while another plays, or while the mic is
    held, waits its turn and plays after (the queue is only for those). A tap on a circle plays
    that chat first; what waited from other chats comes after it. A hold while one plays stops it
    and drops only that chat's rest. Instead of: nothing playing until a tap (decision 93), the mic
    in the rail with a half flower, long press opening a chat, previous as well as next. Files:
    `src/claudia/web/views/mic.cljs`, `src/claudia/web/views/voice_circles.cljs`,
    `src/claudia/web/views/pure/voicemail.cljs`, `src/claudia/web/views/pure/talk.cljs`,
    `src/claudia/web/speech.cljs`, `src/claudia/web/events.cljs`, `src/claudia/slave/runner.cljs`,
    `src/claudia/web/views/thumb_edge.cljs`, `resources/web/css/reach.css`.

98. The person writes in white, Claude in the chat's colour (since decision 103 both write in the
    same near-white, and shape and side tell them apart) (2026-11-21, the user's call). The
    person's own messages are one near-white in every chat (`--mine`, right-aligned as before), no
    longer the chat's chalk; Claude's replies are written in the chat's chalk (`--reply`: the hue 75%
    with white, the old `--accent` of a chat), a gradient chat's mixed from its middle colour,
    Claudia's own chat from her green. An ash (grey, passive) chat and a page with no chat colour
    keep Claude's replies plain (`--fg`). Code, tables and quotes in a reply stay in the plain text
    colour, links blue, so they read as before. The window's tint by chat colour does not change
    (decisions 26, 80). Instead of: the person's lines in the chat's colour and Claude's in plain
    white. Files: `resources/web/css/claudia.css`, `DESIGN.md`.
99. Fork from the header and from a message's menu (2026-11-21, the user's call). Row 3 of the
    header (decision 90) gains Fork beside Files, Sudo and Archive, the same rounded button with an
    icon and a word, on a started chat outside a read-only view: a new chat with this one up to its
    last finished turn, in the same folder (`[:ops/fork k nil]`), "Forking…" and filled while the
    server makes it. A right click on one of the person's own messages, or a long press (450 ms, a
    move over 10 px cancels) on a touch screen, opens a small menu in the chat menu's look with Fork
    from here (`[:ops/fork k id]`), only where a fork may start (not an answer to a question, not the
    running turn's message, not a read-only view: `pure.chat/user-marks`). iOS sends no contextmenu,
    so the page times the press itself; on touch screens those messages are not selectable, so iOS's
    own selection does not cancel the press (Claude's replies still select). A fork's state is its
    own (`:fork {:k :busy :error}`, `pure.menu/fork-state`): an error shows in the open menu, with
    none open (the header) on the error line. Instead of: the "fork from here" link under each
    message (shown on hover) and the "picture · N files" link beside it (the slider of decision
    100 shows a turn's picture; its step chips too until decision 103). Files: `src/claudia/web/views/head.cljs`,
    `src/claudia/web/views/head_pill.cljs`, `src/claudia/web/views/msgmenu.cljs`,
    `src/claudia/web/views/chatmenu.cljs`, `src/claudia/web/chatops.cljs`,
    `src/claudia/web/views/chat.cljs`, `src/claudia/web/views/pure/menu.cljs`,
    `resources/web/css/claudia.css`.
100. The pictures are numbered steps, and the chat and the slider find each other (the step chips
    on replies removed by decision 103) (2026-11-21, the user's call). The slider under the picture shows each picture's number, 1..N, the one shown
    filled in the chat's colour with dark text (pulsing while an older one loads); when the numbers
    do not fit (a number takes 12 px plus 7 per digit), every 5th or 10th (20th, 50th … for very
    many) shows, always with the first, the last and the shown one, and a number too close to one
    of those three is a dot like the rest (`pure.shell/tick-kinds`). Nothing is numbered on the
    board itself. "Back to live" left the bar: the last number or › is live. The Claude reply that
    ends a turn which drew a picture wears a small round chip with that picture's number, in the
    reply's colour (`pure.chat/reply-steps`: a turn runs from a message the person sent to the next,
    an answer to a question staying in it; its reply is Claude's last text, an aside not counted; the
    running turn has none yet; an imported picture none). A tap shows that picture on the board (on
    a phone, the picture screen too); a tap on the chip of the picture shown goes back to live.
    Picking a number on the slider, ‹ ›, or ←/→ (`[:picture/seek i]`) scrolls the chat to the reply
    that closed that picture's turn and lights it (the search jump's flash); the live one sends the
    chat back to its end and it follows the end again. Instead of: unnumbered dots, the Back to live
    chip, and the picture of a turn reached only from the link under its message. Files:
    `src/claudia/web/views/rightbar.cljs`, `src/claudia/web/views/pure/shell.cljs`,
    `src/claudia/web/pictures.cljs`, `src/claudia/web/views/chat.cljs`,
    `src/claudia/web/views/pure/chat.cljs`, `resources/web/css/right.css`,
    `resources/web/css/claudia.css`.
101. Claude's question cards fit a narrow chat column (2026-11-21, the user's call). The chat column
    is a named container (`chat`), and at 440 px or less the question, secret and OK cards follow
    its width, not the window's (the person drags it, about 280 to 700 px): one column of options,
    12 px padding, no key hint, the buttons sharing their row (Answer, alone there, the whole row),
    the secret card's facts stacked. A touch screen never shows the key hint. Instead of: window
    media queries, and the wide card's 16/18 px padding and side-by-side buttons in a column too
    narrow for them. Files: `resources/web/css/claudia.css`.
102. Claude knows the picture's history as numbered steps (2026-11-21, the user's list, items 9-13;
    Claude cleared the board and lost the user's base drawing because it was never told the old
    pictures stay). A step is a picture of the history (decision "Picture history" below), numbered
    by its 1-based place in the chat's list, oldest first, the number the page's bar shows; the last
    is live. The system prompt says so in a Steps paragraph: every turn that draws makes the next
    step, more draws in one turn update it, changing by id and clear both make a new step, old steps
    never change and stay, the user names steps by number, and in one talk about one thing Claude
    keeps the base drawing and changes it by id, clearing only when the topic changes (it replaces
    "Start a new picture (clear: true) when the topic changes"). The page's `send` carries `viewing`
    (the turn of the older picture looked at) and the runner tells Claude "The user is looking at
    step 3 of 7 (step 7 is live) while writing this." in a reminder after the text, hidden from the
    chat; on the live step nothing is said (the least noisy choice that is still unambiguous:
    the prompt says a message without that note was written on the newest step, so a line on every
    message would add nothing). A draw may give `from: N`: the new step starts as a
    copy of step N, then the rest of the call applies; `from` with `clear` is refused, not ignored
    (they contradict, and an error tells Claude which one it meant). The new `steps` tool of the
    picture server lists the steps (number, title, UTC time, which is live) or returns one step's
    scene as JSON, blocks whole up to 30,000 characters and the rest without their data, readable
    one at a time with `block`. The draw's answer starts with the step: "Step 8 · modified from 7 ·
    2 blocks changed", "Step 8 · fresh (clear)", "Step 8 · copied from 3", "Updated step 8 (this
    turn's step) · …", then "Picture now: …" and the notes as before. Instead of: steps kept from
    Claude (it cleared and lost work), a step line on every message, from with clear meaning
    either one. Files: `src/claudia/slave/steps.cljs`, `src/claudia/slave/tools.cljs`,
    `src/claudia/slave/chats.cljs`, `src/claudia/slave/runner.cljs`,
    `src/claudia/slave/codeforms.cljs`, `src/claudia/slave/history.cljs`,
    `src/claudia/shared/text.cljc`, `src/claudia/web/events.cljs`.
103. Whose message is which shows by shape and side, not text colour (2026-11-21, the user's list,
    items 22-24). The person's messages and Claude's replies are written alike: the same font, size
    (15 px), weight and colour, the window's near-white `--fg` (tinted 8% by the chat colour, light
    on every chat, gradient and Claudia's own green). The person's messages sit on the right in a
    soft bubble (`--fg` at 9% over the window, so it follows every tint and gradient; 14 px corners,
    the bottom right 4 px; at most 82% of the column; text left-aligned inside); sent photos, file
    chips and code chips sit in the bubble, the queued tag and its × under it. Claude's replies run
    the column's full width with no bubble and a 3 px line on their left in the chat's chalk
    (`--claude-line`: the chat colour 75% with white, the old `--reply`; an ash chat's is grey, since
    that mix of grey is grey; Claudia's own chat her green; no chat colour, `--muted` grey). The
    line is a layer of its own (`::before`), so the search jump's rounded flash does not bend it,
    and its edges and the text's line up with a step group's line. In a subagent's transcript (the
    agents screen) the line is 2 px at 55%, like the step lines between its replies there: that view
    is small and dense, and a full chalk line on each short line would outshout the chat beside it.
    Code, tables and quotes keep their look. The step chip on the reply that closed a picture's turn
    (decision 100) is gone; the numbered slider stays, and picking a number still scrolls the chat to
    that reply and lights it (`pure.chat/seek-goal`, `step-reply`). Instead of: the person's lines
    in a near-white weight 500 right-aligned without a bubble and Claude's in the chat's chalk
    colour (decision 98), and the step chips. Files: `resources/web/css/claudia.css`,
    `src/claudia/web/views/chat.cljs`, `src/claudia/web/views/pure/chat.cljs`, `DESIGN.md`.
104. Background work ends, Claude carries on (removed by decision 105, which found the real cause; 2026-11-21, the user's list, items 25 and 26; Claude
    started three subagents, said it would combine their branches, test and open a PR once they
    finished, and on the last one's notice replied only "No response requested.", so the turn
    ended and nothing happened until the user asked). Claude Code hands Claude a background task's
    end as a message marked as not the user's input; Claude read that mark as "nothing to do".
    Two fixes:
    - The system prompt's Background work paragraph: when background work Claude started finishes,
      it carries on in that same turn with the plan it told the user; the mark is about approval,
      not about whether work is left; an empty reply ends the turn and the work stops.
    - The empty-turn catch in the runner (`claudia.slave.chats.nudge`). A turn counts as started by
      a background task's end when Claude Code stamps the turn's origin on its result message as
      `task-notification` with no subkind (a scheduled task, a peer's message or an inbox delivery
      has one and never counts); a result without an origin (a person's message, Claudia's own
      sends, an older CLI) never counts. It ended with no real reply when the result's text is
      blank or only the stock phrase "No response requested." (any case, a closing full stop or
      brackets allowed; the only phrase seen in the sessions' replies to notices). Then Claudia
      sends Claude once: "You ended your turn without a reply after "<task>" finished. Continue the
      plan you told the user, or say in one line that nothing is left." The chat shows it as a note
      ("Claudia: …"), never as the person's message: it goes inside `<claudia-nudge>`, and the
      session file's copy reads back as the same note. Never: for a turn that failed or that the
      person stopped (Stop, Esc); for an archived or moving chat; while other
      background work of the chat still runs (its own end comes next); for a task that was stopped
      (the person stopped it on its chip, or Claude did); when a message went in after the task's
      end (the person's, or the nudge itself) or one waits in Claude Code's queue; when a turn
      started or a message went in while the session was made ready. The notice (the newest
      background task's end, kept with its time) goes at the end of the first turn that began
      after it, and a nudge needs one, so one notice wakes Claude at most once and a turn the
      nudge started never nudges. Read-only viewers send nothing: the nudge is the chat's own
      runner's, decided from its session alone. Instead of: a prompt rule alone (a rule can be
      missed), a nudge on every notice turn (two of three finished agents rightly say nothing),
      the Stop hook handing the turn back (it runs before the result says the turn's origin), or
      the nudge shown as the person's words.
    Files: `src/claudia/slave/tools.cljs`, `src/claudia/slave/chats/nudge.cljs`,
    `src/claudia/slave/chats.cljs`, `src/claudia/slave/chats/turns.cljs`,
    `src/claudia/slave/history.cljs`, `src/claudia/slave/tasks.cljs`, `PROTOCOL.md`.
107. The big mic is the open chat's; other chats talk from their circles (2026-11-21, the user's
    calls one at a time). The mic never takes another chat's colour, name, voice or bars ("they
    are hijacking the main microphone button ... the main microphone should be always about the
    main chat"): a hold always talks to the open chat. A chat read aloud shows on its own circle,
    the bars moving on it (dots while its sound is made), its name and voice beside the mic in its
    colour; only the open chat's own reply moves the bars on the mic, and a tap there stops it. A
    tap on the mic while another chat reads starts that chat's cut message again and says to hold.
    A hold on the circle of the chat reading talks back to it. Skipping: a tap on the playing
    circle skips to that chat's next message (as before), and a held circle's left petal is skip
    all instead of add: let go there and the clip is thrown away and all that chat's waiting
    messages are marked heard. Stop and drop: a hold (not a tap) on the mic or a circle stops the
    reading at once and marks all of the stopped chat's waiting messages heard, so its circle goes
    ("only the new message will be relevant"). Read is heard: opening a chat marks its waiting
    voice messages heard, here and on every device through its runner, and stops its reading; a
    reply that comes while the chat is open is still read aloud. The move ring's word is written
    along the ring, upright. Instead of: the mic wearing the chat being read (decision 21) and a
    hold replying to it (decision 93), stopped messages staying unheard on their circle, a chat
    opened keeping its voice messages, "move" written flat. Files:
    `src/claudia/web/views/mic.cljs`, `src/claudia/web/views/mic_flower.cljs`,
    `src/claudia/web/views/voice_circles.cljs`, `src/claudia/web/views/pure/mic.cljs`,
    `src/claudia/web/events.cljs`, `src/claudia/web/speech.cljs`, `resources/web/css/claudia.css`.

106. Claude's first output in a turn is one line in the chat, before any tool call (2026-11-21, the
    user: "Could we make Claudia answer very fast? It doesn't have to act very fast, but now it
    can't answer for a while; it thinks a lot, then it talks."). Measured over the last seven
    days' session files (3840 turns): the first spoken line came after 12 s at the median and 43 s
    at the 90th percentile; turns that spoke before any tool took 4.6 s, turns that ran tools first
    (60% of all) 21 s. The wait is the work before the first word, not the thinking (94% of
    thinking blocks are empty: the API hides the text on these models). So the system prompt's
    chat section says: the first output of every turn is one short line saying what was heard and
    what comes next, before any tool call and before any drawing; the thinking and the tools
    follow it; small talk and a known answer are that one line and nothing else. The request
    start itself (about 4 s to the first block, the context's size) stays. Rejected: a fast-mode
    switch per chat (2x plan usage, and the tools would still come before the line; it may come
    later as an addition), a thinking-progress counter on the Thinking chip (nothing gets faster),
    a cheap model's greeting ahead of the real reply (its own start eats the gain and it can
    contradict the answer). Measured again the same way after a week of use.

105. A turn Claude Code starts by itself runs, so nothing closes its session under it (2026-11-21,
    the user's "it happened again"). Decision 104 was built on a wrong reading: every "No response
    requested." after a background task's end (14 in three days of session files) is Claude Code's
    own placeholder (model `<synthetic>`, 0 tokens), written when a session resumes over a turn that
    never got its answer. The answer was lost because the runner closed the session while that turn
    waited for it: a turn Claude Code starts after a background task's end counted as running only
    from its first streamed words, so a stale session (other tools or instructions after an update)
    was closed by `restart-if-stale!` the moment the last agent ended, or by `settled!` after the
    30 s init wait while a big chat's first answer was still coming; `move-to-newest-sdk!` and the
    idle close had the same gap. Now the turn runs from its `init` (`turns/init-starts-running?`),
    and no stale restart happens while the chat settles (`turns/stale-restart?`): the settle wait
    sees the notice's turn start, and the close comes after that turn's result. Removed: decision
    104's nudge (it watched for a reply Claude never writes) and its prompt paragraph. Instead of:
    keeping the nudge as a safety net. Files: `src/claudia/slave/chats.cljs`,
    `src/claudia/slave/chats/turns.cljs`.
108. A background task's end is a card (2026-11-21, the user: "these cards in the chat look pretty
    bad ... the ones which say that background agent finished, we need those styles"). The note a
    finished background task leaves in the chat was a dashed code box with Claude Code's raw line,
    and an agent's whole answer showed there as monospace with its Markdown marks. Now it is a card:
    a coloured edge and mark for how it went (green done, red failed or a command's exit code not
    0, grey stopped or killed), the agent's name or "Background command" with its state in words
    ("finished", "completed (exit code 0)"), a command on one mono line, and the agent's answer as
    Markdown, folded to four lines with More. The runner reads the card from the session file's
    notice (`<status>`, `<summary>`, `<result>`, unescaped) and from the live `task_notification`
    (its status, its summary, the task's description); other notes (slash command output, move
    notes, Claudia's nudge) keep the code box. Instead of: every note in the dashed mono box.
    Files: `src/claudia/slave/history.cljs`, `src/claudia/slave/chats.cljs`,
    `src/claudia/web/views/chat.cljs`, `src/claudia/web/views/pure/chat.cljs`,
    `resources/web/css/claudia.css`, `PROTOCOL.md`.

109. One layout on every screen wider than a phone, touch or mouse (2026-11-21, the user's call:
    "desktop would get the tablet view, because it's almost the same"). The two wide layouts
    differed only in the edge column on the right wall (decisions 73, 92), the floating mic
    (decision 94) and the chats' rail centred at middle height (decision 40); now a desktop has
    all three too: the rail's Search and New chat and its foot (the update pill, your profile
    circle, the version) move to the edge column, the composer's mic floats beside it, and System,
    Prompts, Settings, Stop, Archive and ‹ › are one click away. Only sizes follow the pointer
    (`reach.css`): a finger gets 12 mm buttons (64 px on an 88 px column, chat rows 64 px tall), a
    mouse 48 px buttons on a 64 px column, solid under the pointer. A phone (under 680 px) keeps its
    three screens. Instead of: two wide layouts (two sets of places to learn, two to build and
    test; System, Prompts and Stop only through the menu or keys on a desktop), or the tablet's
    sizes on a desktop too (88 px of the right half for buttons a mouse does not need that big).
    Files: `src/claudia/web/views/pure/reach.cljs`, `src/claudia/web/views/shell.cljs`,
    `src/claudia/web/views/rail.cljs`, `src/claudia/web/views/chat.cljs`,
    `src/claudia/web/views/thumb_edge.cljs`, `resources/web/css/reach.css`.

110. The phone's mic floats like the tablet's, and every floating mic keeps its whole flower on the
    screen (2026-11-21, the user's calls: "where should we put it to be usable, because it's pretty
    usable on tablet?", then "on tablet it would act the same, like the petal would always fit",
    and "after dragging I want it to reset a little bit faster to the not moving state"). Before,
    the phone's mic sat in a row of its own over the text box, and held there its down petal (now)
    fell mostly off the screen's bottom. Now:
    - On a phone's chat screen the mic floats over the chat (decision 94's mic): bottom middle,
      just over the composer, until moved through its move ring; its spot kept on this device. The
      list and the picture screens have none; while the on-screen keyboard is up it steps away. The
      mic row is gone, so the chat gets its room back. What the mic says (clips, hint, speaker)
      stands centred above its ring of voice messages.
    - On every device a floating mic is kept on the screen by its flower, not its button: its
      centre stays the petals' reach (116 px) from every wall, wherever it is put, dragged or kept
      (a screen too small for that one way keeps it in the middle that way). The flower stays
      centred on the button; the button moves.
    - After a drag it comes to rest sooner: the finger resting 200 ms (was 350) puts it down, and
      its spring settles in about 0.3 s (was 0.5 s) with less overshoot.
    Instead of: the row lifted so the down petal fits (a taller row on a small screen, a different
    mic than the tablet's), or a half flower above the button (four petals in 180°, a different
    gesture set). Files: `src/claudia/web/views/mic.cljs`, `src/claudia/web/views/pure/mic.cljs`,
    `src/claudia/web/views/shell.cljs`, `src/claudia/web/views/chat.cljs`,
    `resources/web/css/reach.css`.

111. The stall watch: an agent waiting on work that no longer moves turns red, and its chat is told
    (2026-11-21, the user's pick of three: "How can we make it so in the future Claudia can see
    these problems?"). That day an agent waited 16 minutes on a background shell running
    `ssh … 'while pgrep -f "…"; do sleep 15; done'`: `pgrep -f` matched the loop's own command line,
    so the shell never ended and never printed, and nobody saw it until the user asked. Now every
    2 s the runner looks at each chat's running agents: an agent is stalled when neither it (a
    step, progress) nor any of its running work (an agent's step, a shell's output file written)
    has shown a sign for 12 minutes, over the 10 minutes one foreground command may run, so a long
    command alone never trips it; a shell alone is never stalled (a server may print nothing for
    hours). A stalled agent's chip turns red, its line says "waiting on nothing · quiet 16m", and
    it turns back the moment a sign comes (Agent `stalled` in PROTOCOL.md). The chat that started
    it is told once per stall, as Claudia's note ("Claudia: The agent "…" has shown no sign of work
    for 12 min: no step of its own, and its shell `…` still runs but has printed nothing. Check
    that its work still runs …"), in `<claudia-note>` tags the system prompt names as Claudia's,
    never the user's approval; sent mid-turn it is taken in mid-turn, else it starts a turn. The
    stall told of is kept with the task, so a runner restart tells it no second time; an archived
    or moving chat is not told. Instead of: heartbeats from remote jobs (every job through one
    wrapper; can come later for work on other machines), or only a rule for agents (the failure
    was an agent not checking). Files: `src/claudia/slave/stall.cljs`,
    `src/claudia/slave/tasks.cljs`, `src/claudia/slave/chats.cljs`,
    `src/claudia/slave/history.cljs`, `src/claudia/slave/tools.cljs`,
    `src/claudia/web/views/agents.cljs`, `src/claudia/web/views/pure/agents.cljs`,
    `resources/web/css/agents.css`, `PROTOCOL.md`.

113. Opening the chat that is talking lets it finish (2026-11-21, the user: "when a chat is talking
    and I change to that chat ... the sound stops. This is not okay"). Decision 107's "read is
    heard" stopped the chat's reading on open. Now opening a chat marks its waiting voice messages
    heard, here and on every device, except the one sounding now: it plays on to its end and is
    marked heard there; the chat's other waiting messages leave the line (they are read on the
    screen). A hold on the mic or a circle still stops at once (decision 107). Files:
    `src/claudia/web/events.cljs`, `src/claudia/web/speech.cljs`,
    `src/claudia/web/views/pure/talk.cljs`, `src/claudia/web/fx.cljs`.

114. A running step group's count pops when it changes (2026-11-21, the user: "I want the counter to
    pop up like an animation when it changes"). While a folded group of tool steps runs, each new
    count ("12 steps") comes in big in the accent colour and springs to its size (0.4 s; none with
    reduced motion); a finished group never moves. Files: `src/claudia/web/views/steps.cljs`,
    `resources/web/css/claudia.css`.

## Back from the TypeScript Claudia (2026-11-03)

- Picture history: a picture per turn (SQLite `pictures`); the bar under the picture steps through them (‹ › ←/→, a scrubber), the newest is live, older ones read only; fold (Alt+P); the timeline view was removed 2026-11-14 (picture only); the camera copies or saves the picture as PNG.
- The camera's PNG of the picture (draw answers carry no screenshot since 2026-11-18: one per draw was about 3% of all tokens spent): playwright-core opens the page's render mode (`claudia.web.render`) from this checkout's `out/web` in a headless Chromium (`/opt/claudia-tools/ms-playwright`, else Playwright's own, else a system Chrome or `CLAUDIA_CHROME`).

91. The Drawing board is the table, every other view a sheet of paper on it (2026-11-21, the user's
    design). The Drawing board has no frame, title or dropdown: the picture's blocks sit straight on
    it, with its bar (‹ ›, the snapshot) under them, always the bottom layer. File Browser, Search,
    Settings, My words, Machines, Prompts and Forms each open as a sheet laid on it: the board's
    colour, set apart only by its edge and a shadow, with a title strip (the view's icon and name in
    the hand font) and an × that closes it. Sheets pile up: the one opened last lies on top, each one
    24 px down and 20 px right of the one under it (10 and 14 px on a phone, inside the picture
    screen), all one size; opening one already in the pile brings it to the top. Only the top sheet
    is drawn in full and takes input (the board under sheets is inert); the ones under it show their
    title strip and left edge, and a click there brings one to the top. Closing the top sheet shows
    the one under it; closing all shows the board. The events: `[:right/view v]` lays v on top (the
    Drawing board takes every sheet off), `[:right/toggle v]` closes v when it is the top sheet,
    else lays it on top, `[:right/close v]` takes v off; the sub `:right-view` is the top sheet's
    view (`:picture` when none), `:right-sheets` the pile, bottom to top. Opening a sheet closes the
    world; on a phone it brings the picture screen up; Files asks for the bookmarks again; a
    read-only view leaves Files out of the pile it shows. The pile is the person's on every device:
    `PUT /hub/right-view {views}` (bottom to top), answered and read back as `{views, view}` with
    `view` the top one for older pages, an older page's `{view}` setting a pile of that one view; the
    hub keeps the names space separated in `right_views.view`, so a pick kept before reads as a pile
    of one (pure: `claudia.shared.views`, tested on plain data). The File Browser's and My words'
    own headings went: the title strip names them. Instead of: one view at a time, picked in a
    "Drawing board ▾" dropdown over the right half (decision 75).
    Files: `src/claudia/shared/views.cljc`, `src/claudia/web/right.cljs`,
    `src/claudia/web/views/sheets.cljs`, `src/claudia/web/views/picture.cljs`,
    `src/claudia/hub/ops.cljs`, `resources/web/css/right.css`, `PROTOCOL.md`.

92. The mic rail: four slots over the mic, four under it, and none comes or goes (since decision 94
    the mic floats over the page and the rail keeps the eight without it) (2026-11-21, the
    user's design). Decision 73's rail on a touch tablet holds eight buttons of one size (64 px, at
    least 12 mm), each its icon with a short name under it, see-through at rest: top to bottom
    Search, Machines (System since decision 96), Prompts, My words (Settings since decision 95), then the mic, then Stop, New chat, ‹ (Previous picture),
    › (Next picture). The mic sits exactly at the rail's middle height: the same padding over and
    under, the halves equal, and the foot (the update pill, the profile circle, the version's line)
    at the bottom of the lower half, outside the eight; on short screens the gaps shrink to 0 before
    the foot loses its room. A button never appears or disappears, so nothing shifts and the mic
    never moves: one that cannot act now stays, passive (greyed, disabled): Stop while the open chat
    has no running turn (filled red while one runs), ‹ › with no earlier or later picture or the
    picture folded, My words without the hub's listen service or the composer, New chat where no
    new chat can be written (`claudia.web.views.pure.edge/slot-states`). The mic's circle keeps its
    size when the mic cannot work there. Machines, Prompts and My words open their view of the right
    half (`[:right/view …]`; My words no longer toggles back to the Drawing board). Archive left the
    rail (the chat header has an Archive button). The mic's empty circle, while the mic sits
    elsewhere, is a "↺ mic back" button: a press forgets the mic's spot on this device
    (localStorage) and the mic glides home. The profile menu gains Prompts and My words beside
    Machines and Forms, the way to them on desktops and phones (no rail there). Since 2026-11-21
    (the user's call) Search, System, Prompts and Settings are toggles: lit while their sheet is the
    top one, and a press then closes it (`[:right/close …]`); and Archive is back on the rail, under
    New chat (the open chat, asked first as the header's; Unarchive for an archived one; passive
    with no started chat of one's own open), so the rail holds nine, five under its middle. Instead of: buttons
    shown only when they could act (Stop popping in and out shifted the buttons over the mic), the
    ‹ › pair, archive on the rail, and the only way home for a moved mic a drag back onto its slot.
    Files: `src/claudia/web/views/thumb_edge.cljs`, `src/claudia/web/views/pure/edge.cljs`,
    `src/claudia/web/views/mic.cljs`, `src/claudia/web/views/menu.cljs`,
    `src/claudia/web/views/icons.cljs`, `resources/web/css/reach.css`.

95. Claude offers the words dictation misheard, as cards in the chat (2026-11-21, the user's design:
    "I never touch the text, so I won't correct my words"). When Claude sees that a word in the
    person's dictated message was plainly misheard ("get hub" for GitHub) and knows the word they
    meant, it calls `mcp__workspace__offer_word {word, heard}`. The step carries `word: {word,
    heard}` (`claudia.slave.history/offered-word`) and the chat shows a small card after it: "Add
    <word> to My words? heard: <heard>", Add and No. Nothing waits on it; the person scrolls past or
    taps. The card shows only in the person's own chats, only while their offers are on, and never
    for a word already in their list or one they said No to (`claudia.web.views.pure.dictated/
    card-shown?`, case ignored). Add puts the word in the dictating person's My words and marks it
    learned from the chat; No puts it on their No list. Both lists and the offers switch are the
    person's prefs, kept by the hub for every device: `GET/PUT /hub/listen/prefs {declined, learned,
    offers}` (table `listen_prefs`, `claudia.hub.listen-prefs`). Settings' Dictation section holds
    it all: the words (× removes one, "from chat" marks the learned ones, Add), "Offer words in the
    chat" (a switch), "Words you said No to (n)" with Offer again, and "Reset dictation profile"
    (asks once more, then clears the words and both lists; the switch stays). Removed: the My words
    sheet and its rail slot (Settings takes the slot, so the rail keeps its eight, decision 92), the profile menu's My words, and the composer's offer after a corrected word
    (`claudia.wordsOffered`). Since the same day the page also sends the chat's last words with each
    clip (`x-listen-context`), so names the chat uses are heard right in the first place. Files:
    `src/claudia/slave/tools.cljs`, `src/claudia/slave/history.cljs`, `src/claudia/slave/chats.cljs`,
    `src/claudia/hub/listen_prefs.cljs`, `src/claudia/hub/store.cljs`, `src/claudia/hub/ops.cljs`,
    `src/claudia/web/words.cljs`, `src/claudia/web/views/my_words.cljs`,
    `src/claudia/web/views/pure/dictated.cljs`, `src/claudia/web/views/pure/steps.cljs`.
97. Three features go: listen back, explain, and the changed-files tracking (2026-11-21, the
    user's call). Each cost code on the page, the runner or the host for something the chats no
    longer need:
    - Listen back (decision 20): the "▶ your voice" player and "heard as" under a sent dictated
      message. A heard clip leaves the device's IndexedDB as before listen back; its database
      `claudia-mic` goes to version 3, which deletes the store `sounds` with the recordings kept
      there. The mic's own clips (kept until their words went out, retries, Try again and Throw
      away) stay as they were. A send still carries the id the page made for its message (`msg`,
      PROTOCOL.md): the runner uses it as the message's id, and queued messages (decision 83) take
      one back by it.
    - Explain on a tool step: the "explain" button on each step and on the running one, and the
      runner's `POST /api/chats/:id/explain` with its Haiku call. The steps groups stay as they
      were. The 3D view's Explain on a part (it asks Claude in the open chat) is a different
      thing and stays.
    - The changed-files tracking (decision 8's file changes): the runner's copy of every file
      before Claude's Edit/Write first touched it in a turn (a PreToolUse hook) and its git
      snapshots of the chat's folder through an index of its own (`RUNNER_DATA/index`), the
      `changes` and `diff` frames, and the page's leftovers of the changes pill (gone since
      2026-11-11). A runner drops its table `changes` and deletes `RUNNER_DATA/index` when it
      starts; forks and server moves (decision 82) no longer carry file changes, and a move's check
      no longer counts them. The agents' Files tab is not part of it and stays: it reads an agent's
      Edit/Write calls from its own transcript (`claudia.slave.tasks`, the line counts moved there
      from `claudia.slave.changes`) and shows them in `claudia.web.views.diff`, which keeps only
      the one-column table it uses. The PostToolUse hook stays (a chat with no folder gets the
      edited file's project; Sentinel's worktrees hear of edits).
    Instead of: keeping them unused, or leaving the sounds, the table and the index files on disk.
    Files: `src/claudia/web/clip_store.cljs`, `src/claudia/web/views/mic_clips.cljs`,
    `src/claudia/web/events.cljs`, `src/claudia/web/views/steps.cljs`,
    `src/claudia/slave/chats.cljs`, `src/claudia/slave/chats/session.cljs`,
    `src/claudia/slave/store.cljs`, `src/claudia/slave/runner.cljs`, `src/claudia/slave/move.cljs`,
    `src/claudia/slave/tasks.cljs`, `src/claudia/web/views/diff.cljs`, `PROTOCOL.md`.

96. Machines becomes System (2026-11-21, the user's design, one call at a time: "another button next
    to files in the header called System"; "make it nice designed"). One sheet for everything about a
    server, for troubleshooting: the header's System button (beside Files, toggles it like Files) and
    the edge's and the menu's old Machines entries open it; a stored pile, rail slot or link that says
    `machines` opens System. On top, one tab per server the person may see (the gate for admins), each
    with a dot: red = a failed unit, a crash or an out-of-memory kill in the last hour; amber = a port
    open on all addresses that no unit, container or chat owns, a leftover, memory or a disk over 85%
    or waiting over 10%; green otherwise; grey = not reached. Under it four tiles, each the number now
    over a 24-hour line from the history: CPU as busy cores of all ("3.7 of 16 cores busy (23%)", the
    user: "is that the full CPU or only one core?"), every core as a bar, the busiest process and its
    owner; Waiting (the kernel's pressure numbers: the share of the last 10 s programs waited for CPU,
    memory or disk; macOS has none); memory with the 90% cap as a line; the fullest disk and the
    cards' memory. Then "Needs a look": only problems and the chats' proposals, each with its one
    action (Log, Ask a chat, Accept / No), "All quiet" when none. Then tabs: Ports (port, reach: all
    addresses / LAN / Headscale / this machine, the process and its owner, the project), Services
    (grouped: Claudia, projects, containers, the system's own folded), Processes (grouped by owner:
    chats with their colour and title, units, containers, then leftovers), History (7 days, a sample a
    minute: busy cores, memory, waiting, with crashes, out-of-memory kills and failed units marked),
    Log (those events, newest first), Machine (the old Machines card unchanged: every core, disk and
    network speed, temperatures). Categories come by themselves from each process's cgroup (its
    unit, container, chat or login session) and the unit file's place; projects come from labels on
    the hub (`system_labels`), per server or for all; something with no label is "unknown", itself the
    hint to look. A chat proposes a label with its `system_label` tool and the person accepts or says
    no on System (the user: "the chat could help me in the long run ... if I accept these changes");
    nothing a chat proposes applies by itself, and System never stops, restarts or kills anything:
    Ask a chat puts the row's facts in the composer and the chat goes through sudo as always
    (decision 52). Ports of other users, user units, chats' process keys, the journal and core dumps
    need root: the root helper answers the front on a read socket of its own with a fixed list of
    reads (PROTOCOL.md), so no tap is needed and no caller's argument reaches a command but a checked
    unit name; without the helper (macOS, the gate) System shows what Claudia's own user can see and
    says so. Each front keeps 7 days of minute samples in its SQLite whether or not anyone looks, so a
    crash at 3 am still shows in the morning. System shows program names, never command lines (they
    can hold secrets); who may see a server's System is who may see its Machines card (decision 12);
    labels are written by admins. Instead of: a second sheet beside Machines (the same numbers
    twice), a hand-written list only (anything new invisible), live numbers only (nothing to trace
    back), sudo taps per read (a tap a minute), history on the hub (gone when the hub is down).
    Files (the page): `src/claudia/web/views/systems.cljs` (the sheet and what it asks),
    `src/claudia/web/views/system_tiles.cljs`, `system_look.cljs`, `system_tabs.cljs`,
    `system_lists.cljs`, `system_history.cljs`, `system_bits.cljs`, `system_machine.cljs` (the old
    card), `src/claudia/web/views/pure/system_feed.cljs`, `system_look.cljs`, `system_tiles.cljs`,
    `system_lists.cljs`, `system_chart.cljs`, `src/claudia/shared/views.cljc` (`machines` read as
    `system`), `src/claudia/web/views/head.cljs`, `resources/web/css/system.css`.

