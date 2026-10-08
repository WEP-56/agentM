import type { ButtonHTMLAttributes, ReactNode, Ref } from "react";
import { cn } from "@/utils/cn";
import { Ripple } from "./Ripple";

type Variant = "filled" | "tonal" | "outlined" | "text" | "elevated" | "danger" | "dangerText";
type Size = "sm" | "md" | "lg";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  icon?: ReactNode;
  ref?: Ref<HTMLButtonElement>;
}

const VARIANTS: Record<Variant, string> = {
  filled: "bg-primary text-on-primary hover:shadow-elev-1",
  tonal: "bg-secondary-container text-on-secondary-container",
  outlined: "border border-outline-variant text-primary",
  text: "text-primary",
  elevated: "bg-surface-container-low text-primary shadow-elev-1",
  danger: "bg-error text-on-error",
  dangerText: "text-error",
};

const SIZES: Record<Size, string> = {
  sm: "h-8 gap-1.5 px-3 type-label-large",
  md: "h-10 gap-2 px-5 type-label-large",
  lg: "h-14 gap-2.5 px-7 type-title-medium",
};

export function Button({ variant = "filled", size = "md", icon, className, children, ref, ...rest }: ButtonProps) {
  const iconPad = icon ? (size === "lg" ? "pl-6" : size === "md" ? "pl-4" : "pl-2.5") : "";
  return (
    <button
      ref={ref}
      className={cn(
        "relative inline-flex shrink-0 select-none items-center justify-center whitespace-nowrap rounded-full outline-none transition-[box-shadow,background-color,color,opacity] duration-200 disabled:pointer-events-none disabled:opacity-[0.38]",
        VARIANTS[variant],
        SIZES[size],
        variant === "text" || variant === "dangerText" ? (size === "lg" ? "px-5" : "px-3") : iconPad,
        className,
      )}
      {...rest}
    >
      <Ripple />
      {icon && <span className={cn("flex", size === "lg" ? "text-[22px]" : "text-[18px]")}>{icon}</span>}
      {children}
    </button>
  );
}

type IconVariant = "standard" | "filled" | "tonal" | "outlined";

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: IconVariant;
  size?: "sm" | "md" | "lg";
  ref?: Ref<HTMLButtonElement>;
  selected?: boolean;
}

const ICON_VARIANTS: Record<IconVariant, string> = {
  standard: "text-on-surface-variant",
  filled: "bg-primary text-on-primary",
  tonal: "bg-secondary-container text-on-secondary-container",
  outlined: "border border-outline-variant text-on-surface-variant",
};

export function IconButton({
  variant = "standard",
  size = "md",
  className,
  children,
  ref,
  selected,
  ...rest
}: IconButtonProps) {
  return (
    <button
      ref={ref}
      className={cn(
        "relative inline-grid shrink-0 place-items-center rounded-full outline-none transition-colors duration-200 disabled:pointer-events-none disabled:opacity-[0.38]",
        size === "sm" ? "size-8 text-[20px]" : size === "lg" ? "size-14 text-[26px]" : "size-10 text-[22px]",
        ICON_VARIANTS[variant],
        selected && variant === "standard" && "text-primary",
        className,
      )}
      {...rest}
    >
      <Ripple />
      {children}
    </button>
  );
}
