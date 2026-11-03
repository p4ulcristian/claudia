# Parity: the page shell (design and behaviour)

Reference: the TypeScript Claudia, branch main = aeb57a1 (`web/`). Beta: this branch.
Marks: [x] present and identical, [~] different (how), [ ] missing. Initial marks are what beta had
before this branch; the final column of notes says what was done.

## Base, theme, fonts (web/style.css:1-53, index.html)
- [x] Palette variables `--bg … --accent`, `--c-*` chat colours, `--on-hue` (style.css:4-17)
- [x] Fonts: Atkinson Hyperlegible, Kalam, JetBrains Mono from Google Fonts (index.html:17)
- [x] Chat colour = whole-window theme, `:root[data-chat]` oklch mixes of `--hue` (style.css:24-32; app.tsx:876-885)
- [~] The theme follows `settings.color` of the open chat; beta read the row's colour only (no theme on a new chat that already has its colour)
- [x] No scrollbars anywhere (style.css:34-35)
- [x] html/body one layer, `--vv-*` viewport variables, standalone 100lvh (style.css:41-43, 890-904; viewport.ts)
- [x] `button`, `input`, `.muted`, `.small` resets (style.css:44-50)
- [ ] `.link` (blue underlined small) in the shell (style.css:51) (was only in agents.css)
- [~] `.primary` weight 600 / chalk fill (style.css:52-53, 802); beta 700
- [ ] Keyframes `breathe blink rise fade pop ring glow fade-in found-flash chalk-dot tip-in confirm-*` (style.css:283-289, 407, 465, 752, 886)

## Layout (style.css:55-75, 823-847; sidebar.css:120-130; app.tsx:953-1265)
- [~] Grid `rail (88px) | main.split | right rail (64px)`, always a rail on a desktop; beta: a 300 px list column that folds to a 64 px rail
- [ ] The full list opens as a drawer (fixed, 340 px; 380 px with Settings) over the rail, shadow `4px 0 24px`, with a backdrop (rgb 0 0 0 / .35) that closes it (style.css:63, 291; sidebar.css:122-126; app.tsx:974)
- [ ] The drawer closes when a chat is picked or a new chat starts (app.tsx:643); never stored (app.tsx:318)
- [~] Menu toggle: `.icon.menu-toggle` fixed top-left, 32px chalk circle on hover only, burger `M3 4.6h12M3.4 9h11.3M3 13.4h12`, centred over the closed rail (style.css:66, 815-821, 905-906; app.tsx:984-986)
- [ ] Ctrl+B toggles the list (app.tsx:758-761); Alt+N new chat (app.tsx:755)
- [~] The split: `max(300px, min(chatW, calc(100% - 286px))) 6px minmax(280px, 1fr)`, chatW 400 until dragged, the drag kept in localStorage `chatW` (app.tsx:74, 863-873, 1093); beta: fixed `minmax(340px, 400px)` and no divider
- [ ] `.divider`: a 2px chalk line between the halves (3px accent on hover), col-resize drag (style.css:73-74, 783-786; app.tsx:1195)
- [ ] Folded right half: `minmax(300px, 1fr) 6px 16px`, the divider hidden (app.tsx:1093; style.css:838)
- [ ] Right rail column (64px) for the forms (app.tsx:1251-1264; style.css:1266-1267): the slot; its contents are the code-forms area
- [x] Top safe area on the split, rail, sidebar (style.css:895-907)

## The rail (style.css:420-474, 1330-1341; app.tsx:988-1019)
- [~] `.rail`: 88px column, `padding: calc(46px + top-safe) 0 10px`, bg2, chalk line on its right (style.css:421-422, 480-481, 900); beta 64px, padding 52px
- [~] `.rail-dots`: gap 18px, padding 6px 0 22px (style.css:423); beta 16px
- [~] `+` and search circles: chalk dashed rings `--chalk-c34-18-dash`, muted (style.css:470-473); beta: CSS dashed border
- [~] Search from the rail opens the drawer with the search focused; Escape or a pick folds it back (app.tsx:321-333, 1038)
- [~] `.rail-chat`: circle, title (2 lines, 600 10.5px sans), server badge (chalk r6-15, hand 11.5px) (style.css:425, 1332-1339)
- [ ] Passive chats last, faded .55, the first with margin-top auto, a dashed chalk line after the last (style.css:446-449; app.tsx:906, 999)
- [ ] Spotlight: the open chat glows, others fade (logo/tag/name/server .35 + saturate .4) until hovered (style.css:451-455)
- [ ] Off server: `.rail-chat.off` opacity .4 grayscale .8, hover .75 (style.css:1535-1536)
- [ ] Hover bubble with the chat's title · project · server (app.tsx:1003; tip.tsx)
- [ ] A picked chat nods (translate y), the open one shakes (app.tsx:671-680)
- [ ] Circles slide to their new places when the order changes; a new one grows in (app.tsx:1284-1305)
- [ ] The open chat stays scrolled into view in the rail and the list (app.tsx:833-840)
- [ ] Sound toggle (speaker icon, on = accent) on the rail (app.tsx:1012-1014, 1274-1282; style.css:1238-1239)
- [ ] Forms button (shapes icon) on the rail (app.tsx:1015-1017; style.css:1231-1233)
- [~] Rail meters at the foot: ctx ring (click compacts, right click menu) and every limit ring (usage.tsx:166-187; style.css:427-441)

## A chat's circle (chatdot.tsx; style.css:443-471, 754-765; agents.css:94-97)
- [x] Chalk ring `--chalk-c34-22` in the chat colour, faint fill (style.css:443-444)
- [~] Face: the project's logo (22px), else its short tag (`projectTags`, 2-3 letters, sans 10px), else a small blank dot (chatdot.tsx:27-33, 60-74); beta: the title's first letter and a letter tag
- [x] State badges: `?` waiting (breathe ×4), three dots working, `✓` done (style.css:457-465)
- [ ] Agent moons: up to 5 chalk dots round the circle while the turn runs with agents (chatdot.tsx:35-47; style.css:762-765)
- [x] Agents ring (dashed amber) + count badge after the turn (agents.tsx:436-446; agents.css:94-97)
- [~] Badges animate for good only on the open chat (style.css:466-469)

## The chat list (sidebar.tsx; sidebar.css)
- [~] `.cs-pane` padding `calc(12px + top-safe) 10px calc(8px + bottom)`, gap 10px (sidebar.css:9-10); beta sidebar gap 8
- [~] Header: title (hand 19px; a menu button when there are people or servers), limit rings, gear, + (desktop only) (sidebar.tsx:226-237; sidebar.css:18-54); beta title 21px, the gear and + look differ
- [x] Title menu: Whose chats / Servers with ticks, notes (sidebar.tsx:76-128)
- [~] Title menu closes on outside pointerdown and Escape (sidebar.tsx:79-89); beta: an invisible full-window layer
- [~] Search box: chalk outline (`.search-box`, r8-2), accent on focus (style.css:799-801); beta: a plain border
- [~] Rows `.chat-row.cs-row`: circle, title 14px 500 (700 unseen), lock when read only, server mark, `ago` time (now / 5m / 3h / 2d / Oct 3), second line (sidebar.tsx:154-176); beta: clock times
- [~] Second line: "Asks you: …", "Needs your permission: …", "Waiting for you", "Claude working · N agents", "Agents working · N", "Agents finished", the last reply, "Stopped", "Ended with an error" (sidebar.tsx:133-146)
- [ ] Hover bubble on a row only when its title is cut (sidebar.tsx:162; tip.tsx:15-27)
- [x] Search: two letters search what was said; hits under each chat (you / claude, marked words) (sidebar.tsx:249-268)
- [ ] Title filter while typing (app.tsx:902-903)
- [ ] A pending new chat row "New chat" with a blank dot (sidebar.tsx:271-273)
- [~] Empty texts "No chats yet." / "Nothing archived." / "No match." (app.tsx:1044)
- [x] Off-server notes over the list (sidebar.tsx:248; style.css:1539)
- [x] Back to chats (archived view) (sidebar.tsx:238-243)
- [x] Phone: + floats bottom right (sidebar.tsx:276-280; sidebar.css:150-153)

## Settings (sidebar.tsx:285-391; settings.tsx; hub.tsx:72-96; sidebar.css:83-117)
- [~] Settings slides in over the list (st-pane from the right, the list 30% left), Escape goes back (sidebar.tsx:321-334; sidebar.css:8-16); beta: replaces the list, no slide
- [x] Header ‹ Chats, Settings (sidebar.tsx:337-343)
- [~] Brand: logo, wordmark, instance chip on its own row (style.css:553-563; settings.tsx:131-160)
- [~] Plan meters: Context bar, the plan label (hand 15px, a select with saved tokens), each limit with reset (usage.tsx:94-124)
- [~] Groups: Archived chats (archive icon) + Show all ~/Work sessions; Sounds + Desktop popups; Forms (shapes icon); Hub: Servers, Access (sidebar.tsx:347-371)
- [ ] Server pick as `.profile-tabs` with the server's name, version, commit, needs-update (hub.tsx:72-96; style.css:586-591, 1559-1562)
- [~] Server settings: signed-in line, Chat colours radio, Claude subscription, GitHub (settings.tsx:68-128)
- [x] Switch look (st-knob) (sidebar.css:100-106)

## The chat column header (app.tsx:1098-1135; style.css:93-99, 384-396, 489-493, 840-845, 1223-1235)
- [~] `.chat-head` 58px, padding 10px 16px, no border (style.css:93, 489)
- [ ] Colour dot (14px) opening a palette of 18 swatches + none (colors.tsx; style.css:385-396)
- [~] Title: hand 22px, underline 3px accent offset 6px on `.t`; click renames (pencil ✎ on hover) (app.tsx:1107-1125; style.css:1224-1235); beta: underline on the h1, rename only from the menu
- [ ] Server chip and folder chip (`~/…` clipped to 28) as mono 11.5px muted text (app.tsx:1126-1127; style.css:350, 493, 843-845)
- [~] Activity pill (changes) chalk r14-2 (activity.tsx; style.css:727-743, 813)
- [~] "reconnecting…" / "<server> is off" in amber (app.tsx:1129)
- [~] Beta extras not in the old header: the context ring and the ⋯ menu (removed: the ring is on the rail, the menu is the right click)
- [x] Phone: ‹ Chats with a count (waiting amber) and the picture button (app.tsx:1099-1105, 1130-1134)

## Messages (chat.tsx:197-331; style.css:101-172, 327-328, 495-497, 618-620)
- [x] `.messages` padding 18px 18px 120px → 15px/1.6 (style.css:101, 497)
- [~] Hello: "What are we working on?" / "Claude answers short here and explains on the right." (chat.tsx:271); beta: "New chat" + other words
- [x] User bubble: hand 18px accent, right aligned (style.css:495)
- [ ] Links under a user message: "fork from here" (hover only), "picture · N files" / "back to live" (chat.tsx:292-301; style.css:112-116)
- [~] Claude's Markdown: `.md` code with border, tables, pre in `.code` with copy (style.css:120-133)
- [~] Tool steps grouped: `› N steps  names / current step`, `N failed`, a blue left line while busy; open: one row per step with dot, name, title, explain (chat.tsx:73-131; style.css:135-168); beta: one flat line per step
- [~] Explain: hand-written "explain" button on hover (always on the running step), a chalk note with × (chat.tsx:64-71, 86-88; style.css:143-158)
- [~] Working: pulse, the note or "Working", " · 12s" elapsed (chat.tsx:313-317; style.css:170-171); beta: hand font "Working…"
- [x] Streaming cursor ▍ (style.css:118)
- [~] Queued messages below with "queued" (chat.tsx:318-326); beta "sending"
- [ ] "Stopped." after a stopped turn (chat.tsx:327)
- [~] Error line (style.css:172); beta adds a pointer
- [ ] Notes: `pre.note-line` dashed mono (chat.tsx:305-308; style.css:328); beta: centred italic
- [~] "Loading earlier messages…" line (chat.tsx:270; style.css:105)
- [ ] Search hit flash on the message (style.css:406-407)
- [x] Images shown and sent media (chat.tsx:148-195; style.css:946-952)

## Composer (chat.tsx:374-576; style.css:174-186, 295-325, 499-503, 926-947)
- [x] Chalk line over it, chalk input box (r10-2), accent when focused (style.css:500-503, 795-796)
- [~] Placeholder "Message Claude…" / "Add to this turn…" / "Answer on the right, or type an answer here…" (chat.tsx:550); beta "Start a chat…" / "Reply…"
- [~] Toolbar order: pickers, attach (paperclip path), archive icon, Stop, Send (chat.tsx:563-572); beta: clip, pickers, spacer, Stop, Send
- [ ] Archive / Unarchive icon button in the toolbar (app.tsx:1184-1190; sidebar.tsx:393-402)
- [x] Model and effort pickers (usage.tsx:72-92; style.css:296-301)
- [~] Slash menu (style.css:320-325): `.slash-menu b` plain; beta mono
- [x] Attachments: file chips, thumbs, drop veil "Drop to attach" (style.css:926-947)
- [ ] "New chat on" server row over the composer of an unstarted chat (hub.tsx:41-62; style.css:1542-1554)
- [~] Read-only note "<Name>'s chat · read only" (app.tsx:1159; style.css:592)

## Questions and permissions (panels.tsx; style.css:187-217, 576-616)
- [ ] The question fills the window as a modal (`.ask-modal`, blurred backdrop, panel up to 1040px, options in columns) (style.css:603-616; app.tsx:975-983)
- [ ] Option numbers 1-9, Enter answers, Esc / "See picture" peeks, "Claude asks · answer" pill brings it back (panels.tsx:27-63, 128-132; style.css:616)
- [~] "Something else…" field, "pick one" / "pick any", preview of the hovered option (panels.tsx:107-121)
- [~] Buttons "See picture" + "Answer" with the key hint (panels.tsx:128-132); beta: "Send" only
- [x] The secret card (beta's own; amber)
- [~] Permission panel `.panel.permit` amber (panels.tsx:136-153): the beta runner asks no permissions

## Menus and popups
- [x] Chat menu (right click): title, swatches, Make passive/active, Rename, Fork, Archive (chatmenu.tsx:44-104; style.css:409-418)
- [~] Menu kept inside the window layer (chatmenu.tsx:7-29)
- [ ] Context menu "Compact chat" on right click of the ctx ring (chatmenu.tsx:32-40)
- [x] GitHub prompt (github-prompt.tsx; style.css:567-574)
- [~] Hover bubble (tip.tsx; style.css:746-752) plus the chalk SVG mask defs `#chalk-grain`, `#chalk-grain-soft` (tip.tsx:45-62)
- [x] Sounds: bells per colour (sounds.ts); popups (popups.ts)

## Agents (agents.tsx; agents.css)
- [x] Chips under the title, +N more, ✓ N ✕ M (agents.tsx:110-162)
- [x] Agent screen over the chat column (agents.tsx:212-232)
- [~] Agent steps in the chat's compact form (Transcript) (agents.tsx:367-372)

## Chalk (chalk.ts; style.css:767-821)
- [~] Baked shapes: beta lacked `r18-2 line3 line3-soft dot9-soft dot11 bar3`
- [~] Chalk outlines on `.new .search-box .primary .stop .actions button .prompt-actions button .act-pill .menu-toggle:hover` with `!important` fills (style.css:774-813)
- [x] Firefox fallback (style.css:788)

## Phone (style.css:848-916; sidebar.css:132-162)
- [x] Three screens list → chat → picture, each sliding over the one before (30% under it)
- [~] The picture screen: bg `var(--bg)`, shadow; the rbar at its foot (style.css:878-884)
- [~] List sizes: title 25px, rows 66px, title 16px, line 14px, + FAB 56px (sidebar.css:134-153)
- [x] Inputs 16px on phones (style.css:911)
- [~] Swipes (swipe.ts): the same moves; Settings back by swipe

## Admin pages (hub.tsx:140-303; style.css:534-551, 586-592, 1564-1588)
- [~] Dialog as `.settings.hub-admin` with `.profile-tabs` header and × (hub.tsx:155-171)
