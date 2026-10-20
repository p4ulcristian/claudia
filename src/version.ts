// Which Claudia this is, shown beside the logo: package.json's version and the commit. A checkout
// asks git; a copy made with git archive (deploy/install.sh) has the commit written into commit.txt.
import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = join(import.meta.dir, "..");
export const VERSION: string = JSON.parse(readFileSync(join(root, "package.json"), "utf8")).version;

function commit() {
  const archived = readFileSync(join(root, "src/commit.txt"), "utf8").trim();
  if (!archived.startsWith("$Format")) return archived;
  const r = Bun.spawnSync(["git", "-C", root, "rev-parse", "--short", "HEAD"]);
  return r.exitCode === 0 ? r.stdout.toString().trim() : null;
}
export const COMMIT = commit();
