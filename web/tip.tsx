// A chalk speech bubble with a chat's full title, shown at once on hover (the browser's own
// tooltip waits about a second). One bubble for the whole page, fixed to the window so the
// sidebar's scrolling and clipping don't cut it.
import React, { useEffect, useState } from "react";

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
      show({ text, color, x: r.right + 12, y: r.top + r.height / 2 });
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
      {/* The chalk edge for page parts outside the picture (this bubble, the rail's circles, the chalk lines). */}
      <svg className="chalk-defs" aria-hidden="true">
        <filter id="tip-chalk" x="-20%" y="-20%" width="140%" height="140%">
          <feTurbulence type="fractalNoise" baseFrequency="0.85" numOctaves="2" seed="3" result="noise" />
          <feDisplacementMap in="SourceGraphic" in2="noise" scale="2.4" xChannelSelector="R" yChannelSelector="G" />
        </filter>
        {/* Softer, for small strokes (the menu icon) that the full wobble would break up. */}
        <filter id="chalk-soft" x="-20%" y="-20%" width="140%" height="140%">
          <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed="3" result="noise" />
          <feDisplacementMap in="SourceGraphic" in2="noise" scale="1.3" xChannelSelector="R" yChannelSelector="G" />
        </filter>
        {/* For lines a few px thin: the filter region has to reach well past them for the wobble. */}
        <filter id="chalk-line" x="-500%" y="-500%" width="1100%" height="1100%">
          <feTurbulence type="fractalNoise" baseFrequency="0.7" numOctaves="2" seed="5" result="noise" />
          <feDisplacementMap in="SourceGraphic" in2="noise" scale="3" xChannelSelector="R" yChannelSelector="G" />
        </filter>
      </svg>
      {tip && (
        <div className="tip-bubble" role="tooltip" style={{ left: tip.x, top: tip.y, "--chat": tip.color } as React.CSSProperties}>
          <svg className="tip-chalk" aria-hidden="true">
            <rect x="2" y="2" rx="10" style={{ width: "calc(100% - 4px)", height: "calc(100% - 4px)" }} />
          </svg>
          <svg className="tip-tail" viewBox="0 0 12 16" aria-hidden="true">
            <path d="M12 1 L1 8 L12 15" />
          </svg>
          {tip.text}
        </div>
      )}
    </>
  );
}
