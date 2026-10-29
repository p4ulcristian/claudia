// Alert texts sealed for one phone (decision 24): the chat's title and the start of Claude's reply
// or question travel as AES-256-GCM ciphertext under the 32-byte key the app made, so Apple's push
// servers see only ciphertext and ids. The app's notification extension opens it; the plain alert
// beside it ("Claudia", "New message") is what shows if it can't. node:crypto does the cipher.
import { createCipheriv, randomBytes } from "node:crypto";

const NONCE = 12;

// The app's key as it registered it (base64 of 32 bytes), or null when it isn't one.
export function deviceKey(b64: unknown): string | null {
  if (typeof b64 !== "string" || !/^[A-Za-z0-9+/]{43}=$/.test(b64.trim())) return null;
  return Buffer.from(b64.trim(), "base64").length === 32 ? b64.trim() : null;
}

// base64 of nonce (12) || ciphertext || tag (16), a fresh random nonce each time, no AAD.
export function seal(keyB64: string, text: { title: string; body: string }): string {
  const key = Buffer.from(keyB64, "base64");
  if (key.length !== 32) throw new Error("not a 32-byte key");
  const nonce = randomBytes(NONCE);
  const c = createCipheriv("aes-256-gcm", key, nonce);
  const sealed = Buffer.concat([c.update(JSON.stringify(text), "utf8"), c.final()]);
  return Buffer.concat([nonce, sealed, c.getAuthTag()]).toString("base64");
}
