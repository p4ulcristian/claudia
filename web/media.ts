// Photos and videos the user attaches to a message (the composer's picker, paste, drop), turned
// into what Claude can read: images. A photo goes as it is when Claude takes it (JPEG, PNG, GIF,
// WebP, small enough), otherwise redrawn as a JPEG of at most PHOTO_EDGE px. Claude reads no video,
// so a video goes as frames drawn in the browser, at most FRAMES, spread evenly (one per second for
// short ones), with a note before them saying what they are.

export type SentImage = { media_type: string; data: string; note?: string };
// One thing in the composer: a photo (one image) or a video (its frames); `url` is its thumbnail.
export type Attachment = { url: string; images: SentImage[]; video?: { seconds: number } };

const TAKEN = new Set(["image/jpeg", "image/png", "image/gif", "image/webp"]);
const PHOTO_EDGE = 2048;
const PHOTO_BYTES = 3.5 * 1024 * 1024; // under the API's 5 MB per image once base64-encoded
const FRAME_EDGE = 1280;
const FRAMES = 30;

const dataOf = (url: string) => url.slice(url.indexOf(",") + 1);

function draw(src: CanvasImageSource, w: number, h: number, edge: number, quality: number) {
  const k = Math.min(1, edge / Math.max(w, h));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(w * k));
  canvas.height = Math.max(1, Math.round(h * k));
  canvas.getContext("2d")!.drawImage(src, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL("image/jpeg", quality);
}

const readUrl = (f: Blob) => new Promise<string>((ok, no) => {
  const r = new FileReader();
  r.onload = () => ok(String(r.result));
  r.onerror = () => no(r.error);
  r.readAsDataURL(f);
});

async function photo(f: File): Promise<Attachment> {
  const bitmap = await createImageBitmap(f);
  try {
    if (TAKEN.has(f.type) && f.size <= PHOTO_BYTES && Math.max(bitmap.width, bitmap.height) <= PHOTO_EDGE) {
      const url = await readUrl(f);
      return { url, images: [{ media_type: f.type, data: dataOf(url) }] };
    }
    const url = draw(bitmap, bitmap.width, bitmap.height, PHOTO_EDGE, 0.9);
    return { url, images: [{ media_type: "image/jpeg", data: dataOf(url) }] };
  } finally {
    bitmap.close();
  }
}

const once = (el: HTMLMediaElement, ok: string) => new Promise<void>((resolve, reject) => {
  const done = () => { el.removeEventListener(ok, done); el.removeEventListener("error", fail); resolve(); };
  const fail = () => { el.removeEventListener(ok, done); el.removeEventListener("error", fail); reject(el.error ?? new Error("video")); };
  el.addEventListener(ok, done);
  el.addEventListener("error", fail);
});

const at = (s: number) => `${s.toFixed(1)} s`;

async function video(f: File): Promise<Attachment> {
  const src = URL.createObjectURL(f);
  const v = document.createElement("video");
  v.muted = true;
  v.playsInline = true;
  v.preload = "auto";
  v.src = src;
  try {
    await once(v, "loadeddata");
    // iOS Safari draws nothing from a video that has never played: start it once, silently.
    await v.play().then(() => v.pause(), () => {});
    const seconds = v.duration;
    const n = Math.max(1, Math.min(FRAMES, Math.ceil(seconds)));
    const times = Array.from({ length: n }, (_, i) => Math.min(seconds * (i + 0.5) / n, Math.max(0, seconds - 0.05)));
    const images: SentImage[] = [];
    let url = "";
    for (const t of times) {
      v.currentTime = t;
      await once(v, "seeked");
      const frame = draw(v, v.videoWidth, v.videoHeight, FRAME_EDGE, 0.8);
      url ||= frame;
      images.push({ media_type: "image/jpeg", data: dataOf(frame) });
    }
    images[0].note = `Video "${f.name}" (${at(seconds)}, ${v.videoWidth}x${v.videoHeight}): ${n} frame${n > 1 ? "s" : ""} follow in order, taken at ${times.map(at).join(", ")}. There is no sound.`;
    return { url, images, video: { seconds } };
  } finally {
    v.removeAttribute("src");
    v.load();
    URL.revokeObjectURL(src);
  }
}

// null: not a photo or video, or it could not be read.
export async function readMedia(f: File): Promise<Attachment | null> {
  try {
    if (f.type.startsWith("image/")) return await photo(f);
    if (f.type.startsWith("video/")) return await video(f);
  } catch (e) {
    console.error("attach", f.name, e);
  }
  return null;
}
