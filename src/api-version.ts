// The API the page and the hub speak to a Claudia server (/api/*, /ws). Raised when a change would
// break a page built for the version before; the hub's page marks a server whose /api/me answers
// another one as needing an update. (Its own file: the page imports it, and src/version.ts reads files.)
export const API = 1;
