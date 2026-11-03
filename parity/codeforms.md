# Parity checklist: the code forms

Old = the TypeScript Claudia (branch main, aeb57a1). Beta = this branch. [x] identical, [~] different
(how), [ ] missing. Compared by screenshots of the Forms gallery (every form on its sample) and of the
same chats in both (their last old picture copied into a throwaway runner), at 1440x900 and 390x844;
screenshots in /tmp/pcf/shots (old-*, beta-*, merged-*).

## Code colours
- [x] Shiki, VS Code's grammars, dark-plus theme, loaded from jsdelivr on first use, each language
  when first needed; aliases cljs/cljc/edn/bb -> clojure, mts/cts -> ts, h -> c (web/shiki.ts) —
  claudia.web.shiki. highlight.js no longer colours any code form (it stays for the chat's markdown).
- [x] Token font styles (italic/bold/underline bits) on code and diffs; colour + italic on calls lines;
  colour only on clojure sources and flow cards (forms.tsx:196, calls.tsx:142, clojure.tsx:171, flow.tsx:125).

## Code view (files' opened file, legacy "code" form) — forms.tsx:156-288
- [x] Rows from data.line, diff +/- signs (removed lines unnumbered), "−" sign glyph.
- [x] Folding over 60 lines: 4 around marked/noted lines, else the first 40; "⋯ n lines" buttons;
  fold/unfold all; copy -> "copied" for 1.2 s (copies without removed lines).
- [x] Head: dir + bold name + ":first–last", language, fold/unfold, copy; error line.
- [x] Marked lines (.hot, amber chalk line3 bar), notes pinned under lines (.f-pin chalk box, colour).
- [x] Legacy form "code" still renders (forms.tsx:635).

## Files — forms.tsx:290-614, src/files.ts
- [x] Chalk folder icons (open/shut), extension tags coloured by kind, line counts, git status letters
  with tips, "•" changed-inside on folders, marks (colour, note), planned tag.
- [x] Crumbs from ~ (each a way up), double-click a folder to go in, "↺ back", collapse all, refresh,
  "hide hidden" (localStorage files.hideDot; pointed paths stay).
- [x] on disk / sketch · not on disk / sketch · + n planned | real switch / example kinds.
- [x] Live: open folders and files read again every 2.5 s while the page is seen (mtime "since").
- [x] Claude's file opens with its ancestors, scrolls to the first hot line or note; the one named before
  closes unless the user opened it.
- [x] Images: shown inline, double-click big, Escape/click closes; served with their content type
  (beta sent application/octet-stream before: fixed).
- [x] Order: folders first, names by localeCompare (beta sorted by code point: fixed, page and runner).
- [x] Reads allowed inside roots of the chat's earlier pictures too (runner.ts:454; beta: current only, fixed).
- [x] A network hiccup doesn't show as an error row (old ignored failed fetches).
- [~] Claude's render page: a live files block says what it shows ("Shown to the user live from disk
  ...: root; open: file, lines ...") instead of the old "Open this chat to browse its files." Kept from
  beta (README decision 2026-11-03): Claude's screenshot carries what the user sees.

## Git — git.tsx, src/git.ts, src/git-layout.ts
- [x] Lanes and trunk colours: one layout (claudia.shared.git-layout) for the runner and the gallery;
  local tips coloured before remote ones (the gallery's sample coloured origin/shops blue: fixed, violet).
- [x] Rows spaced by time (log scale, gap labels), WORKING dashed node, HEAD node, ref chips with
  ⎇/◆, sync marks ✓ ↑n ↓n gone ⌂ ☁, marks with notes, "⋯ more commits".
- [x] Branch bar: kind ⇅/⌂/☁, colour dot, sync, separator before remotes, click hides/shows, "show all".
- [x] Header: repo path, ⎇ branch / detached, "fetched … ago" (missing in beta: added), live / example.
- [x] Dirty counts as the old (a file staged and changed counts twice); dirty names as the old
  (a nested repo's "dir/" shows an empty name, as old).
- [x] Stale branch filter: only names that still exist (beta passed unknown names to git log: fixed).
- [x] Commit view: subject, hash · author · date · n files +a −d, merge note, body, files with
  status/rename/+−, small commits open, big start folded; RANGE meta.
- [x] Big commits: over 20 000 changed lines no patch, every file loads alone; over 1.5 MB only the
  first files (beta lacked the line limit: added). WORKING: untracked files' patches against /dev/null,
  untracked file load falls back to --no-index (missing in beta: added).
- [x] Diff: hunk rows "⋯ line n · …", Shiki colours with word-level marks cut into the tokens (beta lost
  the colours on lines with marks: fixed).
- [x] Hash prefix matches from 4 characters (beta: 2).
- [~] Gallery sample only: the WORKING row's time is the head commit's, not now (the old put
  "56.8y" between the sample's WORKING node and its commits). Live repos use now, as the old.

## Clojure — clojure.tsx, src/clojure.ts
- [x] Layout: layers by longest path, back edges red/dashed, sweeps ordering, bubble size, prefixes as
  ringed bubbles "›", "(itself)", single-bubble prefix opens straight, colours by sorted id.
- [x] Direction: picked by shape (0.8 aim, full screen by window) or fixed; "auto"; an empty map picks
  "↓ down" like the old (beta said "→ across": fixed).
- [x] Zoom at the pointer (Ctrl/⌘ wheel, any wheel in full screen), buttons at the middle, "fit",
  viewBox moved on the element, chalk off while moving (.moving) (beta zoomed at the middle: fixed);
  pan by drag with pointer capture when zoomed; click after a drag swallowed.
- [x] Full screen (the block), Esc leaves; map height from the block width (≤1.1×).
- [x] Panels: namespace (doc, requires/external, required by, vars with kinds/private/args/docs, marks,
  source with row numbers), arrow (functions used, ×n, ← from). Crumbs, counts, legend.
- [x] Runner: clj-kondo once per root, fingerprint, last index answers while one runs; order of uses
  and callers by localeCompare (beta: code points, fixed).

## Calls — calls.tsx, src/calls.ts
- [x] Tabs A · Unfold / B · Neighbours / C · Map with hints; header "name · short var".
- [x] Unfold: nested cards per call under the calling line, "called by" chips re-root, "↺ back to".
- [x] Neighbours: trail, callers with calling lines (30, "… n more callers"), callees with doc lines,
  mark dots.
- [x] Map: depth steppers 0..8, legend by folder, columns by distance, boxes sorted by folder then name
  (localeCompare), arrows measured after layout (back arrows under the map), selection lights arrows,
  card below, "+ n more" cut.
- [x] Code lines: Shiki colours kept on lines with call links (beta dropped them: fixed); long vars
  fold to first 3 + calls ±1 + last.
- [x] A changed project reads again what is shown (beta kept the old answer: fixed).
- [x] Runner: .sql keyword links, callers/callees, cut per step 30; file lines and .sql links cached
  per index as the old.

## Editor — editor.tsx, src/editor.ts
- [x] CodeMirror 6 with the old extensions, Dark+ highlight style and theme; one view, state per tab.
- [x] Typing saved after 300 ms (code_edit, base version), another page's typing merged, Claude's new
  code replaces (undo keeps typing), unsent dot on tabs, read only / "open this chat to edit".
- [x] Send (button, Enter in the note, Ctrl+Enter in code) -> code_send; "Send n files".
- [x] Languages: Java and C/C++ were missing (added @codemirror/lang-java, lang-cpp).
- [x] Runner guard (refusal with the user's code, seen), user edit, send message format.
- [x] Send chips in the chat (codesend.cljs) with their diff.

## Flow — flow.tsx
- [x] Stations (steps), lanes with sliding cards, gone/dim, captions, back/next, ←/→, scenarios,
  lane heights from the tallest card (counted with trailing empty lines as the old).
- [x] Card bodies coloured by Shiki with the new piece marked inside the colours (beta marked whole lines).

## Templates and the Forms gallery — gallery.tsx, picture.tsx (SaveTemplate), src/forms.ts
- [x] Specs and samples word for word from src/forms.ts (beta had its own files/git samples, "~/..."
  for "~/…", base when-lines without their last sentence, no 3D scene sample).
- [x] Order: graph, pipeline … stats, files, git, clojure, calls, editor, 3D scene, flow, freeform;
  badges "3d scene" etc., numbers #n, templates after.
- [x] Built-in forms: delete (confirm card, form text) and restore; kept per profile, Claude's draw
  tool and prompt leave them out and every chat gets the new tool (missing in beta: added,
  GET/PATCH /api/forms hidden, notHere).
- [x] Opt-in forms a runner doesn't have are not shown (gallery, form row).
- [x] Templates: by name (beta: newest first), badge "<name> · template" (green), description line,
  delete with confirm.
- [x] "save as template": starts with the block's name, Enter saves, Escape cancels, note 3.5 s,
  "Couldn't save the template." (beta started empty, 2.5 s).
- [x] Gallery opened by the shell (rail button, settings) through codeforms/gallery [server close].

## Form row — formrow.tsx
- [x] Rail mode (codeforms/form-rail for the shell's right rail): circles in the old order, tools at
  the foot in blue, column picker, the sample peek level with the circle and kept inside the window.
- [x] Phone: the row over the picture (picture-tools).
- [x] Texts: questions' when-line, state words, aria-pressed on tools, hidden forms left out.
- [~] Phone row sits in the picture head (beta's picture structure) rather than sticky inside the
  picture's scroll; same look.

## Chalk
- [x] .f-ln.hot bar uses chalk line3, git branch dots dot9-soft (beta used line2 / dot18).
- [~] SVG grain on git lanes and clojure bubbles: CSS mask image instead of the old SVG <mask> by id
  (no global chalk-defs SVG in beta); looks the same in the screenshots.
