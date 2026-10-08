import { useState, type ReactNode } from "react";
import { motion } from "framer-motion";
import { MdArrowBack } from "react-icons/md";
import { cn } from "@/utils/cn";
import { Ripple } from "./Ripple";
import { IconButton } from "./Button";

/* ───────────── Tab page with MD3 large → small collapsing top app bar ───────────── */
export function TabPage({
  title,
  actions,
  children,
}: {
  title: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  const [scrolled, setScrolled] = useState(false);
  return (
    <div className="flex h-full flex-col bg-surface">
      <header
        className={cn(
          "z-10 shrink-0 pt-[var(--sat)] transition-colors duration-300",
          scrolled ? "bg-surface-container" : "bg-surface",
        )}
      >
        <div className="flex h-16 items-center gap-2 pl-5 pr-3">
          <motion.h1
            initial={false}
            animate={{ opacity: scrolled ? 1 : 0, y: scrolled ? 0 : 8 }}
            transition={{ duration: 0.22, ease: [0.2, 0, 0, 1] }}
            className="min-w-0 flex-1 truncate type-title-large text-on-surface"
          >
            {title}
          </motion.h1>
          {actions}
        </div>
      </header>
      <div
        className="min-h-0 flex-1 overflow-y-auto no-scrollbar"
        onScroll={(e) => setScrolled(e.currentTarget.scrollTop > 40)}
      >
        <h1 className="px-5 pb-5 pt-1 type-headline-large text-on-surface">{title}</h1>
        <div className="px-4 pb-8">{children}</div>
      </div>
    </div>
  );
}

/* ───────────── Small top app bar for pushed screens ───────────── */
export function TopBar({
  title,
  subtitle,
  onBack,
  actions,
  className,
  leading,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  onBack?: () => void;
  actions?: ReactNode;
  className?: string;
  leading?: ReactNode;
}) {
  return (
    <header className={cn("z-10 shrink-0 bg-surface pt-[var(--sat)]", className)}>
      <div className="flex h-16 items-center gap-1 px-1">
        {onBack && (
          <IconButton aria-label="返回" onClick={onBack} className="text-current">
            <MdArrowBack />
          </IconButton>
        )}
        {leading}
        <div className="ml-1 min-w-0 flex-1">
          <div className="truncate type-title-large leading-7">{title}</div>
          {subtitle && <div className="truncate type-label-medium opacity-70">{subtitle}</div>}
        </div>
        <div className="flex items-center pr-1">{actions}</div>
      </div>
    </header>
  );
}

/* ───────────── Grouped list (MD3 Expressive segmented list) ───────────── */
export function ListGroup({
  title,
  action,
  children,
  className,
}: {
  title?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("mt-7 first:mt-0", className)}>
      {(title || action) && (
        <div className="flex min-h-10 items-center justify-between pb-1 pl-4 pr-1">
          <h2 className="type-title-small text-primary">{title}</h2>
          {action}
        </div>
      )}
      <div className="flex flex-col gap-[2px] overflow-hidden rounded-[24px]">{children}</div>
    </section>
  );
}

export function ListItem({
  icon,
  headline,
  supporting,
  trailing,
  onClick,
  disabled,
  className,
  children,
}: {
  icon?: ReactNode;
  headline: ReactNode;
  supporting?: ReactNode;
  trailing?: ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  className?: string;
  children?: ReactNode;
}) {
  const body = (
    <>
      {onClick && <Ripple disabled={disabled} />}
      <div className="flex min-h-[72px] items-center gap-4 px-4 py-3">
        {icon && <span className="flex shrink-0 text-[24px] text-on-surface-variant">{icon}</span>}
        <span className="min-w-0 flex-1">
          <span className="block truncate type-body-large text-on-surface">{headline}</span>
          {supporting && (
            <span className="mt-0.5 block truncate type-body-medium text-on-surface-variant">{supporting}</span>
          )}
        </span>
        {trailing}
      </div>
      {children}
    </>
  );
  const cls = cn(
    "relative block w-full rounded-[4px] bg-surface-container-low text-left outline-none transition-opacity",
    disabled && "opacity-50",
    className,
  );
  return onClick ? (
    <div role="button" tabIndex={0} aria-disabled={disabled} onClick={disabled ? undefined : onClick} className={cls}>
      {body}
    </div>
  ) : (
    <div className={cls}>{body}</div>
  );
}

/* ───────────── Navigation bar ───────────── */
export interface NavItem<T extends string> {
  key: T;
  label: string;
  icon: ReactNode;
  activeIcon: ReactNode;
}

export function NavigationBar<T extends string>({
  items,
  value,
  onChange,
}: {
  items: NavItem<T>[];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <nav className="z-10 shrink-0 bg-surface-container pb-[var(--sab)]">
      <div className="flex h-20">
        {items.map((it) => {
          const active = it.key === value;
          return (
            <button
              key={it.key}
              onClick={() => onChange(it.key)}
              className="group flex flex-1 flex-col items-center justify-center gap-1 outline-none"
              aria-current={active ? "page" : undefined}
            >
              <span className="relative grid h-8 w-16 place-items-center rounded-full text-on-surface-variant">
                <motion.span
                  className="absolute inset-0 -z-[1] rounded-full bg-secondary-container"
                  initial={false}
                  animate={{ scaleX: active ? 1 : 0.4, opacity: active ? 1 : 0 }}
                  transition={{ duration: 0.3, ease: [0.2, 0, 0, 1] }}
                />
                <Ripple />
                <span
                  className={cn(
                    "relative flex text-[24px] transition-colors duration-200",
                    active ? "text-on-secondary-container" : "text-on-surface-variant",
                  )}
                >
                  {active ? it.activeIcon : it.icon}
                </span>
              </span>
              <span
                className={cn(
                  "type-label-medium transition-colors duration-200",
                  active ? "text-on-surface" : "text-on-surface-variant",
                )}
                style={{ fontWeight: active ? 700 : 500 }}
              >
                {it.label}
              </span>
            </button>
          );
        })}
      </div>
    </nav>
  );
}
