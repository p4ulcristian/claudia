# Parity: the page shell (design and behaviour)

Reference: the TypeScript Claudia, branch main = aeb57a1 (`web/`). Beta: this branch.
Marks: [x] identical, [~] different (how), [ ] missing. Each line: what the old page does (file:line),
then what was done here when beta lacked it or differed. Compared by screenshots at 1440x900 and
390x844 (deviceScaleFactor 2), old page and beta hub side by side on the same chats.

## Base, theme, fonts (web/style.css:1-53, 776-821; index.html)
- [x] Palette variables, `--c-*` chat colours, `--on-hue` (style.css:4-17)
- [x] Fonts: Atkinson Hyperlegible, Kalam, JetBrains Mono (index.html:17)
- [x] The open chat's colour tints the whole window, `:root[data-chat]` (style.css:24-32)
- [~] A new, unsent chat has no colour: the old runner gave it one at once (prefs "a colour of its own"); the beta runner has no prefs and colours a chat when it is made
- [x] No scrollbars; html/body one layer; `--vv-*`; standalone 100lvh (style.css:34-43, 890-907)
- [x] `.link`, `.primary` (600, chalk fill), `.muted`, `.small` (style.css:49-53) — ported
- [x] Every keyframe of the shell (style.css:283-289, 407, 465, 752, 886, 1256-1257) — ported
- [x] Chat Markdown code coloured with highlight.js GitHub Dark (style.css:1; md.ts) — md.cljs highlights (common languages + Clojure + Dockerfile), the theme scoped to `.md`

## Layout (style.css:55-75; sidebar.css:120-130; app.tsx:953-1265)
- [x] Grid `rail 88px | main.split | 64px forms rail` on a desktop — shell rewritten
- [x] The full list as a drawer (fixed, 340 px; 380 px with Settings) over the rail, shadow, backdrop that closes it
- [x] The drawer closes when a chat is picked or a new chat starts; never stored
- [x] Menu button: chalk ring on hover only, the old burger, centred over the closed rail
- [x] Ctrl+B (⌘B) toggles the list, Alt+N new chat, Escape stops a running turn (app.tsx:747-765)
- [x] Split `max(300px, min(chatW, calc(100% - 286px))) 6px minmax(280px, 1fr)`, chatW 400, the drag kept in localStorage `chatW`
- [x] `.divider` chalk line, accent and 3px on hover, col-resize drag
- [x] Folded right half `minmax(300px, 1fr) 6px 16px`, divider hidden
- [x] Right rail column: the slot (`nav.rail.right-rail`); its forms are the picture branch's (`picture/right-rail`, merged there)
- [~] The fold's sliding motion (useFoldMotion, app.tsx:1307-1363): the grid switches at once (the picture branch owns the fold edge)

## The rail (style.css:420-474, 1330-1341; app.tsx:988-1019)
- [x] 88 px, padding `46px + top-safe`, chalk line right; dots gap 18px
- [x] + and search as dashed chalk circles; search opens the drawer with the box focused, Escape folds it back
- [x] Circle, title (two lines), server badge on the hub
- [x] Passive chats last, faded, the first pushed down, a dashed line after the last
- [x] Spotlight: the open chat glows, the others fade until hovered
- [x] Off server greyed
- [x] Hover bubble: title · project · server (screenshot cmp-tip identical)
- [x] Nod on pick, shake on the open one (Web Animations)
- [x] Circles slide to new places, a new one grows in (useSlide)
- [x] The open chat scrolled into view in the rail and the list
- [x] Sound toggle (speaker, accent when on) and Forms button at the foot
- [x] Rail meters: ctx ring (click compacts, right click: the compact menu) and every limit ring

## A chat's circle (chatdot.tsx; style.css:443-471, 754-765)
- [x] Chalk ring in the chat colour, faint fill
- [x] Face: the project's logo, else its short tag (projectTags), else a small dot — dot.cljs rewritten (beta drew the title's letter)
- [x] State badges ? / dots / ✓ (✓ never on the open chat or read only)
- [x] Agent moons while the turn runs; the agents ring after it
- [x] Badges animate for good only on the open chat

## The chat list (sidebar.tsx; sidebar.css)
- [x] Pane padding and gaps; header: title (menu when there are people or servers), limit rings, gear, + (desktop)
- [x] Title menu closes on a click outside or Escape (no invisible layer)
- [x] Chalk search box, accent when focused
- [x] Rows: circle, title 14px (700 unseen), lock, server mark, `ago` (now/5m/3h/2d/Oct 3), second line (Asks you: … / Waiting for you / Claude working · N agents / Agents working · N / the last reply; a blank line kept)
- [x] Order: as the chats were started, a new chat last (the runner's Row got `started`)
- [x] Hover bubble only when a title is cut
- [x] Search hits under each chat, marked words, a hit opens the chat and flashes the message (found-flash), older pages fetched until it is there
- [x] Title filter while typing; pending new chat row; "No match." / "Nothing archived." / "No chats yet."
- [x] Off-server notes; Back to chats; phone + FAB
- [~] All ~/Work sessions: beta lists them as their own section (the beta runner's API); the old mixed them into the list

## Settings (sidebar.tsx:285-391; settings.tsx; hub.tsx:72-96)
- [x] Slides in over the list (the list 30% left), Escape goes back, mounted for its slide out
- [x] Brand with the chip on its own row; plan meters with the Context bar (right click: compact), the plan label (a select with two saved tokens or more), each limit's reset
- [x] Groups: Archived chats (box icon) + all sessions; Sounds + Desktop popups; Forms (shapes icon); Hub: Servers, Access
- [x] Which server's: `.profile-tabs`, then name · Claudia version commit
- [x] Signed in as … · profile, Claude subscription, GitHub
- [ ] "Chat colours" (each new chat its own colour / default look): the beta runner has no `/api/prefs` (runner area)

## Chat header (app.tsx:1098-1135; style.css:93-99, 384-396, 489-493, 840-845, 1223-1235)
- [x] 58px, no border; colour dot with the palette (18 swatches + none); a dashed dot on a new chat
- [x] Title in Kalam 22px underlined on `.t`, click renames, ✎ on hover
- [x] Server chip and folder chip (`~/…` clipped to 28) as quiet mono text, the folder hidden under 600px
- [x] Changes pill: the turn looked at ("This turn" / "Last turn with changes" / "Earlier turn"), dropdown closes on outside click or Escape (beta's turn picker removed)
- [x] "<server> is off" / "reconnecting…"
- [x] Beta's context ring and ⋯ menu removed from the header (ring on the rail, menu on right click)
- [x] Phone: ‹ Chats with a count, the picture button (lit for a new picture)

## Messages (chat.tsx:197-331)
- [x] Padding 18/18/120, 15px/1.6; hello "What are we working on?"
- [x] User bubble in Kalam 18px accent, as Markdown; sent photos as `.sent-media` thumbs (big on click); files as chips
- [x] "fork from here" (hover only where hover exists), "picture · N files" / "back to live"
- [x] Tool steps folded: › N steps, names (Draw, Folder, Ask, mcp names without the prefix) or the current step, N failed, blue line while busy; open: a row per step
- [x] explain: hand-written button (always on the running step), chalk note with ×
- [~] A step's input and output under its row: the beta wire carries no detail/result (runner area); shown when a Msg has them
- [x] Working: pulse, "Working" / "Waiting for your answer", · elapsed (from when the turn was first seen running)
- [x] Queued messages below, "queued"; notes as dashed mono `pre.note-line`; error line; streaming cursor
- [~] "Stopped." after a stopped turn: the beta runner does not say a turn was stopped

## Composer (chat.tsx:374-576)
- [x] Chalk line over it, chalk box, accent when focused
- [x] Placeholders "Message Claude…" / "Add to this turn…" / "Answer on the right, or type an answer here…"; typed text answers an open question
- [x] Toolbar: pickers, attach (old paperclip), archive/unarchive icon, Stop, Send
- [x] Pickers on a new chat too (sent with `settings` once the chat is made); the running model's name, else the server's default model when its meta has one
- [~] The model picker shows "Default model" where the old said "Opus 5.5 1M": the beta meta has no `defaultModel` (runner area)
- [x] Slash menu as the old; file chips with progress, photo thumbs (64px, ×), "Drop to attach" veil
- [x] "New chat on" server row over the composer of an unstarted chat (hub, two servers or more)
- [x] Read-only note "<Name>'s chat · read only"

## Questions (panels.tsx; style.css:187-217, 603-616)
- [x] A modal over the window (blurred), panel up to 1040px, options in columns (screenshot beta-ask)
- [x] 1-9 pick, Enter answers, Esc / "See picture" peeks, "Claude asks · answer" pill brings it back (tested on a throwaway chat, archived after)
- [x] "Something else…", pick one / pick any, the hovered option's preview, key hint, See picture + Answer
- [x] The secret card (beta's own) in the same modal, amber
- [~] Permission panel: the beta runner asks no permissions

## Menus and popups
- [x] Chat menu: swatches (+ no colour, which beta lacked), passive, rename, fork, archive; kept inside the page layer
- [x] Context menu "Compact chat" on the ctx ring and the Context bar
- [x] GitHub prompt as the old settings card
- [x] Hover bubble (identical); chalk SVG masks `#chalk-grain`, `#chalk-grain-soft` (made with the baked chalk)
- [x] Sounds per colour, popups, the two switches

## Agents (agents.tsx; agents.css)
- [x] Chips under the title, +N more, ✓ N ✕ M, the agent screen (task-stop as the old)
- [x] An agent's steps in the chat's compact form (steps.cljs transcript)

## Chalk (chalk.ts; style.css:767-821)
- [x] Every shape of the old set (r18-2, line3, line3-soft, dot9-soft, dot11, bar3 added)
- [x] Chalk outlines on new, search box, primary, stop, actions, prompt buttons, act pill, menu button hover

## Phone (style.css:848-916; sidebar.css:132-162)
- [x] list → chat → picture, each sliding over the one before, the one under 30% left; the picture screen on `--bg`
- [x] List sizes (title 25px, rows 66px, 16/14px, FAB 56px); inputs 16px
- [x] The app layer clipped (`overflow: clip`): focusing the composer no longer scrolls the screens sideways
- [x] Swipes; Settings swipes back over the list

## Admin pages (hub.tsx:140-303; style.css:1564-1588)
- [x] The settings card with `.profile-tabs` and ×, the tables and forms as the old
