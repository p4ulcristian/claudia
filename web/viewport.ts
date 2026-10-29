// The page never moves; only its scrollers do.
//
// The body is the page's one layer (style.css), in normal flow, not fixed: iOS 26 Safari paints its
// bottom bar solid in the background colour of any fixed element along the bottom edge, and shows
// the page through the glass only when there is none. Without a keyboard the layer is the layout
// viewport's size (html, 100%), so with viewport-fit=cover it runs under the bar. While a phone's
// keyboard is up the layer is the visible area instead, the visual viewport: iOS keeps the layout
// viewport at full height and shrinks (and often pans or scrolls) only the visual one, so the layer
// follows its size and its offset in the document (pageLeft/pageTop, which include any scroll iOS
// did to bring the field into view), and the composer sits right on top of the keyboard.
//
// iOS also lets a finger pan the page itself (the visual viewport while the keyboard is up, the
// rubber band) whatever the page's overflow says. So a touch may only move something that can
// scroll in that direction: the nearest scroller under the finger with room left that way;
// otherwise the move is cancelled. Pinch zoom of the page is cancelled too (iOS ignores
// user-scalable=no).

const vv = window.visualViewport;
const css = document.documentElement.style;

const VARS = ["--vv-w", "--vv-h", "--vv-left", "--vv-top"];

function fit() {
  const root = document.documentElement;
  // An on-screen keyboard covers the bottom of the layout viewport (html's height; on iOS
  // innerHeight follows the visual viewport, so it can't tell).
  const keyboard = !!vv && root.clientHeight - vv.height > 120;
  root.toggleAttribute("data-keyboard", keyboard);
  // No keyboard, or zoomed in (a desktop pinch, iOS accessibility zoom): the layer is the layout
  // viewport, at the document's origin, so zooming still shows the page larger.
  if (!vv || !keyboard || Math.abs(vv.scale - 1) > 0.01) {
    for (const p of VARS) css.removeProperty(p);
    if (scrollX || scrollY) scrollTo(0, 0);
    return;
  }
  css.setProperty("--vv-w", `${vv.width}px`);
  css.setProperty("--vv-h", `${vv.height}px`);
  css.setProperty("--vv-left", `${vv.pageLeft}px`);
  css.setProperty("--vv-top", `${vv.pageTop}px`);
}

if (vv) {
  vv.addEventListener("resize", fit);
  vv.addEventListener("scroll", fit);
  addEventListener("scroll", fit);
  fit();
}

// The layer's box in client coordinates: fixed popups (menus, bubbles) are placed inside it, so
// they subtract its left/top and stay within its width/height. At (0, 0) and the window's size
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

// Whether the touch in progress moves nothing natively (decided on its first move). The phone's
// swipes (web/swipe.ts) take a drag only then: otherwise a scroller already follows the finger.
// Their listener runs after this file's, so the first move is decided by the time they ask.
export const touchBlocked = () => blocked;

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
