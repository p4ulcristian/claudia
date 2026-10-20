// The colours a chat can wear; null = none. The page maps each name to a theme colour (--c-<name>).
export const CHAT_COLORS = [
  "red", "orange", "amber", "yellow", "lime", "green", "teal", "cyan",
  "sky", "blue", "indigo", "violet", "purple", "magenta", "pink", "brown",
] as const;

// A new chat's colour: random among the ones the other chats use least, so neighbours differ.
export function pickColor(used: Iterable<string>): string {
  const count = new Map<string, number>(CHAT_COLORS.map((c) => [c, 0]));
  for (const c of used) if (count.has(c)) count.set(c, count.get(c)! + 1);
  const least = Math.min(...count.values());
  const pool = CHAT_COLORS.filter((c) => count.get(c) === least);
  return pool[Math.floor(Math.random() * pool.length)];
}
