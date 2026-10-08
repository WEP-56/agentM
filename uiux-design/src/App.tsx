import { useLayoutEffect, type CSSProperties } from "react";
import { MotionConfig } from "framer-motion";
import {
  MdBatteryFull,
  MdOutlineDarkMode,
  MdOutlineHistory,
  MdOutlineLightMode,
  MdSignalCellularAlt,
  MdWifi,
} from "react-icons/md";
import { APP_NAME } from "@/data/agents";
import { useApp } from "@/store/useApp";
import { applyTheme, getSystemDark, resolveDark, useSystemDark } from "@/theme/theme";
import { AppRoot } from "@/screens/MainShell";
import { AppLogo } from "@/components/Brand";
import { Button, IconButton } from "@/components/md/Button";
import { cn } from "@/utils/cn";
import { useMediaQuery, useNow, useWindowSize } from "@/utils/hooks";
import { isNative } from "@/platform/native";

// Apply the persisted theme before the first paint.
{
  const s = useApp.getState().settings;
  applyTheme(s.seed, resolveDark(s.themeMode, getSystemDark()));
}

export default function App() {
  const mode = useApp((s) => s.settings.themeMode);
  const seed = useApp((s) => s.settings.seed);
  const sysDark = useSystemDark();
  const dark = resolveDark(mode, sysDark);
  const desktopSize = useMediaQuery("(min-width: 720px) and (min-height: 620px)");
  const framed = !isNative && desktopSize;

  useLayoutEffect(() => applyTheme(seed, dark), [seed, dark]);

  return (
    <MotionConfig reducedMotion="user">
      {framed ? (
        <Stage dark={dark} />
      ) : (
        <div
          className="fixed inset-0"
          style={{ "--sat": "env(safe-area-inset-top, 0px)", "--sab": "env(safe-area-inset-bottom, 0px)" } as CSSProperties}
        >
          <AppRoot />
        </div>
      )}
    </MotionConfig>
  );
}

/* ───────────── Desktop preview stage ───────────── */
function Stage({ dark }: { dark: boolean }) {
  const { w, h } = useWindowSize();
  const screenH = Math.round(Math.max(600, Math.min(860, h - 56)));
  const screenW = Math.round(Math.min(400, Math.max(360, screenH * 0.462)));
  const showAside = w >= 1080;

  return (
    <div className="relative flex min-h-dvh items-center justify-center overflow-hidden bg-surface-container-low px-6 py-7">
      <div aria-hidden className="pointer-events-none absolute inset-0">
        <div className="absolute -left-[12%] -top-[25%] size-[62vmax] rounded-full bg-primary-container opacity-60 blur-[120px]" />
        <div className="absolute -bottom-[30%] -right-[14%] size-[56vmax] rounded-full bg-tertiary-container opacity-45 blur-[130px]" />
      </div>
      <div className="relative flex items-center gap-24">
        {showAside && <Aside dark={dark} />}
        <Phone width={screenW} height={screenH} dark={dark} />
      </div>
    </div>
  );
}

function Aside({ dark }: { dark: boolean }) {
  const reset = useApp((s) => s.resetPreview);
  const setThemeMode = useApp((s) => s.setThemeMode);
  return (
    <div className="w-[320px]">
      <AppLogo size={64} spin />
      <h1 className="mt-8 type-display-small text-on-surface">{APP_NAME}</h1>
      <p className="mt-2 type-body-large text-on-surface-variant">安卓端 AI Agent 开发工作台</p>
      <p className="mt-5 type-body-medium leading-relaxed text-on-surface-variant/85">
        内置 Linux 运行时与 Ubuntu 子系统。按需安装 Claude Code、Codex、OpenCode、Pi、DSH，在手机上进行真实开发。
      </p>
      <div className="mt-7 flex flex-wrap gap-2">
        {["Material 3", "React", "Ubuntu 24.04", "proot"].map((t) => (
          <span key={t} className="rounded-lg border border-outline-variant px-3 py-1.5 type-label-large text-on-surface-variant">
            {t}
          </span>
        ))}
      </div>
      <div className="mt-10 flex items-center gap-2">
        <Button variant="tonal" icon={<MdOutlineHistory />} onClick={reset}>
          重新引导
        </Button>
        <IconButton variant="outlined" aria-label="切换深浅色" onClick={() => setThemeMode(dark ? "light" : "dark")}>
          {dark ? <MdOutlineLightMode /> : <MdOutlineDarkMode />}
        </IconButton>
      </div>
      <p className="mt-10 type-label-small text-on-surface-variant/70">交互式预览 · 所有数据均为模拟</p>
    </div>
  );
}

function Phone({ width, height, dark }: { width: number; height: number; dark: boolean }) {
  const top = useApp((s) => s.stack[s.stack.length - 1]);
  const lightIcons = dark || (top?.name === "session" && top.view === "terminal");
  return (
    <div
      className="relative shrink-0 rounded-[58px] bg-[#1a1a1d] p-[11px] shadow-phone ring-1 ring-white/10"
      style={{ width: width + 22, height: height + 22 }}
    >
      <span className="absolute -right-[3px] top-[20%] h-14 w-[3px] rounded-r-sm bg-[#2b2b30]" />
      <span className="absolute -right-[3px] top-[31%] h-24 w-[3px] rounded-r-sm bg-[#2b2b30]" />
      <div
        className="relative h-full w-full overflow-hidden rounded-[47px] bg-surface"
        style={{ "--sat": "36px", "--sab": "18px", transform: "translateZ(0)" } as CSSProperties}
      >
        <AppRoot />
        <StatusBar light={lightIcons} />
        <div className="pointer-events-none absolute inset-x-0 bottom-0 z-[60] flex h-[18px] items-center justify-center">
          <span className={cn("h-1 w-28 rounded-full transition-colors duration-300", lightIcons ? "bg-white/80" : "bg-black/75")} />
        </div>
      </div>
    </div>
  );
}

function StatusBar({ light }: { light: boolean }) {
  const now = useNow(5000);
  const d = new Date(now);
  const time = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  return (
    <div
      className={cn(
        "pointer-events-none absolute inset-x-0 top-0 z-[60] flex h-9 items-center justify-between pl-7 pr-6 text-[13px] font-medium transition-colors duration-300",
        light ? "text-white" : "text-[#1d1b20]",
      )}
    >
      <span className="tabular-nums">{time}</span>
      <span className="absolute left-1/2 top-[9px] size-[18px] -translate-x-1/2 rounded-full bg-black ring-1 ring-white/5" />
      <span className="flex items-center gap-1 text-[15px]">
        <MdSignalCellularAlt />
        <MdWifi />
        <MdBatteryFull />
      </span>
    </div>
  );
}
