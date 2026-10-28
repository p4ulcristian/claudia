// Where the page's /api/… and /ws go. Served by a server itself (single-server mode) every path is
// its own ("", whose chats from the claudia_view cookie). Served by the hub, each server is under
// /s/<server>/…, and whose chats to show goes along as the X-Claudia-View header (the socket and
// images, which can't carry headers: ?view=). Every feature asks the open chat's server, through
// the Api in ApiCtx.
import { createContext, useContext } from "react";

export type Api = {
  // The server's name in the hub; "" when the page is served by a server itself.
  server: string;
  // Another profile whose chats are read (only in the hub; a server itself reads the cookie).
  view: string | null;
  fetch: (path: string, init?: RequestInit) => Promise<Response>;
  // A URL for an <img> or a link (no headers there): the view rides in the query.
  src: (path: string) => string;
  // The chat socket's address.
  ws: () => string;
};

const withView = (path: string, view: string | null) => (view ? `${path}${path.includes("?") ? "&" : "?"}view=${encodeURIComponent(view)}` : path);

const made = new Map<string, Api>();

// One object per server and view, so it can sit in effect dependencies.
export function apiFor(server: string, view: string | null = null): Api {
  const key = `${server}\n${view ?? ""}`;
  const have = made.get(key);
  if (have) return have;
  const base = server ? `/s/${server}` : "";
  const api: Api = {
    server,
    view,
    fetch: (path, init) => {
      if (!view) return fetch(base + path, init);
      const headers = new Headers(init?.headers);
      headers.set("X-Claudia-View", view);
      return fetch(base + path, { ...init, headers });
    },
    src: (path) => withView(base + path, view),
    ws: () => `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}${withView(`${base}/ws`, view)}`,
  };
  made.set(key, api);
  return api;
}

export const ApiCtx = createContext<Api>(apiFor(""));
export const useApi = () => useContext(ApiCtx);

// The API this page speaks; a server answering another in /api/me is marked "needs an update".
export { API as PAGE_API } from "../src/api-version";
