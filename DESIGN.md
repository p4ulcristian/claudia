# Claudia's look

Chosen 2026-10-20 from three directions (`plan/design/directions.html`): **B · Chalkboard**.
Applied 2026-10-20. Fonts: Atkinson Hyperlegible (text), Kalam (handwriting), JetBrains Mono (code, paths, diffs). Dark only: the light theme and the theme picker were removed 2026-10-20.

Why it fits: Claudia is someone talking while drawing on a board. The look comes from that, not from the usual app defaults.

- **Board**: the picture pane is a slate board (mid-dark neutral grey since 2026-10-22, faint uneven wash), not near-black.
- **Chalk**: diagram lines and labels look hand-drawn (slight wobble, a handwriting face for labels); the chat's colour is the coloured chalk for what changed.
- **Talk**: chat text in a warm, very readable sans; the user's lines in the handwriting face, in the chat's chalk colour.
- **Chalk lines**: parts are split by hand-drawn chalk lines (sidebar edge, the divider, above the composer), not borders or wood (the wooden ledge was removed 2026-10-22: its fixed brown ignored the chat colour).
- **Chat colours**: every chat has one of 16 (random at start, least-used first); it tints the whole window and is the chalk colour.
- Stays plain: code, diffs, tables (readability first).

## Chalk technique (baked, since 2026-10-28)

The rough chalk edge is drawn once, when the page loads, never per frame. Live SVG filters (feTurbulence + feDisplacementMap on HTML elements) made Safari redraw in software every frame (13 fps scrolling the chat, 1.6 s to switch chats), so none is left.

- `web/chalk.ts` renders the old filters' noise (the SVG spec's turbulence, same frequencies, seeds and displacement) into small images at the screen's pixel ratio and hands them to the CSS as `--chalk-*` custom properties.
- Outlines, circles, lines and dots: a ::before 2px bigger than the box, painted in the chalk colour (`--ink`) through a nine-slice mask of the baked shape (`-webkit-mask-box-image: var(--ring)`); a box keeps its fill on itself, under the outline. A new radius or width is one line in `SHAPES`. Browsers without nine-slice masks (Firefox) get plain outlines.
- SVG drawings (the picture's graph, the git and clojure maps, charts, icons): a baked grain tile as their mask; graph shapes and lines get a wider copy under them through the fray grain, which makes their rough edge.
- Endless dots (state badges, moons, run dots) move on only on the open chat; elsewhere they run a few times and rest. The bar's dots rest while the bar is hidden.
