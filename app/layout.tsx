import type { Metadata } from "next";
import { config } from "@fortawesome/fontawesome-svg-core";
import "@fortawesome/fontawesome-svg-core/styles.css";
import "./globals.css";

// We import the Font Awesome CSS manually above; stop the library from injecting
// it again at runtime (avoids an icon flash on first paint with SSR).
config.autoAddCss = false;

export const metadata: Metadata = {
  title: "claudia",
  description: "Browse folders, resume Claude sessions, and chat live.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
