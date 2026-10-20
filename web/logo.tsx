// Claudia's mark (logo option A, 2026-10-20, plan/design/logo.html): a speech bubble holding two
// joined nodes, talking and drawing in one shape. Chalk in currentColor, the nodes in the accent.
import React from "react";

export function Logo({ size = 20 }: { size?: number }) {
  return (
    <svg className="logo" width={size} height={size} viewBox="0 0 32 32" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M7 5h18a4 4 0 0 1 4 4v11a4 4 0 0 1-4 4H15l-6.5 5v-5H7a4 4 0 0 1-4-4V9a4 4 0 0 1 4-4z" stroke="currentColor" strokeWidth="2.4" />
      <path d="M13.2 14.5h5.6" stroke="var(--accent)" strokeWidth="2.2" />
      <circle cx="10.5" cy="14.5" r="2.7" fill="var(--accent)" />
      <circle cx="21.5" cy="14.5" r="2.5" stroke="var(--accent)" strokeWidth="2.1" />
    </svg>
  );
}
