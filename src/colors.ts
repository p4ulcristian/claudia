// The colours a chat can wear; null = none. The page maps each name to a theme colour (--c-<name>).
export const CHAT_COLORS = [
  "burgundy", "red", "orange", "amber", "yellow", "lime", "green", "teal", "cyan",
  "sky", "blue", "indigo", "violet", "purple", "magenta", "pink", "brown", "ash",
] as const;

// The passive colour: only picked by hand; its chats sink to the bottom of the rail.
export const PASSIVE = "ash";

// A new chat's colour: random among the ones the other chats use least, so neighbours differ.
export function pickColor(used: Iterable<string>): string {
  const count = new Map<string, number>(CHAT_COLORS.filter((c) => c !== PASSIVE).map((c) => [c, 0]));
  for (const c of used) if (count.has(c)) count.set(c, count.get(c)! + 1);
  const least = Math.min(...count.values());
  const pool = [...count.keys()].filter((c) => count.get(c) === least);
  return pool[Math.floor(Math.random() * pool.length)];
}
