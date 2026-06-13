"use client";

// IndexedDB cache for session transcripts. Transcripts can be many MB, so they
// live here rather than localStorage. Every op degrades gracefully to "no
// cache" on any failure — the network path always remains the source of truth.

import type { ClaudeEvent } from "@/lib/types";

const DB_NAME = "claudia";
const STORE = "transcripts";
// Bump when the event shape or server-side filtering changes, to drop stale data.
const CACHE_VERSION = 1;
// Keep at most this many transcripts; evict the least-recently-opened beyond it.
const MAX_ENTRIES = 200;

export interface CachedTranscript {
  sessionId: string;
  version: number;
  events: ClaudeEvent[];
  size: number; // byte offset to resume from (the `since` for delta loads)
  modified: number;
  lastAccess: number;
}

let dbPromise: Promise<IDBDatabase | null> | null = null;

function openDB(): Promise<IDBDatabase | null> {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve) => {
    if (typeof indexedDB === "undefined") return resolve(null);
    let req: IDBOpenDBRequest;
    try {
      req = indexedDB.open(DB_NAME, 1);
    } catch {
      return resolve(null);
    }
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) {
        const store = db.createObjectStore(STORE, { keyPath: "sessionId" });
        store.createIndex("lastAccess", "lastAccess");
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => resolve(null);
  });
  return dbPromise;
}

function tx(
  db: IDBDatabase,
  mode: IDBTransactionMode,
): IDBObjectStore {
  return db.transaction(STORE, mode).objectStore(STORE);
}

/** Open the DB ahead of first use so the first read is warm. */
export function warmTranscriptCache(): void {
  void openDB();
}

export async function getCachedTranscript(
  sessionId: string,
): Promise<CachedTranscript | null> {
  const db = await openDB();
  if (!db) return null;
  return new Promise((resolve) => {
    try {
      const req = tx(db, "readonly").get(sessionId);
      req.onsuccess = () => {
        const entry = req.result as CachedTranscript | undefined;
        if (!entry || entry.version !== CACHE_VERSION) return resolve(null);
        resolve(entry);
      };
      req.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
}

export async function putCachedTranscript(
  entry: Omit<CachedTranscript, "version" | "lastAccess"> & { lastAccess?: number },
): Promise<void> {
  const db = await openDB();
  if (!db) return;
  const record: CachedTranscript = {
    ...entry,
    version: CACHE_VERSION,
    lastAccess: entry.lastAccess ?? Date.now(),
  };
  await new Promise<void>((resolve) => {
    try {
      const store = tx(db, "readwrite");
      store.put(record);
      store.transaction.oncomplete = () => resolve();
      store.transaction.onerror = () => resolve();
    } catch {
      resolve();
    }
  });
  void evict(db);
}

export async function deleteCachedTranscript(sessionId: string): Promise<void> {
  const db = await openDB();
  if (!db) return;
  await new Promise<void>((resolve) => {
    try {
      const store = tx(db, "readwrite");
      store.delete(sessionId);
      store.transaction.oncomplete = () => resolve();
      store.transaction.onerror = () => resolve();
    } catch {
      resolve();
    }
  });
}

// Trim the store to MAX_ENTRIES, dropping the least-recently-opened first.
async function evict(db: IDBDatabase): Promise<void> {
  return new Promise((resolve) => {
    try {
      const store = tx(db, "readwrite");
      const countReq = store.count();
      countReq.onsuccess = () => {
        let over = countReq.result - MAX_ENTRIES;
        if (over <= 0) return resolve();
        const cursorReq = store.index("lastAccess").openCursor();
        cursorReq.onsuccess = () => {
          const cursor = cursorReq.result;
          if (!cursor || over <= 0) return resolve();
          cursor.delete();
          over--;
          cursor.continue();
        };
        cursorReq.onerror = () => resolve();
      };
      countReq.onerror = () => resolve();
    } catch {
      resolve();
    }
  });
}
