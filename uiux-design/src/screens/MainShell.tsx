import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  MdDeveloperBoard,
  MdHome,
  MdOutlineDeveloperBoard,
  MdOutlineHome,
  MdOutlineSettings,
  MdOutlineTune,
  MdSettings,
  MdTune,
} from "react-icons/md";
import { useApp, type Route, type Tab } from "@/store/useApp";
import { NavigationBar, type NavItem } from "@/components/md/Layout";
import { OverlayCtx, SnackbarHost } from "@/components/md/Overlay";
import { AppLogo } from "@/components/Brand";
import { HomeScreen } from "./Home";
import { AgentConfigScreen, ConfigScreen } from "./Config";
import { EnvScreen, LogsScreen } from "./Environment";
import { SettingsScreen } from "./Settings";
import { SessionScreen } from "./Session";
import { Onboarding } from "./Onboarding";
import { isNative, nativeRequest, type NativeSnapshot } from "@/platform/native";
import { NativeEnvironment } from "./NativeEnvironment";
import { NativeOnboarding } from "./NativeOnboarding";

const NAV: NavItem<Tab>[] = [
  { key: "home", label: "首页", icon: <MdOutlineHome />, activeIcon: <MdHome /> },
  { key: "config", label: "配置", icon: <MdOutlineTune />, activeIcon: <MdTune /> },
  { key: "env", label: "环境", icon: <MdOutlineDeveloperBoard />, activeIcon: <MdDeveloperBoard /> },
  { key: "settings", label: "设置", icon: <MdOutlineSettings />, activeIcon: <MdSettings /> },
];

/** Pushed screens (session / agent config / logs) slide over the tabs like Android activities. */
const routeKey = (r: Route) => r.name + ("agentId" in r ? `:${r.agentId}` : "");

export function MainShell() {
  const tab = useApp((s) => s.tab);
  const setTab = useApp((s) => s.setTab);
  const stack = useApp((s) => s.stack);
  const pop = useApp((s) => s.pop);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && useApp.getState().stack.length) pop();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [pop]);

  useEffect(() => {
    if (!isNative) return;
    window.agentMBack = () => {
      const state = useApp.getState();
      if (state.stack.length) { state.pop(); return true; }
      if (state.tab !== 'home') { state.setTab('home'); return true; }
      return false;
    };
    return () => { delete window.agentMBack; };
  }, []);

  return (
    <MainShellContent tab={tab} setTab={setTab} stack={stack} />
  );
}

function useNativePolling() {
  useEffect(() => {
    if (!isNative) return;
    const refresh = () => { void useApp.getState().checkSystem(); };
    let disposed = false;
    let fetching = false;
    const interval = window.setInterval(async () => {
      if (fetching || document.visibilityState !== 'visible') return;
      fetching = true;
      try {
        const snapshot = await nativeRequest<NativeSnapshot>('inspect');
        if (!disposed) useApp.setState({ native: snapshot, logs: snapshot.logs, permissions: snapshot.permissions });
      } catch { /* Explicit refresh surfaces connection errors; polling does not spam notifications. */ }
      finally { fetching = false; }
    }, 1500);
    window.addEventListener('agentm:resume', refresh);
    return () => { disposed = true; clearInterval(interval); window.removeEventListener('agentm:resume', refresh); };
  }, []);
}

function MainShellContent({ tab, setTab, stack }: { tab: Tab; setTab: (tab: Tab) => void; stack: Route[] }) {
  return (
    <motion.div
      className="absolute inset-0 flex flex-col bg-surface"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.3 }}
    >
      <div className="relative min-h-0 flex-1">
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={tab}
            className="absolute inset-0"
            initial={{ opacity: 0, scale: 0.985 }}
            animate={{ opacity: 1, scale: 1, transition: { duration: 0.26, ease: [0.2, 0, 0, 1] } }}
            exit={{ opacity: 0, transition: { duration: 0.08 } }}
          >
            {tab === "home" && <HomeScreen />}
            {tab === "config" && <ConfigScreen />}
            {tab === "env" && (isNative ? <NativeEnvironment /> : <EnvScreen />)}
            {tab === "settings" && <SettingsScreen />}
          </motion.div>
        </AnimatePresence>
      </div>
      <NavigationBar items={NAV} value={tab} onChange={setTab} />

      <AnimatePresence>
        {stack.map((r, i) => (
          <motion.div
            key={routeKey(r)}
            className="absolute inset-0 bg-surface"
            style={{ zIndex: 20 + i }}
            initial={{ opacity: 0, x: 64 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: 64, transition: { duration: 0.2, ease: [0.3, 0, 0.8, 0.15] } }}
            transition={{ duration: 0.38, ease: [0.05, 0.7, 0.1, 1] }}
          >
            {r.name === "session" ? (
              <SessionScreen agentId={r.agentId} view={r.view} />
            ) : r.name === "agentConfig" ? (
              <AgentConfigScreen agentId={r.agentId} />
            ) : (
              <LogsScreen />
            )}
          </motion.div>
        ))}
      </AnimatePresence>
    </motion.div>
  );
}

function Splash() {
  return (
    <motion.div
      className="absolute inset-0 grid place-items-center bg-surface"
      exit={{ opacity: 0, transition: { duration: 0.25 } }}
    >
      <motion.div
        initial={{ scale: 0.4, opacity: 0, rotate: -40 }}
        animate={{ scale: 1, opacity: 1, rotate: 0 }}
        transition={{ duration: 0.8, ease: [0.05, 0.7, 0.1, 1] }}
      >
        <AppLogo size={104} />
      </motion.div>
    </motion.div>
  );
}

/** The whole Android app, rendered inside the phone frame (or full-screen on mobile). */
export function AppRoot() {
  useNativePolling();
  const [overlay, setOverlay] = useState<HTMLDivElement | null>(null);
  const booting = useApp((s) => s.booting);
  const onboarded = useApp((s) => s.onboarded);
  const hasStack = useApp((s) => s.stack.length > 0);

  useEffect(() => {
    if (!booting) return;
    const t = setTimeout(() => useApp.getState().finishBoot(), 1100);
    return () => clearTimeout(t);
  }, [booting]);

  const snackBottom =
    !booting && onboarded && !hasStack ? "calc(80px + var(--sab) + 12px)" : "calc(var(--sab) + 60px)";

  return (
    <OverlayCtx.Provider value={overlay}>
      <div className="relative h-full w-full overflow-hidden bg-surface text-on-surface">
        <AnimatePresence mode="wait">
          {booting ? <Splash key="splash" /> : onboarded ? <MainShell key="main" /> : isNative ? <NativeOnboarding key="native-onboarding" /> : <Onboarding key="onboarding" />}
        </AnimatePresence>
        <SnackbarHost bottom={snackBottom} />
        <div ref={setOverlay} className="pointer-events-none absolute inset-0 z-50" />
      </div>
    </OverlayCtx.Provider>
  );
}
