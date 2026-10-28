// The page never moves; only its scrollers do.
//
// The body is a fixed layer (style.css) the size of the visible area: the visual viewport. On a
// phone that is the part above the keyboard, so the composer sits right on top of the keyboard and
// the messages scroll inside their own column. iOS keeps the layout viewport at full height when the
// keyboard opens and shrinks (and often pans) only the visual one, so the layer follows its size and
// offset here, on every resize and scroll of the visual viewport.
//
// iOS also lets a finger pan the page itself (the visual viewport while the keyboard is up, the
// rubber band) whatever the page's overflow says. So a touch may only move something that can
// scroll in that direction: the nearest scroller under the finger with room left that way;
// otherwise the move is cancelled. Pinch zoom of the page is cancelled too (iOS ignores
// user-scalable=no).

const vv = window.visualViewport;
const css = document.documentElement.style;

function fit() {
  // Zoomed in (a desktop pinch, iOS accessibility zoom): the layer stays the layout viewport's size,
  // so zooming still shows the page larger.
  if (!vv || Math.abs(vv.scale - 1) > 0.01) {
    for (const p of ["--vv-w", "--vv-h", "--vv-left", "--vv-top"]) css.removeProperty(p);
    document.documentElement.removeAttribute("data-keyboard");
    return;
  }
  css.setProperty("--vv-w", `${vv.width}px`);
  css.setProperty("--vv-h", `${vv.height}px`);
  css.setProperty("--vv-left", `${vv.offsetLeft}px`);
  css.setProperty("--vv-top", `${vv.offsetTop}px`);
  // An on-screen keyboard covers the bottom of the layout viewport (html's height; on iOS
  // innerHeight follows the visual viewport, so it can't tell).
  document.documentElement.toggleAttribute("data-keyboard", document.documentElement.clientHeight - vv.height > 120);
}

if (vv) {
  vv.addEventListener("resize", fit);
  vv.addEventListener("scroll", fit);
  fit();
}

// The fixed layer's box in client coordinates: fixed popups (menus, bubbles) are placed inside it,
// so they subtract its left/top and stay within its width/height. At (0, 0) and the window's size
// except while a phone's keyboard is open.
export function layer(): DOMRect {
  return document.body.getBoundingClientRect();
}

const scrolls = (v: string) => v === "auto" || v === "scroll" || v === "overlay";

// Whether some element from `el` up to the body can scroll by (dx, dy) (content moving that way).
function canScroll(el: Element | null, dx: number, dy: number): boolean {
  const down = Math.abs(dy) >= Math.abs(dx);
  for (; el && el !== document.body; el = el.parentElement) {
    const s = getComputedStyle(el);
    if (down) {
      if (!scrolls(s.overflowY) || el.scrollHeight <= el.clientHeight) continue;
      if (dy > 0 ? el.scrollTop + el.clientHeight < el.scrollHeight - 1 : el.scrollTop > 0) return true;
    } else {
      if (!scrolls(s.overflowX) || el.scrollWidth <= el.clientWidth) continue;
      if (dx > 0 ? el.scrollLeft + el.clientWidth < el.scrollWidth - 1 : el.scrollLeft > 0) return true;
    }
  }
  return false;
}

// Decided on a touch's first move (later moves can't be cancelled once a scroll has started).
let start: { x: number; y: number } | null = null;
let blocked = false;

addEventListener("touchstart", (e: TouchEvent) => {
  const t = e.touches[0];
  start = e.touches.length === 1 && t ? { x: t.clientX, y: t.clientY } : null;
  blocked = false;
}, { passive: true });

addEventListener("touchmove", (e: TouchEvent) => {
  if (e.touches.length > 1) return e.preventDefault();
  const t = e.touches[0];
  if (!e.cancelable || !t) return;
  if (start) {
    const dx = start.x - t.clientX, dy = start.y - t.clientY;
    if (!dx && !dy) return;
    const target = e.target instanceof Element ? e.target : null;
    // A slider moves its own thumb.
    blocked = !target?.closest("input[type=range]") && !canScroll(target, dx, dy);
    start = null;
  }
  if (blocked) e.preventDefault();
}, { passive: false });

addEventListener("gesturestart", (e) => e.preventDefault());
