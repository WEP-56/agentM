export const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

export function fmtMB(mb: number) {
  if (mb >= 1024) return `${(mb / 1024).toFixed(mb >= 10240 ? 1 : 2)} GB`;
  return `${Math.round(mb)} MB`;
}

export function fmtUptime(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`;
}

export function fmtClock(t: number, withMs = false) {
  const d = new Date(t);
  const pad = (n: number, l = 2) => String(n).padStart(l, "0");
  const base = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  return withMs ? `${base}.${pad(d.getMilliseconds(), 3)}` : base;
}

export function hostOf(url: string) {
  try {
    return new URL(url).host;
  } catch {
    return url || "—";
  }
}

export function maskKey(key: string) {
  if (!key) return "";
  if (key.length <= 8) return "••••";
  return `${key.slice(0, 5)}••••${key.slice(-4)}`;
}

export const uid = () => Math.random().toString(36).slice(2, 9);
