// What sets one Claudia instance apart from another; the code is the same everywhere.
// Each instance has a profiles list (src/profiles.ts): the gate's signed-in email picks a profile,
// each profile's chats run in its own runner (on the box: as that profile's own Linux user),
// and every profile of an instance runs on one Claude token that anyone signed in can replace.

// The Claude token in use (written by the page, read by the runners on each turn) and a note beside
// it saying whose it is, who switched to it and when (no secret in it). The tokens saved in Settings
// sit in TOKENS_DIR, readable by the page only; switching copies one of them over TOKEN_FILE.
const DATA = process.env.XDG_DATA_HOME ?? `${process.env.HOME}/.local/share`;
export const TOKEN_FILE = process.env.CLAUDIA_TOKEN_FILE ?? `${DATA}/claudia/claude-token`;
export const TOKEN_NOTE = `${TOKEN_FILE}.json`;
export const TOKENS_DIR = `${TOKEN_FILE}.d`;

// This instance's name (CLAUDIA_NAME, set in its unit), shown in the chip under the logo so two
// Claudias don't look alike. Unset = only the token and version there.
export const NAME = (process.env.CLAUDIA_NAME ?? "").trim().slice(0, 32) || null;
