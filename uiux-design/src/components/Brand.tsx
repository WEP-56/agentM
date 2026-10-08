import { useMemo, type CSSProperties, type ReactNode } from "react";
import type { AgentDef, Glyph } from "@/data/agents";
import { useApp } from "@/store/useApp";
import { agentTile, resolveDark, useSystemDark } from "@/theme/theme";
import { cn } from "@/utils/cn";

export function useIsDark() {
  const mode = useApp((s) => s.settings.themeMode);
  const sys = useSystemDark();
  return resolveDark(mode, sys);
}

/* ───────────── MD3 Expressive shapes (polar-generated) ───────────── */
function polarPath(n: number, amp: number, steps = 240) {
  let d = "";
  for (let i = 0; i <= steps; i++) {
    const t = (i / steps) * Math.PI * 2;
    const r = 50 * (1 - amp + amp * Math.cos(n * t));
    const x = 50 + r * Math.cos(t - Math.PI / 2);
    const y = 50 + r * Math.sin(t - Math.PI / 2);
    d += `${i ? "L" : "M"}${x.toFixed(2)} ${y.toFixed(2)}`;
  }
  return d + "Z";
}

export const SHAPES = {
  cookie9: polarPath(9, 0.075),
  cookie12: polarPath(12, 0.05),
  sunny: polarPath(8, 0.045),
  clover: polarPath(4, 0.16),
  flower: polarPath(6, 0.12),
};

export function Shape({
  kind = "cookie9",
  className,
  style,
  children,
  spin,
}: {
  kind?: keyof typeof SHAPES;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
  spin?: "slow" | "med";
}) {
  return (
    <div className={cn("relative grid place-items-center", className)} style={style}>
      <svg
        viewBox="0 0 100 100"
        className={cn(
          "absolute inset-0 h-full w-full",
          spin === "slow" && "animate-spin-slow",
          spin === "med" && "animate-spin-med",
        )}
      >
        <path d={SHAPES[kind]} fill="currentColor" />
      </svg>
      {children && <div className="relative">{children}</div>}
    </div>
  );
}

/* ───────────── Glyphs (original marks, not vendor logos) ───────────── */
export function GlyphIcon({ kind, size = 24 }: { kind: Glyph | "app"; size?: number }) {
  const common = {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 2.4,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
  };
  switch (kind) {
    case "spark":
      return (
        <svg {...common}>
          {[0, 45, 90, 135].map((a) => (
            <line key={a} x1="12" y1="3.6" x2="12" y2="20.4" transform={`rotate(${a} 12 12)`} />
          ))}
        </svg>
      );
    case "prompt":
      return (
        <svg {...common}>
          <path d="M5 7.5 9.5 12 5 16.5" />
          <path d="M12.5 17h6.5" />
        </svg>
      );
    case "block":
      return (
        <svg {...common} strokeWidth={2.2}>
          <rect x="4.5" y="3.5" width="15" height="17" rx="1.5" />
          <rect x="8.5" y="11" width="7" height="5.5" fill="currentColor" stroke="none" />
        </svg>
      );
    case "pi":
      return (
        <svg {...common}>
          <path d="M4.5 7.5h15" />
          <path d="M9 7.5V19" />
          <path d="M15 7.5v8.2c0 2 .9 3.1 2.6 3.1" />
        </svg>
      );
    case "wave":
      return (
        <svg {...common} strokeWidth={2.2}>
          <path d="M3 9.5c1.5-1.8 3-1.8 4.5 0s3 1.8 4.5 0 3-1.8 4.5 0 3 1.8 4.5 0" />
          <path d="M3 15c1.5-1.8 3-1.8 4.5 0s3 1.8 4.5 0 3-1.8 4.5 0 3 1.8 4.5 0" />
        </svg>
      );
    case "app":
      return (
        <svg {...common}>
          <path d="M5.5 8 10 12l-4.5 4" />
          <path d="M12.5 16.5h6" />
          <path d="M17.5 4.5v3M16 6h3" strokeWidth={1.8} />
        </svg>
      );
  }
}

/* ───────────── App logo ───────────── */
export function AppLogo({ size = 56, spin }: { size?: number; spin?: boolean }) {
  return (
    <Shape kind="cookie9" spin={spin ? "slow" : undefined} className="text-primary" style={{ width: size, height: size }}>
      <span className="text-on-primary">
        <GlyphIcon kind="app" size={size * 0.5} />
      </span>
    </Shape>
  );
}

/* ───────────── Agent icon (themed tile) ───────────── */
export function AgentIcon({ agent, size = 48, className }: { agent: AgentDef; size?: number; className?: string }) {
  const dark = useIsDark();
  const seed = useApp((s) => s.settings.seed);
  const c = useMemo(() => agentTile(agent.color, seed, dark), [agent.color, seed, dark]);
  return (
    <div
      className={cn("grid shrink-0 place-items-center", className)}
      style={{ width: size, height: size, borderRadius: size * 0.34, background: c.bg, color: c.fg }}
    >
      <GlyphIcon kind={agent.glyph} size={Math.round(size * 0.5)} />
    </div>
  );
}
