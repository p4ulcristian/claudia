import fs from "node:fs/promises";
import path from "node:path";
import type { FileNode, FileContent } from "./types";

// Directories never worth showing in the tree (heavy, generated, or VCS guts).
const PRUNE = new Set([
  "node_modules",
  ".git",
  ".next",
  "dist",
  "build",
  ".cache",
  ".turbo",
  "coverage",
]);

// Refuse to load anything bigger than this into the editor.
const MAX_BYTES = 2 * 1024 * 1024;

/**
 * Resolve `rel` against `root` and guarantee the result stays inside `root`.
 * Blocks `..` traversal and absolute paths escaping the workspace. Throws on
 * any escape attempt so callers can return a 400.
 */
function confine(root: string, rel: string): string {
  const base = path.resolve(root);
  const full = path.resolve(base, rel);
  if (full !== base && !full.startsWith(base + path.sep)) {
    throw new Error("path escapes workspace root");
  }
  return full;
}

/** Recursively list the tree under `root`, pruning heavy/VCS dirs. */
export async function listTree(root: string): Promise<FileNode[]> {
  const base = path.resolve(root);

  async function walk(dir: string): Promise<FileNode[]> {
    let entries: import("node:fs").Dirent[];
    try {
      entries = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      return [];
    }
    const nodes: FileNode[] = [];
    for (const e of entries) {
      // Dotfiles are kept (you often want .env, .gitignore); only the heavy
      // generated/VCS dirs in PRUNE are dropped.
      if (e.isDirectory() && PRUNE.has(e.name)) continue;
      const full = path.join(dir, e.name);
      const rel = path.relative(base, full);
      if (e.isDirectory()) {
        nodes.push({
          name: e.name,
          path: rel,
          dir: true,
          children: await walk(full),
        });
      } else if (e.isFile()) {
        nodes.push({ name: e.name, path: rel, dir: false });
      }
    }
    // Dirs first, then files; each alphabetical.
    nodes.sort((a, b) => {
      if (a.dir !== b.dir) return a.dir ? -1 : 1;
      return a.name.toLowerCase().localeCompare(b.name.toLowerCase());
    });
    return nodes;
  }

  return walk(base);
}

// Image extensions → MIME type. These are returned as data URLs for preview
// rather than being rejected as binary.
const IMAGE_MIME: Record<string, string> = {
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".webp": "image/webp",
  ".svg": "image/svg+xml",
  ".bmp": "image/bmp",
  ".ico": "image/x-icon",
  ".avif": "image/avif",
};

/** Read a single file's contents, with a language hint from its extension. */
export async function readFile(
  root: string,
  rel: string,
): Promise<FileContent> {
  const full = confine(root, rel);
  const stat = await fs.stat(full);
  if (!stat.isFile()) throw new Error("not a file");
  if (stat.size > MAX_BYTES) throw new Error("file too large to open");
  const buf = await fs.readFile(full);

  const mime = IMAGE_MIME[path.extname(rel).toLowerCase()];
  if (mime) {
    return {
      path: rel,
      content: `data:${mime};base64,${buf.toString("base64")}`,
      language: "",
      kind: "image",
    };
  }

  // Crude binary sniff: a NUL byte in the first chunk means "not text".
  if (buf.subarray(0, 8000).includes(0)) throw new Error("binary file");
  return {
    path: rel,
    content: buf.toString("utf8"),
    language: languageFor(rel),
    kind: "text",
  };
}

/** Write `content` to a file inside the workspace. Creates parent dirs. */
export async function writeFile(
  root: string,
  rel: string,
  content: string,
): Promise<void> {
  const full = confine(root, rel);
  await fs.mkdir(path.dirname(full), { recursive: true });
  await fs.writeFile(full, content, "utf8");
}

/** Map a filename to a Monaco language id. Falls back to plaintext. */
function languageFor(rel: string): string {
  const ext = path.extname(rel).toLowerCase();
  const map: Record<string, string> = {
    ".ts": "typescript",
    ".tsx": "typescript",
    ".js": "javascript",
    ".jsx": "javascript",
    ".mjs": "javascript",
    ".cjs": "javascript",
    ".json": "json",
    ".css": "css",
    ".scss": "scss",
    ".html": "html",
    ".md": "markdown",
    ".py": "python",
    ".rs": "rust",
    ".go": "go",
    ".sh": "shell",
    ".yml": "yaml",
    ".yaml": "yaml",
    ".toml": "ini",
    ".sql": "sql",
    ".clj": "clojure",
    ".cljs": "clojure",
    ".edn": "clojure",
  };
  return map[ext] ?? "plaintext";
}
