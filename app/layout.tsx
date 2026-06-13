import type { Metadata } from "next";
import "./globals.css";

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
