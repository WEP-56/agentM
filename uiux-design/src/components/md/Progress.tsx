import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { cn } from "@/utils/cn";

/* ───────────── Linear (MD3 2024: indicator + gap + track) ───────────── */
export function LinearProgress({ value, className }: { value?: number; className?: string }) {
  if (value === undefined)
    return (
      <div className={cn("relative h-1 w-full overflow-hidden rounded-full bg-secondary-container", className)}>
        <span className="animate-indeterminate absolute inset-y-0 rounded-full bg-primary" />
      </div>
    );
  const pct = Math.max(0, Math.min(1, value)) * 100;
  return (
    <div className={cn("flex h-1 w-full items-center gap-1", className)}>
      <span className="h-full rounded-full bg-primary transition-[width] duration-200" style={{ width: `${pct}%` }} />
      <span className="h-full flex-1 rounded-full bg-secondary-container" />
    </div>
  );
}

/* ───────────── Circular ───────────── */
export function CircularProgress({
  value,
  size = 40,
  stroke = 4,
  className,
}: {
  value?: number;
  size?: number;
  stroke?: number;
  className?: string;
}) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  if (value === undefined)
    return (
      <svg className={cn("animate-spin-fast shrink-0", className)} width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke="var(--md-primary)"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={`${c * 0.3} ${c}`}
        />
      </svg>
    );
  const v = Math.max(0, Math.min(1, value));
  return (
    <svg
      className={cn("shrink-0 -rotate-90", className)}
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
    >
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--md-secondary-container)" strokeWidth={stroke} />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={r}
        fill="none"
        stroke="var(--md-primary)"
        strokeWidth={stroke}
        strokeLinecap="round"
        strokeDasharray={`${Math.max(0.001, c * v)} ${c}`}
        style={{ transition: "stroke-dasharray 160ms linear" }}
      />
    </svg>
  );
}

/* ───────────── MD3 Expressive wavy linear progress ───────────── */
export function WavyProgress({
  value,
  className,
  amplitude = 3,
  wavelength = 28,
  thickness = 4,
}: {
  value: number;
  className?: string;
  amplitude?: number;
  wavelength?: number;
  thickness?: number;
}) {
  const wrap = useRef<HTMLDivElement>(null);
  const path = useRef<SVGPathElement>(null);
  const track = useRef<SVGLineElement>(null);
  const dot = useRef<SVGCircleElement>(null);
  const target = useRef(value);
  target.current = value;
  const [w, setW] = useState(0);
  const H = 14;
  const mid = H / 2;

  useLayoutEffect(() => {
    const el = wrap.current;
    if (!el) return;
    setW(el.clientWidth);
    const ro = new ResizeObserver(([e]) => setW(e.contentRect.width));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  useEffect(() => {
    if (!w) return;
    let raf = 0;
    let shown = target.current;
    let phase = 0;
    let last = performance.now();
    const half = thickness / 2;

    const tick = (t: number) => {
      const dt = Math.min(50, t - last);
      last = t;
      shown += (target.current - shown) * Math.min(1, dt / 180);
      phase += dt * 0.0055;
      const v = Math.max(0, Math.min(1, shown));
      const amp = v >= 0.995 ? 0 : amplitude * Math.min(1, v * 10);
      const end = w * v;
      const x0 = half;
      const x1 = Math.max(half, end - half);
      let d = "";
      if (x1 > x0 + 0.5) {
        for (let x = x0; x < x1; x += 2) {
          const y = mid + amp * Math.sin((x / wavelength) * Math.PI * 2 - phase);
          d += `${d ? "L" : "M"}${x.toFixed(1)} ${y.toFixed(2)}`;
        }
        const y1 = mid + amp * Math.sin((x1 / wavelength) * Math.PI * 2 - phase);
        d += `L${x1.toFixed(1)} ${y1.toFixed(2)}`;
      }
      path.current?.setAttribute("d", d);
      const ts = Math.min(w - half, end + thickness + 2);
      const hideTrack = ts >= w - half - 0.5;
      track.current?.setAttribute("x1", String(ts));
      track.current?.setAttribute("x2", String(w - half));
      track.current?.setAttribute("opacity", hideTrack ? "0" : "1");
      dot.current?.setAttribute("opacity", hideTrack ? "0" : "1");
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [w, amplitude, wavelength, thickness, mid]);

  return (
    <div ref={wrap} className={cn("h-[14px] w-full", className)}>
      {w > 0 && (
        <svg width={w} height={H} className="block overflow-visible">
          <line
            ref={track}
            y1={mid}
            y2={mid}
            stroke="var(--md-secondary-container)"
            strokeWidth={thickness}
            strokeLinecap="round"
          />
          <circle ref={dot} cx={w - thickness / 2} cy={mid} r={thickness / 2} fill="var(--md-primary)" />
          <path
            ref={path}
            fill="none"
            stroke="var(--md-primary)"
            strokeWidth={thickness}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      )}
    </div>
  );
}
