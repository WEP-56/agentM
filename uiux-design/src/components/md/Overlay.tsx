import { createContext, useContext, useEffect, useLayoutEffect, useState, type ReactNode, type RefObject } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion, useDragControls } from "framer-motion";
import { cn } from "@/utils/cn";
import { useApp } from "@/store/useApp";
import { Ripple } from "./Ripple";

const EMPH_DECEL = [0.05, 0.7, 0.1, 1] as const;
const EMPH = [0.2, 0, 0, 1] as const;

/** Overlays render into the phone screen (not the browser window). */
export const OverlayCtx = createContext<HTMLElement | null>(null);

export function Portal({ children }: { children: ReactNode }) {
  const el = useContext(OverlayCtx);
  return el ? createPortal(children, el) : null;
}

/* ───────────── Dialog ───────────── */
export function Dialog({
  open,
  onClose,
  icon,
  title,
  children,
  actions,
}: {
  open: boolean;
  onClose: () => void;
  icon?: ReactNode;
  title: ReactNode;
  children?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <Portal>
      <AnimatePresence>
        {open && (
          <motion.div key="dialog" className="pointer-events-auto absolute inset-0 z-30 flex items-center justify-center p-6">
            <motion.div
              className="absolute inset-0 bg-scrim/40"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.2 }}
              onClick={onClose}
            />
            <motion.div
              role="dialog"
              aria-modal
              initial={{ opacity: 0, scale: 0.9, y: -16 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.96, transition: { duration: 0.15 } }}
              transition={{ duration: 0.38, ease: EMPH_DECEL }}
              className="relative w-full max-w-[340px] rounded-[28px] bg-surface-container-high p-6 shadow-elev-3"
            >
              {icon && <div className="mb-4 flex justify-center text-[24px] text-secondary">{icon}</div>}
              <h2 className={cn("type-headline-small text-on-surface", icon && "text-center")}>{title}</h2>
              {children && <div className="mt-4 type-body-medium text-on-surface-variant">{children}</div>}
              {actions && <div className="mt-6 flex flex-wrap justify-end gap-2">{actions}</div>}
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </Portal>
  );
}

/* ───────────── Modal bottom sheet (drag handle to dismiss) ───────────── */
export function BottomSheet({
  open,
  onClose,
  title,
  children,
  footer,
  label,
}: {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  label?: string;
}) {
  const controls = useDragControls();
  return (
    <Portal>
      <AnimatePresence>
        {open && (
          <motion.div key="sheet" className="pointer-events-auto absolute inset-0 z-30">
            <motion.div
              className="absolute inset-0 bg-scrim/40"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.25 }}
              onClick={onClose}
            />
            <motion.div
              role={label ? 'dialog' : undefined}
              aria-modal={label ? true : undefined}
              aria-label={label}
              drag="y"
              dragListener={false}
              dragControls={controls}
              dragConstraints={{ top: 0, bottom: 0 }}
              dragElastic={{ top: 0, bottom: 0.7 }}
              onDragEnd={(_, info) => {
                if (info.offset.y > 110 || info.velocity.y > 600) onClose();
              }}
              initial={{ y: "100%" }}
              animate={{ y: 0 }}
              exit={{ y: "100%", transition: { duration: 0.25, ease: [0.3, 0, 0.8, 0.15] } }}
              transition={{ duration: 0.42, ease: EMPH_DECEL }}
              className="absolute inset-x-0 bottom-0 flex max-h-[90%] flex-col rounded-t-[28px] bg-surface-container-low shadow-elev-1"
            >
              <div
                className="flex shrink-0 cursor-grab touch-none flex-col active:cursor-grabbing"
                onPointerDown={(e) => controls.start(e)}
              >
                <div className="flex justify-center pb-2 pt-4">
                  <span className="h-1 w-8 rounded-full bg-on-surface-variant/40" />
                </div>
                {title && <div className="px-6 pb-3 pt-1 type-title-large text-on-surface">{title}</div>}
              </div>
              <div className="min-h-0 flex-1 overflow-y-auto px-6 pb-4 no-scrollbar">{children}</div>
              {footer && <div className="shrink-0 px-6 pb-[calc(16px+var(--sab))] pt-2">{footer}</div>}
              {!footer && <div className="shrink-0 pb-[var(--sab)]" />}
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </Portal>
  );
}

/* ───────────── Menu ───────────── */
export interface MenuItem {
  label: string;
  icon?: ReactNode;
  onClick: () => void;
  danger?: boolean;
  disabled?: boolean;
}

export function Menu({
  open,
  onClose,
  anchor,
  items,
  align = "end",
}: {
  open: boolean;
  onClose: () => void;
  anchor: RefObject<HTMLElement | null>;
  items: MenuItem[];
  align?: "start" | "end";
}) {
  const root = useContext(OverlayCtx);
  const [pos, setPos] = useState<{ top: number; left?: number; right?: number } | null>(null);

  useLayoutEffect(() => {
    if (!open || !anchor.current || !root) return;
    const a = anchor.current.getBoundingClientRect();
    const r = root.getBoundingClientRect();
    setPos(
      align === "end"
        ? { top: a.bottom - r.top + 4, right: Math.max(8, r.right - a.right) }
        : { top: a.bottom - r.top + 4, left: Math.max(8, a.left - r.left) },
    );
  }, [open, anchor, root, align]);

  return (
    <Portal>
      <AnimatePresence>
        {open && pos && (
          <motion.div key="menu" className="pointer-events-auto absolute inset-0 z-40" onClick={onClose}>
            <motion.div
              onClick={(e) => e.stopPropagation()}
              style={{ top: pos.top, left: pos.left, right: pos.right, transformOrigin: align === "end" ? "top right" : "top left" }}
              initial={{ opacity: 0, scale: 0.9, y: -6 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.96, transition: { duration: 0.12 } }}
              transition={{ duration: 0.22, ease: EMPH }}
              className="absolute min-w-[180px] overflow-hidden rounded-2xl bg-surface-container py-2 shadow-elev-2"
            >
              {items.map((it) => (
                <button
                  key={it.label}
                  disabled={it.disabled}
                  onClick={() => {
                    onClose();
                    it.onClick();
                  }}
                  className={cn(
                    "relative flex h-12 w-full items-center gap-3 px-4 text-left type-label-large outline-none disabled:opacity-40",
                    it.danger ? "text-error" : "text-on-surface",
                  )}
                >
                  <Ripple />
                  {it.icon && (
                    <span className={cn("flex text-[22px]", it.danger ? "text-error" : "text-on-surface-variant")}>
                      {it.icon}
                    </span>
                  )}
                  {it.label}
                </button>
              ))}
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </Portal>
  );
}

/* ───────────── Snackbar ───────────── */
export function SnackbarHost({ bottom }: { bottom: string }) {
  const snack = useApp((s) => s.snack);
  const dismiss = useApp((s) => s.dismissSnack);

  useEffect(() => {
    if (!snack) return;
    const t = setTimeout(() => dismiss(snack.id), snack.action ? 5000 : 3200);
    return () => clearTimeout(t);
  }, [snack, dismiss]);

  return (
    <div className="pointer-events-none absolute inset-x-0 z-40 px-3 transition-[bottom] duration-300" style={{ bottom }}>
      <AnimatePresence mode="popLayout">
        {snack && (
          <motion.div
            key={snack.id}
            layout
            initial={{ opacity: 0, y: 24, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 12, transition: { duration: 0.16 } }}
            transition={{ duration: 0.32, ease: EMPH_DECEL }}
            className="pointer-events-auto flex min-h-12 items-center gap-2 rounded-[8px] bg-inverse-surface py-1.5 pl-4 pr-2 shadow-elev-3"
          >
            <span className="flex-1 py-1.5 type-body-medium text-inverse-on-surface">{snack.text}</span>
            {snack.action && (
              <button
                onClick={() => {
                  snack.action?.run();
                  dismiss(snack.id);
                }}
                className="relative h-10 shrink-0 rounded-full px-3 type-label-large text-inverse-primary"
              >
                <Ripple />
                {snack.action.label}
              </button>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
