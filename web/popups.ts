// Desktop popups: the same moments as the sounds (a chat finished, or waits on you), shown by the
// desktop through the browser's notifications, so they appear on whichever computer has Claudia
// open. Off unless turned on (the toggle in the sidebar, kept per device); turning them on asks
// the browser's permission once. How long a popup stays is the desktop's choice, not the page's.
import type { SoundKind } from "./sounds";

const BODY: Record<SoundKind, string> = { done: "Finished", waiting: "Waiting for you" };

export const popupsAllowed = () => "Notification" in window && Notification.permission === "granted";

// Ask the browser once; false when it's refused (or the browser has no notifications).
export async function allowPopups(): Promise<boolean> {
  if (!("Notification" in window)) return false;
  if (Notification.permission === "default") await Notification.requestPermission();
  return popupsAllowed();
}

// One popup per chat at a time: a newer one for the same chat replaces it. Clicking it brings the
// window to the front and opens the chat. Silent: the sounds toggle is for sound.
export function popup(kind: SoundKind, chat: { id: string; title: string }, onOpen: () => void) {
  if (!popupsAllowed()) return;
  const n = new Notification(chat.title, { body: BODY[kind], tag: chat.id, icon: "/app-icon/icon-192.png", silent: true });
  n.onclick = () => {
    window.focus();
    onOpen();
    n.close();
  };
}
