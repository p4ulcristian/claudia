# Parity: the picture pane and the base forms

Old = branch main (aeb57a1), files under `web/` and `src/`. Beta = this branch.
[x] identical, [~] different (how), [ ] missing. State as found before the work; see "After" at the end.

## The right half (web/app.tsx 1196-1248, web/picture.tsx, web/style.css)
- [~] No header on the board: the title names a picture only in the scrubber's tip (picture.tsx:565). Beta: a `picture-head` with the title, a Forms button and the form row.
- [~] `.right-scroll` > `.stage` (padding 18 18 110, `.stage > *` grows; style.css:221). Beta: `.picture-scroll` padding 8 18 40.
- [~] An earlier picture: `.stage.past` inset 2px violet 40% ring (style.css:223). Beta: a 3px violet left edge.
- [~] Empty state: three dashed circles (22/30/22 px, 1.5px dashed --line), sans muted text (style.css:225-229). Beta: chalk dashed rings, Kalam 17px.
- [x] Canvas grid: two columns, gap 18 16, half blocks side by side, full width under 560px (style.css:919-922).
- [~] A block appears without animation. Beta adds `rise`.
- [x] Columns 1-3 per chat and their container rules (style.css:1296-1305; in codeforms.css).
- [~] Glow per change: changed ids per draw, a new glow per draw (picture.tsx:509-513). Beta: only when the block's value changed after first sight; nodes and arrows never.
- [~] Badge: name first, then " ·", then the kind (picture.tsx:456; style.css:956-964): kind = graph | the form's label in lower case ("3d scene") | freeform | template (green); opacity .55, .9 on hover; 12px; the form's when-line as tooltip. Beta: kind first, "· name", 12.5px, opacity .6, form name not label, no template kind, no tooltip.
- [~] Save as template (picture.tsx:468-504): first in the head, shown on hover; the name field starts with the block's name; Enter saves, Escape cancels; the server's answer for 3.5 s. Beta: empty field, a form submit, "saved" for 2.5 s.
- [ ] Kept files block: a files block the user clicked in stays when Claude's draw removes it, with "kept ✕" / "kept · close ✕" (picture.tsx:506-580).
- [ ] Pictures from before blocks (nodes + cards) drawn as a graph and cards (picture.tsx:582-592, style.css:268-279, 622-626).

## Graph blocks (picture.tsx:14-377, style.css:231-266, 505-524)
- [x] Node sizes, label/note wrap, ELK options, groups, stacked hidden arrows, rounded edge paths.
- [~] Variant choice: the best raw fit wins, kept unless another is 1.25x better (picture.tsx:301-305). Beta caps fits at 1.2 first.
- [~] Available size from the block, else the scroller minus 36; height 80% of the scroller (picture.tsx:231-235). Beta reads `.picture-scroll`.
- [ ] Pan by dragging, Ctrl+wheel / pinch zoom around the pointer (0.3-4x), double-click resets; grab cursor; `.zoom-layer` (picture.tsx:253-275, 316-328).
- [ ] A changed node pulses a ring three times (`.ring`, keyed per draw); a changed arrow is `hot` (thicker, full colour) (picture.tsx:195-204, 357; style.css:253, 262, 521).
- [~] `.node.s-active .body` stroke-width 2.6 (style.css:249 wins over 507). Beta 2.8.
- [x] Shapes, chalk fray, arrowheads per colour, done/blocked badges, dashed arrows, edge labels, group frames, fresh pop/fade, move animation.
- [~] Layout reruns only when the graph or the width changes. Beta reruns on every render (a new map as the effect's dependency).

## Freeform pages (picture.tsx:379-421)
- [x] Theme variables, fonts, CSP, parts by postMessage.
- [~] Height starts at 240, at least 60, transition 0.15s (style.css:744). Beta 120 / 40, no transition.

## Forms (web/forms.tsx:1-130, style.css:973-1033)
- [x] pipeline (stage boxes, marks, lit arrows, stacked under 120px a stage).
- [~] pipeline arrowhead masked by the chalk grain (style.css:991).
- [~] timeline: the status tag is its own line under the label (forms.tsx:61-63). Beta puts it inside the label.
- [x] compare.
- [~] stats: tiles stretch to the row's height (no align-content). Beta: align-content start.
- [~] chart: Vega-Lite in the page itself (vega-embed from jsdelivr), bars/lines through the chalk grain (forms.tsx:107-152, style.css:1031-1033). Beta: a sandboxed iframe, no grain.
- [~] `data-item` on items.

## Forms gallery (web/gallery.tsx, style.css:1206-1260)
- [~] Order: graph, pipeline, timeline, compare, chart, stats, files, git, clojure, calls, editor, scene3d, flow, freeform. Beta: base forms + scene3d first.
- [~] Head: #n, delete (pushed right), badge last (right). Beta: badge left.
- [ ] Every built-in form can be deleted (prefs.hiddenForms; Claude stops getting it) and restored; confirm card wording per kind.
- [ ] scene3d sample; [~] when-lines word for word (pipeline lacks "Update one stage as it moves.").
- [~] Templates' badge: "template" in green with the name.
- [~] Opened from the rail's Forms button and Settings > Forms, not from the picture.

## Form row / right rail (web/formrow.tsx, app.tsx:1250-1263, style.css:1262-1295)
- [ ] Desktop: a 64px right rail beside the picture (outside it, stays when folded): the forms in a column, the user's tools (blue) under a dashed line, the column picker at the foot; the sample opens to the left, level with the circle, kept inside the window.
- [~] Phone: the row sticky at the top of the picture drawer. Beta: in the picture header on both.

## Bar under the picture (web/rightbar.tsx, style.css:679-719, 790-813)
- [x] Picture | Timeline, scrubber with dots/lines/none, ‹ › and n/m, Live / Live · unchanged / Back to live, fold ›.
- [~] Chalk outlines on the bar (r12), the switch (r9), its on button (r7), the fold (r7), the chips (r12), fill bg2 88%; gap 12; fold pushed right. Beta: own outline colour, box shadow, plain borders.
- [~] Tip placed fixed over its mark, kept inside the right half. Beta: absolute.
- [x] Beta adds the snapshot camera (kept).

## Timeline view (web/timeline.tsx, style.css:628-677, 720-725)
- [~] Head: bold "Timeline", agents count, filter, "show everything" link; padding 10 18; a line under it. Beta: no "Timeline", no line, other padding.
- [~] Rows: hover --panel, open --panel2; "its steps" and "earlier steps" as links. Beta: other colours, own classes.
- [ ] Explain this step on an opened tool step (same explanation as in the chat).
- [x] Rails, separators, durations, diffs, details.

## Fold (style.css:823-838)
- [~] Folded edge: a chalk line (line2 mask) in --chalk, accent and 3px on hover or when a new picture came. Beta: a plain line.
- [ ] The column slides shut / open (useFoldMotion, app.tsx:1307-1363).

## Phone (style.css:848-885)
- [~] The picture is a drawer over the chat: no header, the form row on top, the bar under the picture (static), its fold closes it. Beta: a header with "‹ Chat" and the title.

## Render page (web/render.tsx)
- [~] The picture alone in a `.stage` (padding-bottom 18), no title. Beta: a title over it.

## scene3d (web/scene3d-view.tsx, style.css:1590-1652)
- to compare with the old view: frame, controls, labels, legend, story bar.

## Draw tool description (src/prompt.ts, src/forms.ts)
- [~] Forms in the old order; scene3d's data word for word (allow, scan, people not solid, the overlap check); html "e.g. Chart.js or D3", "are set as CSS variables"; kind "html or template"; blocks "HTML blocks, and the width/position of any block"; template and height fields.

## After (2026-11-03)
Compared by screenshots (headless Chrome, 1440x900 and 390x844 @2x) against the old Claudia on
:4510 and, for the board itself, the old render page (web/render.html served by bun) against beta's
render mode with the same scene: graph (groups, every shape, state and colour, dashed and labelled
arrows, notes), pipeline, timeline, stats, compare, chart, freeform. The two renders are pixel
identical except the chart's bars (the chalk grain lands at another offset).

- [x] No header: the board starts at the top; the title shows in the scrubber's tip.
- [x] `.right-scroll` > `.stage` (18 18 110), `.stage.past` violet ring.
- [x] Empty state (dashed circles, sans text).
- [x] Canvas grid, half blocks, columns; blocks appear without `rise`.
- [x] Glow per change, a ring on a changed node, `hot` on a changed arrow. Beta has no `changed`
      list on the wire: the page diffs each new scene against the one before (same chat), so a
      draw that sends a node unchanged doesn't ring it (the old one did).
- [x] Badge: name · kind, form label lower case, template green, when-line tooltip, .55 / .9.
- [x] Save as template (the code forms branch's version, same behaviour).
- [x] Kept files block ("kept ✕", "kept · close ✕", dashed outline when gone).
- [x] Pictures from before blocks: graph + cards.
- [x] Graph: old variant choice, available size, pan / Ctrl+wheel / pinch zoom / double-click reset,
      `.zoom-layer`, active stroke 2.6, layout only on change.
- [x] Freeform frames: 240 start, 60 minimum, height transition.
- [x] Forms: pipeline arrowhead grain, timeline tag line, stats stretch, chart in the page with the
      grain mask (#chalk-grain / #chalk-grain-soft now baked into the page by chalk/bake-all!), data-item.
- [x] Gallery, form row, right rail: the code forms branch built the same (merged here); the right
      rail sits in the design branch's grid column, its contents codeforms/form-rail.
- [x] Bar: the old chalk outlines, fold pushed right, fixed tip; the snapshot camera stays (beta).
- [x] Timeline: "Timeline" head with its line, links, hover/open colours, Explain this step
      (through the chat's `:ops/explain`; a sub-agent's step may not be found by the runner).
- [x] Folded edge: chalk line, accent on hover and when a new picture came.
- [x] Phone: picture screen without a header, the form row on top, the bar under it, › closes it.
- [x] Render page: the picture alone in a `.stage`, no title.
- [x] scene3d: the gallery samples render identically (frame, labels, arrows, Diagram / reset view).
- [x] Draw tool: forms in the old order, scene3d's data word for word, html / kind / blocks
      descriptions, `height` field.
- [ ] The fold's slide (useFoldMotion): not done. The split's columns are the design branch's
      inline grid; the fold switches at once.
