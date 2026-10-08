import { useEffect, useRef } from "react";

/**
 * MD3 state layer + touch ripple.
 * Drop inside any element; it attaches to its parent (like <md-ripple>).
 * Only the innermost ripple host reacts to a press.
 */
export function Ripple({ disabled = false }: { disabled?: boolean }) {
  const ref = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    const layer = ref.current;
    const host = layer?.parentElement;
    if (!layer || !host) return;
    host.dataset.ripple = "";
    host.style.isolation = "isolate";
    if (getComputedStyle(host).position === "static") host.style.position = "relative";
    if (disabled) return;

    const onDown = (e: PointerEvent) => {
      if (e.pointerType === "mouse" && e.button !== 0) return;
      if ((host as HTMLButtonElement).disabled) return;
      const target = e.target as Element | null;
      if (target && target.closest("[data-ripple]") !== host) return;

      const rect = host.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const r = Math.max(
        Math.hypot(x, y),
        Math.hypot(rect.width - x, y),
        Math.hypot(x, rect.height - y),
        Math.hypot(rect.width - x, rect.height - y),
      );
      const wave = document.createElement("span");
      wave.className = "md-ripple-wave";
      wave.style.width = wave.style.height = `${r * 2}px`;
      wave.style.left = `${x - r}px`;
      wave.style.top = `${y - r}px`;
      layer.appendChild(wave);
      const t0 = performance.now();

      const release = () => {
        window.removeEventListener("pointerup", release);
        window.removeEventListener("pointercancel", release);
        const wait = Math.max(0, 220 - (performance.now() - t0));
        window.setTimeout(() => {
          wave.style.opacity = "0";
          window.setTimeout(() => wave.remove(), 380);
        }, wait);
      };
      window.addEventListener("pointerup", release);
      window.addEventListener("pointercancel", release);
    };

    host.addEventListener("pointerdown", onDown);
    return () => host.removeEventListener("pointerdown", onDown);
  }, [disabled]);

  return <span ref={ref} aria-hidden className="md-ripple" />;
}
