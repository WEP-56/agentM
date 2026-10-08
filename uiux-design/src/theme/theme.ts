import {
  argbFromHex,
  Blend,
  hexFromArgb,
  Hct,
  SchemeTonalSpot,
  TonalPalette,
} from "@material/material-color-utilities";
import { useSyncExternalStore } from "react";
import { isNative, nativeRequest } from "@/platform/native";

export type ThemeMode = "system" | "light" | "dark";

/** Seed colors offered in 设置 › 外观. Each produces a full MD3 tonal-spot scheme. */
export const SEEDS: { id: string; hex: string; name: string }[] = [
  { id: "violet", hex: "#6750A4", name: "紫罗兰" },
  { id: "blue", hex: "#1B6EF3", name: "海蓝" },
  { id: "teal", hex: "#00897B", name: "青碧" },
  { id: "green", hex: "#4C7A2E", name: "苔绿" },
  { id: "amber", hex: "#B5651D", name: "琥珀" },
  { id: "rose", hex: "#B4446C", name: "玫瑰" },
];

const ROLES = [
  "primary",
  "onPrimary",
  "primaryContainer",
  "onPrimaryContainer",
  "secondary",
  "onSecondary",
  "secondaryContainer",
  "onSecondaryContainer",
  "tertiary",
  "onTertiary",
  "tertiaryContainer",
  "onTertiaryContainer",
  "error",
  "onError",
  "errorContainer",
  "onErrorContainer",
  "surface",
  "onSurface",
  "surfaceVariant",
  "onSurfaceVariant",
  "surfaceDim",
  "surfaceBright",
  "surfaceContainerLowest",
  "surfaceContainerLow",
  "surfaceContainer",
  "surfaceContainerHigh",
  "surfaceContainerHighest",
  "outline",
  "outlineVariant",
  "inverseSurface",
  "inverseOnSurface",
  "inversePrimary",
  "scrim",
] as const;

const kebab = (s: string) => s.replace(/[A-Z]/g, (m) => "-" + m.toLowerCase());

function scheme(seed: string, dark: boolean) {
  return new SchemeTonalSpot(Hct.fromInt(argbFromHex(seed)), dark, 0);
}

const cache = new Map<string, Record<string, string>>();

export function schemeVars(seed: string, dark: boolean): Record<string, string> {
  const key = `${seed}:${dark}`;
  const hit = cache.get(key);
  if (hit) return hit;

  const src = argbFromHex(seed);
  const s = scheme(seed, dark);
  const out: Record<string, string> = {};
  for (const role of ROLES) out[`--md-${kebab(role)}`] = hexFromArgb(s[role] as number);

  // Custom harmonized roles (success / warn) — blended toward the seed hue like MD3 custom colors.
  const green = TonalPalette.fromInt(Blend.harmonize(argbFromHex("#1E8E3E"), src));
  out["--md-success"] = hexFromArgb(green.tone(dark ? 80 : 40));
  out["--md-on-success"] = hexFromArgb(green.tone(dark ? 20 : 100));
  out["--md-success-container"] = hexFromArgb(green.tone(dark ? 30 : 90));
  out["--md-on-success-container"] = hexFromArgb(green.tone(dark ? 90 : 10));
  const amber = TonalPalette.fromInt(Blend.harmonize(argbFromHex("#E39A00"), src));
  out["--md-warn"] = hexFromArgb(amber.tone(dark ? 80 : 50));

  // Terminal palette — always dark, tinted by the seed.
  const d = dark ? s : scheme(seed, true);
  out["--term-bg"] = hexFromArgb(d.neutralPalette.tone(5));
  out["--term-bar"] = hexFromArgb(d.neutralPalette.tone(8));
  out["--term-key"] = hexFromArgb(d.neutralPalette.tone(14));
  out["--term-fg"] = hexFromArgb(d.neutralPalette.tone(92));
  out["--term-dim"] = hexFromArgb(d.neutralVariantPalette.tone(66));
  out["--term-faint"] = hexFromArgb(d.neutralVariantPalette.tone(42));
  out["--term-border"] = hexFromArgb(d.neutralVariantPalette.tone(28));
  out["--term-accent"] = hexFromArgb(d.primaryPalette.tone(80));
  out["--term-ok"] = hexFromArgb(green.tone(75));
  out["--term-err"] = hexFromArgb(TonalPalette.fromInt(Blend.harmonize(argbFromHex("#E5484D"), src)).tone(70));
  out["--term-warn"] = hexFromArgb(amber.tone(78));

  cache.set(key, out);
  return out;
}

export function applyTheme(seed: string, dark: boolean) {
  if (typeof document === "undefined") return;
  const vars = schemeVars(seed, dark);
  const root = document.documentElement;
  for (const k in vars) root.style.setProperty(k, vars[k]);
  root.style.colorScheme = dark ? "dark" : "light";
  root.classList.toggle("dark", dark);
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", vars["--md-surface"]);
  if (isNative) void nativeRequest('setAppearance', { dark, background: vars['--md-surface'] }).catch(() => {});
}

/** Three-tone swatch (primary / secondary / tertiary) like Android's "Wallpaper & style" picker. */
export function swatch(seed: string, dark: boolean) {
  const s = scheme(seed, dark);
  return {
    a: hexFromArgb(s.primaryPalette.tone(dark ? 80 : 40)),
    b: hexFromArgb(s.secondaryPalette.tone(dark ? 60 : 80)),
    c: hexFromArgb(s.tertiaryPalette.tone(dark ? 70 : 70)),
  };
}

const tileCache = new Map<string, { bg: string; fg: string; solid: string }>();
/** Brand color harmonized with the seed → "themed icon" tile colors. */
export function agentTile(color: string, seed: string, dark: boolean) {
  const key = `${color}:${seed}:${dark}`;
  const hit = tileCache.get(key);
  if (hit) return hit;
  const p = TonalPalette.fromInt(Blend.harmonize(argbFromHex(color), argbFromHex(seed)));
  const res = {
    bg: hexFromArgb(p.tone(dark ? 28 : 90)),
    fg: hexFromArgb(p.tone(dark ? 88 : 32)),
    solid: hexFromArgb(p.tone(dark ? 80 : 45)),
  };
  tileCache.set(key, res);
  return res;
}

/* ───────────── System dark-mode subscription (prefers-color-scheme) ───────────── */
const mq = typeof window !== "undefined" ? window.matchMedia("(prefers-color-scheme: dark)") : null;
function subscribe(cb: () => void) {
  mq?.addEventListener("change", cb);
  return () => mq?.removeEventListener("change", cb);
}
export function getSystemDark() {
  return mq?.matches ?? false;
}
export function useSystemDark() {
  return useSyncExternalStore(subscribe, getSystemDark, () => false);
}
export function resolveDark(mode: ThemeMode, systemDark: boolean) {
  return mode === "dark" || (mode === "system" && systemDark);
}
