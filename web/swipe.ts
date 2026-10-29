// The phone's sideways swipes (decision 26), following the finger like iOS's back swipe: on the
// chat, right slides it off and shows the list under it, left pulls the picture in; on the list,
// left slides the open chat back over it (back's mirror, like closing a sidebar); on the picture,
// right pushes it out; on the Settings and agent screens, right goes back. Released past 40% of the
// way, or flicked, the move completes; else it springs back. The page owns the gesture (the iPhone
// app turns off the system's edge swipe), so it starts anywhere, except in fields, on things that
// move sideways themselves (code and tables that scroll, the picture's pannable graphs, sliders),
// with two fingers, and on a mostly vertical move (the axis is decided on the first 10 px).
//
// While the finger moves, only inline transforms change, once per frame; at the end they are
// animated to where the app's own classes put that screen, the app's state changes (the history
// step the screens already have), and once its classes show the end, the inline styles go: nothing
// jumps. With reduced motion nothing follows the finger: a completed swipe just switches.
import { useEffect, useRef } from "react";
import { touchBlocked } from "./viewport";

export type Gesture = {
  // The finger's way: +1 right, -1 left.
  dir: 1 | -1;
  // How far the finger goes for the whole move (px).
  width: number;
  // Draw the move at p (0 = the screen as it is, 1 = done) with inline styles.
  paint: (p: number) => void;
  // The app's own step there (may land later, e.g. after a history step).
  commit: () => void;
  // The app shows the end state now (its classes match the last paint).
  settled: () => boolean;
  // Drop the inline styles.
  clear: () => void;
};

const AXIS_PX = 10;
const DONE_AT = 0.4;
const FLICK = 0.45; // px per ms
const reduced = () => matchMedia("(prefers-reduced-motion: reduce)").matches;

// Something under the finger that moves sideways itself: a scroller with room, or an element that
// takes horizontal moves (touch-action without pan-x: the picture's graphs, the picture bar's track).
function movesSideways(el: Element | null): boolean {
  for (; el && el !== document.body; el = el.parentElement) {
    const s = getComputedStyle(el);
    const x = s.overflowX;
    if ((x === "auto" || x === "scroll" || x === "overlay") && el.scrollWidth > el.clientWidth + 1) return true;
    const ta = s.touchAction;
    if (ta && ta !== "auto" && ta !== "manipulation" && !ta.includes("pan-x")) return true;
  }
  return false;
}

// Turns swipes on while `enabled`; `gestures` says, at a touch's start, where it may start
// (surface) and which moves it may make there.
export function useSwipes(enabled: boolean, gestures: () => { surface: Element | null; moves: Gesture[] }) {
  const get = useRef(gestures);
  get.current = gestures;
  useEffect(() => {
    if (!enabled) return;
    type Track = { x0: number; y0: number; origin: number; moves: Gesture[]; g: Gesture | null; p: number; samples: { x: number; t: number }[] };
    let track: Track | null = null;
    let busy = false; // an end animation or a commit is under way
    let raf = 0;

    const paintSoon = (g: Gesture, p: number) => {
      if (reduced()) return;
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => g.paint(p));
    };

    // From p to the end (1) or back (0), then commit and wait for the app, or just clear.
    const finish = (g: Gesture, from: number, done: boolean, speed: number) => {
      cancelAnimationFrame(raf);
      if (reduced()) {
        if (done) g.commit();
        g.clear();
        return;
      }
      busy = true;
      const to = done ? 1 : 0;
      const dist = Math.abs(to - from) * g.width;
      const ms = Math.min(320, Math.max(120, dist / Math.max(speed, 0.9)));
      const t0 = performance.now();
      const step = (now: number) => {
        const k = Math.min(1, (now - t0) / ms);
        const e = 1 - Math.pow(1 - k, 3);
        g.paint(from + (to - from) * e);
        if (k < 1) return void (raf = requestAnimationFrame(step));
        if (!done) {
          g.clear();
          busy = false;
          return;
        }
        g.commit();
        // The app's classes take over where the paint ended, then the inline styles go (at most 1.5 s on).
        let frames = 0;
        const wait = () => {
          if (g.settled() || ++frames > 90) {
            g.clear();
            busy = false;
            return;
          }
          raf = requestAnimationFrame(wait);
        };
        raf = requestAnimationFrame(wait);
      };
      raf = requestAnimationFrame(step);
    };

    const start = (e: TouchEvent) => {
      track = null;
      if (busy || e.touches.length !== 1) return;
      const t = e.touches[0]!;
      const target = e.target instanceof Element ? e.target : null;
      const { surface, moves } = get.current();
      if (!target || !surface?.contains(target) || !moves.length) return;
      if (target.closest("input, textarea, select, [contenteditable], [data-no-swipe]") || movesSideways(target)) return;
      track = { x0: t.clientX, y0: t.clientY, origin: t.clientX, moves, g: null, p: 0, samples: [] };
    };

    const move = (e: TouchEvent) => {
      if (!track) return;
      const t = e.touches[0];
      if (e.touches.length > 1 || !t) return void cancel();
      if (!track.g) {
        const dx = t.clientX - track.x0, dy = t.clientY - track.y0;
        if (Math.hypot(dx, dy) < AXIS_PX) return;
        // Mostly vertical, or a scroller already follows the finger (viewport.ts let it): not ours.
        const g = Math.abs(dx) >= Math.abs(dy) * 1.2 && e.cancelable && touchBlocked() ? track.moves.find((m) => Math.sign(dx) === m.dir) : undefined;
        if (!g) return void (track = null);
        track.g = g;
        track.origin = t.clientX;
      }
      if (e.cancelable) e.preventDefault();
      const g = track.g;
      track.p = Math.min(1, Math.max(0, ((t.clientX - track.origin) * g.dir) / g.width));
      const now = e.timeStamp || performance.now();
      track.samples.push({ x: t.clientX, t: now });
      while (track.samples.length > 2 && now - track.samples[0]!.t > 100) track.samples.shift();
      paintSoon(g, track.p);
    };

    const end = () => {
      const tr = track;
      track = null;
      if (!tr?.g) return;
      const s = tr.samples, a = s[0], b = s.at(-1);
      // Speed along the gesture's way over the last 100 ms (px/ms); a flick decides by itself.
      const v = a && b && b.t > a.t ? ((b.x - a.x) * tr.g.dir) / (b.t - a.t) : 0;
      const done = v > FLICK ? true : v < -FLICK ? false : tr.p > DONE_AT;
      finish(tr.g, tr.p, done, Math.abs(v));
    };

    const cancel = () => {
      const tr = track;
      track = null;
      if (tr?.g) finish(tr.g, tr.p, false, 0);
    };

    addEventListener("touchstart", start, { passive: true });
    addEventListener("touchmove", move, { passive: false });
    addEventListener("touchend", end);
    addEventListener("touchcancel", cancel);
    return () => {
      removeEventListener("touchstart", start);
      removeEventListener("touchmove", move);
      removeEventListener("touchend", end);
      removeEventListener("touchcancel", cancel);
      cancelAnimationFrame(raf);
    };
  }, [enabled]);
}

// The phone's screens (app.tsx, sidebar.css): the list and Settings in the sidebar slot, under the
// chat (main); the picture drawer and an agent's view inside the chat.
type Screens = {
  phone: boolean;
  screen: "list" | "chat" | "settings";
  picOpen: boolean;
  agentShown: boolean;
  toList: () => void;
  toChat: () => void;
  closeSettings: () => void;
  closeAgent: () => void;
  openPicture: () => void;
  closePicture: () => void;
};

export function usePhoneSwipes(s: Screens) {
  useSwipes(s.phone, () => {
    const $ = (q: string) => document.querySelector<HTMLElement>(q);
    const app = $(".app");
    const has = (c: string) => !!app?.classList.contains(c);
    if (s.screen === "settings") {
      const g = slideBack($(".st-pane"), $(".cs-pane"), s.closeSettings, () => !has("settings-shown"));
      return { surface: $(".st-pane"), moves: g ? [g] : [] };
    }
    if (s.screen === "list") {
      const g = slideOver($(".app > main"), $(".app > .sidebar-slot"), s.toChat, () => has("no-sidebar"));
      return { surface: $(".app > .sidebar-slot"), moves: g ? [g] : [] };
    }
    if (s.screen !== "chat") return { surface: null, moves: [] };
    const pic = $(".right.pic-drawer"), shade = $(".pic-backdrop");
    if (s.picOpen) {
      const g = drawer(pic, shade, false, s.closePicture, () => !pic?.classList.contains("open"));
      return { surface: $(".app > main"), moves: g ? [g] : [] };
    }
    if (s.agentShown) {
      const g = slideBack($(".ag-screen"), null, s.closeAgent, () => !$(".ag-screen"));
      return { surface: $(".ag-screen"), moves: g ? [g] : [] };
    }
    const moves = [
      slideBack($(".app > main"), $(".app > .sidebar-slot"), s.toList, () => !has("no-sidebar")),
      drawer(pic, shade, true, s.openPicture, () => !!pic?.classList.contains("open")),
    ].filter((g): g is Gesture => !!g);
    return { surface: $(".app > main"), moves };
  });
}

// ---- the moves the phone's screens make ----

const px = (x: number) => `translate3d(${x}px, 0, 0)`;

function setStyles(el: HTMLElement | null, s: Partial<Record<"transform" | "visibility" | "opacity" | "pointerEvents", string>>) {
  if (!el) return;
  el.style.transition = "none";
  Object.assign(el.style, s);
}

// Inline styles off without a transition (the class's value is the same as the last paint).
function unset(...els: (HTMLElement | null)[]) {
  for (const el of els) {
    if (!el) continue;
    el.style.transition = "none";
    el.style.transform = el.style.visibility = el.style.opacity = el.style.pointerEvents = "";
    void el.offsetWidth;
    el.style.transition = "";
  }
}

// A screen on top slides off to the right with the finger and shows the one under it, which comes
// from 30% to the left (iOS's back swipe).
export function slideBack(top: HTMLElement | null, under: HTMLElement | null, back: () => void, settled: () => boolean): Gesture | null {
  if (!top) return null;
  const w = top.getBoundingClientRect().width || innerWidth;
  return {
    dir: 1,
    width: w,
    paint: (p) => {
      setStyles(top, { transform: px(p * w) });
      setStyles(under, { transform: px((p - 1) * 0.3 * w), visibility: "visible" });
    },
    commit: back,
    settled,
    clear: () => unset(top, under),
  };
}

// slideBack's mirror: the screen that slid off comes back from the right with a left move, and the
// one under it goes back to 30% to the left.
export function slideOver(top: HTMLElement | null, under: HTMLElement | null, forward: () => void, settled: () => boolean): Gesture | null {
  if (!top) return null;
  const w = under?.getBoundingClientRect().width || innerWidth;
  return {
    dir: -1,
    width: w,
    paint: (p) => {
      setStyles(top, { transform: px((1 - p) * w), visibility: "visible" });
      setStyles(under, { transform: px(-p * 0.3 * w) });
    },
    commit: forward,
    settled,
    clear: () => unset(top, under),
  };
}

// A drawer from the right (off by `off` of its width when shut): pulled in by a left move, or
// pushed out by a right one; its backdrop fades with it.
export function drawer(el: HTMLElement | null, backdrop: HTMLElement | null, opening: boolean, step: () => void, settled: () => boolean, off = 1.05): Gesture | null {
  if (!el) return null;
  const w = el.getBoundingClientRect().width || innerWidth;
  const shut = w * off;
  return {
    dir: opening ? -1 : 1,
    width: shut,
    paint: (p) => {
      const open = opening ? p : 1 - p;
      setStyles(el, { transform: px((1 - open) * shut) });
      setStyles(backdrop, { opacity: String(open), visibility: "visible" });
    },
    commit: step,
    settled,
    clear: () => unset(el, backdrop),
  };
}
