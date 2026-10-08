import { useId, type ReactNode } from "react";
import { MdCheck } from "react-icons/md";
import { cn } from "@/utils/cn";
import { Ripple } from "./Ripple";

/* ───────────── Switch ───────────── */
export function Switch({
  checked,
  onChange,
  disabled,
  label,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
  label?: string;
}) {
  return (
    <button
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={(e) => {
        e.stopPropagation();
        onChange(!checked);
      }}
      className={cn(
        "group relative inline-flex h-8 w-[52px] shrink-0 items-center rounded-full border-2 outline-none transition-colors duration-200 disabled:opacity-40",
        checked ? "border-primary bg-primary" : "border-outline bg-surface-container-highest",
      )}
    >
      <span
        className={cn(
          "absolute top-1/2 grid -translate-y-1/2 place-items-center rounded-full transition-all duration-300 ease-[cubic-bezier(0.2,0,0,1)] group-active:scale-110",
          checked ? "left-[22px] size-6 bg-on-primary" : "left-[6px] size-4 bg-outline",
        )}
      >
        <MdCheck
          className={cn(
            "text-[16px] text-primary transition-opacity duration-200",
            checked ? "opacity-100" : "opacity-0",
          )}
        />
      </span>
    </button>
  );
}

/* ───────────── Checkbox / Radio (visual) ───────────── */
export function Checkbox({ checked }: { checked: boolean }) {
  return (
    <span
      className={cn(
        "grid size-[18px] shrink-0 place-items-center rounded-[3px] border-2 transition-colors duration-150",
        checked ? "border-primary bg-primary text-on-primary" : "border-on-surface-variant",
      )}
    >
      <MdCheck className={cn("text-[14px] transition-transform duration-200", checked ? "scale-100" : "scale-0")} />
    </span>
  );
}

export function Radio({ checked }: { checked: boolean }) {
  return (
    <span
      className={cn(
        "grid size-5 shrink-0 place-items-center rounded-full border-2 transition-colors duration-150",
        checked ? "border-primary" : "border-on-surface-variant",
      )}
    >
      <span
        className={cn(
          "size-2.5 rounded-full bg-primary transition-transform duration-200",
          checked ? "scale-100" : "scale-0",
        )}
      />
    </span>
  );
}

/* ───────────── Chips ───────────── */
export function Chip({
  selected,
  onClick,
  children,
  icon,
  className,
}: {
  selected?: boolean;
  onClick?: () => void;
  children: ReactNode;
  icon?: ReactNode;
  className?: string;
}) {
  return (
    <button
      onClick={(e) => {
        e.stopPropagation();
        onClick?.();
      }}
      className={cn(
        "relative inline-flex h-8 shrink-0 items-center gap-1.5 rounded-[8px] border px-3 type-label-large outline-none transition-colors duration-200",
        selected
          ? "border-transparent bg-secondary-container pl-2 text-on-secondary-container"
          : "border-outline-variant text-on-surface-variant",
        !selected && icon && "pl-2",
        className,
      )}
    >
      <Ripple />
      {selected ? <MdCheck className="text-[18px]" /> : icon && <span className="flex text-[18px]">{icon}</span>}
      {children}
    </button>
  );
}

/* ───────────── Segmented button ───────────── */
export function Segmented<T extends string>({
  value,
  onChange,
  options,
}: {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: string; icon?: ReactNode }[];
}) {
  return (
    <div className="flex h-10 w-full overflow-hidden rounded-full border border-outline">
      {options.map((o, i) => {
        const active = o.value === value;
        return (
          <button
            key={o.value}
            onClick={() => onChange(o.value)}
            className={cn(
              "relative flex min-w-0 flex-1 items-center justify-center gap-1.5 px-2 type-label-large outline-none transition-colors duration-200",
              i > 0 && "border-l border-outline",
              active ? "bg-secondary-container text-on-secondary-container" : "text-on-surface",
            )}
          >
            <Ripple />
            <span className="flex text-[18px]">{active ? <MdCheck /> : o.icon}</span>
            <span className="truncate">{o.label}</span>
          </button>
        );
      })}
    </div>
  );
}

/* ───────────── Outlined text field (floating label) ───────────── */
export function TextField({
  label,
  value,
  onChange,
  type = "text",
  trailing,
  mono,
  labelBg = "bg-surface-container-low",
  supporting,
  autoFocus,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  trailing?: ReactNode;
  mono?: boolean;
  labelBg?: string;
  supporting?: string;
  autoFocus?: boolean;
}) {
  const id = useId();
  return (
    <div>
      <div className="relative">
        <input
          id={id}
          value={value}
          type={type}
          placeholder=" "
          autoFocus={autoFocus}
          autoComplete="off"
          spellCheck={false}
          onChange={(e) => onChange(e.target.value)}
          className={cn(
            "peer h-14 w-full rounded-[6px] border border-outline bg-transparent px-4 text-on-surface caret-primary outline-none transition-colors duration-150 focus:border-2 focus:border-primary focus:px-[15px]",
            mono ? "font-mono text-[14px]" : "type-body-large",
            trailing && "pr-12 focus:pr-12",
          )}
        />
        <label
          htmlFor={id}
          className={cn(
            "pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 px-1 type-body-large text-on-surface-variant transition-all duration-150",
            "peer-focus:top-0 peer-focus:text-[12px] peer-focus:leading-4 peer-focus:text-primary",
            "peer-[:not(:placeholder-shown)]:top-0 peer-[:not(:placeholder-shown)]:text-[12px] peer-[:not(:placeholder-shown)]:leading-4",
            labelBg,
          )}
        >
          {label}
        </label>
        {trailing && <div className="absolute right-1 top-1/2 -translate-y-1/2">{trailing}</div>}
      </div>
      {supporting && <p className="px-4 pt-1 type-body-small text-on-surface-variant">{supporting}</p>}
    </div>
  );
}

/* ───────────── Status dot ───────────── */
export function StatusDot({
  tone,
  pulse,
  className,
}: {
  tone: "ok" | "idle" | "busy" | "err";
  pulse?: boolean;
  className?: string;
}) {
  return (
    <span
      className={cn(
        "inline-block size-2 shrink-0 rounded-full",
        tone === "ok" && "bg-success text-success",
        tone === "idle" && "border-[1.5px] border-outline",
        tone === "busy" && "bg-tertiary text-tertiary",
        tone === "err" && "bg-error text-error",
        pulse && "animate-pulse-dot",
        className,
      )}
    />
  );
}
