// A chalk speech bubble with a chat's full title, shown at once on hover (the browser's own
// tooltip waits about a second). One bubble for the whole page, fixed to the page's layer so the
// sidebar's scrolling and clipping don't cut it.
import React, { useEffect, useState } from "react";
import { layer } from "./viewport";
import { GRAIN_SIZE, grainUrl } from "./chalk";

type Tip = { text: string; color?: string; x: number; y: number };

let show: (t: Tip | null) => void = () => {};

// Props for the element the bubble points at. With `onlyClipped`, it shows only when the
// element's `.t` text is cut off (a row whose title fits needs no bubble).
export function tipProps(text: string, color?: string, onlyClipped = false) {
  return {
    onMouseEnter: (e: React.MouseEvent<HTMLElement>) => {
      const el = e.currentTarget;
      const t = el.querySelector<HTMLElement>(".t");
      if (onlyClipped && t && t.scrollWidth <= t.clientWidth) return;
      const r = el.getBoundingClientRect();
      const l = layer();
      show({ text, color, x: r.right + 12 - l.left, y: r.top + r.height / 2 - l.top });
    },
    onMouseLeave: () => show(null),
  };
}

export function TipBubble() {
  const [tip, setTip] = useState<Tip | null>(null);
  useEffect(() => {
    show = setTip;
    const hide = () => setTip(null);
    // A scrolled list or a click moves the row away from the bubble.
    addEventListener("scroll", hide, true);
    addEventListener("mousedown", hide, true);
    addEventListener("blur", hide);
    return () => {
      show = () => {};
      removeEventListener("scroll", hide, true);
      removeEventListener("mousedown", hide, true);
      removeEventListener("blur", hide);
    };
  }, []);
  return (
    <>
      {/* The chalk grain for SVG drawn outside the picture (the git and clojure maps, charts): SVG
          shapes take a mask, by id. The rest of the page's chalk is CSS (web/chalk.ts). */}
      <svg className="chalk-defs" aria-hidden="true">
        {(["edge", "soft"] as const).map((kind) => {
          const id = kind === "edge" ? "chalk-grain" : "chalk-grain-soft";
          return (
            <React.Fragment key={kind}>
              <pattern id={`${id}-tile`} patternUnits="userSpaceOnUse" width={GRAIN_SIZE} height={GRAIN_SIZE}>
                <image href={grainUrl(kind)} width={GRAIN_SIZE} height={GRAIN_SIZE} />
              </pattern>
              <mask id={id} maskUnits="userSpaceOnUse" x="-100000" y="-100000" width="200000" height="200000" style={{ maskType: "alpha" }}>
                <rect x="-100000" y="-100000" width="200000" height="200000" fill={`url(#${id}-tile)`} />
              </mask>
            </React.Fragment>
          );
        })}
      </svg>
      {tip && (
        <div className="tip-bubble" role="tooltip" style={{ left: tip.x, top: tip.y, "--chat": tip.color } as React.CSSProperties}>
          <svg className="tip-tail" viewBox="0 0 12 16" aria-hidden="true">
            <path d="M12 1 L1 8 L12 15" />
          </svg>
          {tip.text}
        </div>
      )}
    </>
  );
}
