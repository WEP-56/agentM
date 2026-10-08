import { create } from "zustand";
import { persist } from "zustand/middleware";
import {
  AGENT_IDS,
  APP_NAME,
  APP_VERSION,
  agentById,
  RUNTIMES,
  type AgentId,
  type RuntimeId,
  type ViewKind,
} from "@/data/agents";
import type { ThemeMode } from "@/theme/theme";
import { fmtMB, sleep, uid } from "@/utils/format";
import { isNative, type NativeSnapshot } from "@/platform/native";
import { nativeActions } from "@/platform/nativeActions";

export type Tab = "home" | "config" | "env" | "settings";
export type Route =
  | { name: "session"; agentId: AgentId; view: ViewKind }
  | { name: "agentConfig"; agentId: AgentId }
  | { name: "logs" };

export type AgentStatus =
  | "not_installed"
  | "installing"
  | "installed"
  | "starting"
  | "running"
  | "stopping"
  | "uninstalling";
export interface AgentRt {
  status: AgentStatus;
  progress: number;
  startedAt?: number;
}
export interface Provider {
  id: string;
  presetId: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
}
export interface AgentConfig {
  activeId: string;
  providers: Provider[];
}
export type RuntimeStatus = "stopped" | "starting" | "running" | "stopping";
export type LogLevel = "D" | "I" | "W" | "E";
export interface LogEntry {
  id: number;
  t: number;
  level: LogLevel;
  tag: string;
  msg: string;
}
export interface Snack {
  id: number;
  text: string;
  action?: { label: string; run: () => void };
}
export type Perm = "notifications" | "storage" | "battery";
export type TaskStatus = "ok" | "checking" | "reinstalling";

/* ───── Sessions (terminal lines & web chat), kept in memory only ───── */
export type LineTone = "fg" | "dim" | "faint" | "accent" | "ok" | "err" | "warn" | "add" | "del";
export interface TermLine {
  id: number;
  kind: "banner" | "user" | "line" | "spinner";
  text?: string;
  tone?: LineTone;
  bullet?: string;
  bulletTone?: LineTone;
  indent?: number;
  bold?: boolean;
}
export interface ToolCall {
  name: string;
  arg: string;
  result: string;
  diff?: { add: string[]; del: string[] };
  out?: string[];
}
export interface ChatMsg {
  id: number;
  role: "user" | "assistant" | "tool";
  text: string;
  tool?: ToolCall;
  streaming?: boolean;
}
export interface Session {
  lines: TermLine[];
  msgs: ChatMsg[];
  busy: boolean;
  runId: number;
  history: string[];
}

export const SYSTEM_CHECKS = ["根文件系统", "软件源", "DNS 解析", "存储挂载"];
const SYSTEM_CHECKS_EN = ["rootfs integrity", "apt sources", "dns resolve", "storage mounts"];

let seq = 1;
export const nextId = () => seq++;
const now = () => Date.now();
const mkLog = (tag: string, msg: string, level: LogLevel = "I", t = now()): LogEntry => ({
  id: nextId(),
  t,
  level,
  tag,
  msg,
});

export function defaultConfig(id: AgentId): AgentConfig {
  const def = agentById(id);
  const providers = def.presets.slice(0, 2).map((p) => ({
    id: uid(),
    presetId: p.id,
    name: p.name,
    baseUrl: p.baseUrl,
    apiKey: "",
    model: p.models[0] ?? "",
  }));
  return { activeId: providers[0].id, providers };
}

export const newSession = (): Session => ({
  lines: [{ id: nextId(), kind: "banner" }],
  msgs: [],
  busy: false,
  runId: 0,
  history: [],
});

const initialAgents = () =>
  Object.fromEntries(AGENT_IDS.map((id) => [id, { status: "not_installed", progress: 0 }])) as Record<
    AgentId,
    AgentRt
  >;
const initialConfigs = () =>
  Object.fromEntries(AGENT_IDS.map((id) => [id, defaultConfig(id)])) as Record<AgentId, AgentConfig>;
const initialRuntimes = () =>
  Object.fromEntries(RUNTIMES.map((r) => [r.id, { status: "ok", progress: 0 }])) as Record<
    RuntimeId,
    { status: TaskStatus; progress: number }
  >;

async function waitFor(pred: () => boolean, timeout = 10000) {
  const t0 = now();
  while (!pred() && now() - t0 < timeout) await sleep(80);
}

function progressLoop(total: number, onTick: (p: number) => void) {
  return new Promise<void>((resolve) => {
    const t0 = now();
    const iv = setInterval(() => {
      const raw = Math.min(1, (now() - t0) / total);
      onTick(1 - Math.pow(1 - raw, 1.5));
      if (raw >= 1) {
        clearInterval(iv);
        resolve();
      }
    }, 90);
  });
}

interface Data {
  native: NativeSnapshot | null;
  onboarded: boolean;
  agents: Record<AgentId, AgentRt>;
  configs: Record<AgentId, AgentConfig>;
  settings: { themeMode: ThemeMode; seed: string; autoStartRuntime: boolean };
  permissions: Record<Perm, boolean>;
  mirror: string;
  cacheMB: number;

  booting: boolean;
  tab: Tab;
  stack: Route[];
  runtime: { status: RuntimeStatus; startedAt?: number };
  system: { status: TaskStatus; progress: number; checkStep: number; checkedAt?: number };
  runtimes: Record<RuntimeId, { status: TaskStatus; progress: number }>;
  logs: LogEntry[];
  snack: Snack | null;
  sessions: Partial<Record<AgentId, Session>>;
}

interface Actions {
  finishBoot(): void;
  completeOnboarding(selected: AgentId[], mirror: string, logs: { tag: string; msg: string; level?: LogLevel }[]): void;
  resetPreview(): void;
  setTab(t: Tab): void;
  push(r: Route): void;
  pop(): void;
  setSessionView(id: AgentId, view: ViewKind): void;
  log(tag: string, msg: string, level?: LogLevel): void;
  clearLogs(): void;
  showSnack(text: string, action?: Snack["action"]): void;
  dismissSnack(id?: number): void;
  startRuntime(): Promise<void>;
  stopRuntime(): Promise<void>;
  restartRuntime(): Promise<void>;
  ensureRuntime(): Promise<void>;
  installAgent(id: AgentId): Promise<void>;
  uninstallAgent(id: AgentId): Promise<void>;
  launchAgent(id: AgentId, open?: boolean): Promise<void>;
  stopAgent(id: AgentId): Promise<void>;
  openAgent(id: AgentId): void;
  setActiveProvider(id: AgentId, providerId: string): void;
  upsertProvider(id: AgentId, p: Provider): void;
  deleteProvider(id: AgentId, providerId: string): void;
  setModel(id: AgentId, providerId: string, model: string): void;
  checkSystem(): Promise<void>;
  reinstallSystem(): Promise<void>;
  checkRuntimes(): Promise<void>;
  reinstallRuntime(rid: RuntimeId): Promise<void>;
  clearCache(): void;
  setThemeMode(m: ThemeMode): void;
  setSeed(hex: string): void;
  setAutoStart(v: boolean): void;
  setPermission(p: Perm, v: boolean): void;
  ensureSession(id: AgentId): void;
  patchSession(id: AgentId, fn: (s: Session) => Session): void;
}

export type AppState = Data & Actions;

const baseData = (): Data => ({
  native: null,
  onboarded: false,
  agents: initialAgents(),
  configs: initialConfigs(),
  settings: { themeMode: "system", seed: "#6750A4", autoStartRuntime: true },
  permissions: { notifications: false, storage: false, battery: false },
  mirror: "auto",
  cacheMB: isNative ? 0 : 236,

  booting: true,
  tab: "home",
  stack: [],
  runtime: { status: "stopped" },
  system: { status: "ok", progress: 0, checkStep: -1 },
  runtimes: initialRuntimes(),
  logs: isNative ? [] : [mkLog("app", `${APP_NAME} ${APP_VERSION} · 浏览器预览数据`, "I", now() - 3000)],
  snack: null,
  sessions: {},
});

export const useApp = create<AppState>()(
  persist(
    (set, get) => {
      const setAgent = (id: AgentId, patch: Partial<AgentRt>) =>
        set((s) => ({ agents: { ...s.agents, [id]: { ...s.agents[id], ...patch } } }));
      const setRt = (id: RuntimeId, patch: Partial<{ status: TaskStatus; progress: number }>) =>
        set((s) => ({ runtimes: { ...s.runtimes, [id]: { ...s.runtimes[id], ...patch } } }));
      const activeProvider = (id: AgentId) => {
        const c = get().configs[id];
        return c.providers.find((p) => p.id === c.activeId) ?? c.providers[0];
      };

      return {
        ...baseData(),

        finishBoot: () => {
          set({ booting: false });
          const s = get();
          if (s.onboarded && s.settings.autoStartRuntime && s.runtime.status === "stopped") void s.startRuntime();
        },

        completeOnboarding: (selected, mirror, logs) => {
          const agents = initialAgents();
          selected.forEach((id) => (agents[id] = { status: "installed", progress: 0 }));
          set((s) => ({
            onboarded: true,
            agents,
            mirror,
            tab: "home",
            stack: [],
            runtime: { status: "running", startedAt: now() },
            cacheMB: 236 + selected.length * 24,
            logs: [...s.logs, ...logs.map((l) => mkLog(l.tag, l.msg, l.level ?? "I"))].slice(-500),
          }));
        },

        resetPreview: () => {
          const keep = get().settings;
          set({ ...baseData(), settings: keep });
        },

        setTab: (tab) => set({ tab }),
        push: (r) => set((s) => ({ stack: [...s.stack, r] })),
        pop: () => set((s) => ({ stack: s.stack.slice(0, -1) })),
        setSessionView: (id, view) =>
          set((s) => ({
            stack: s.stack.map((r) => (r.name === "session" && r.agentId === id ? { ...r, view } : r)),
          })),

        log: (tag, msg, level = "I") => set((s) => ({ logs: [...s.logs, mkLog(tag, msg, level)].slice(-500) })),
        clearLogs: () => set({ logs: [] }),
        showSnack: (text, action) => set({ snack: { id: now() + Math.random(), text, action } }),
        dismissSnack: (id) => set((s) => (id === undefined || s.snack?.id === id ? { snack: null } : {})),

        /* ───── Runtime ───── */
        startRuntime: async () => {
          const st = get().runtime.status;
          if (st === "running") return;
          if (st === "starting") return waitFor(() => get().runtime.status === "running");
          if (st === "stopping") await waitFor(() => get().runtime.status === "stopped");
          set({ runtime: { status: "starting" } });
          get().log("runtime", "starting proot · rootfs=/data/data/dev.agentm.app/files/debian");
          await sleep(520);
          get().log("debian", "mount /proc /sys /dev · bind /sdcard → /mnt/sdcard");
          await sleep(620);
          get().log("runtime", `Ubuntu 24.04 (noble) aarch64 ready · pid ${1000 + Math.floor(Math.random() * 8999)}`);
          set({ runtime: { status: "running", startedAt: now() } });
        },

        stopRuntime: async () => {
          if (get().runtime.status !== "running") return;
          const running = AGENT_IDS.filter((id) => ["running", "starting"].includes(get().agents[id].status));
          set({ runtime: { status: "stopping" } });
          running.forEach((id) => setAgent(id, { status: "stopping" }));
          await sleep(700);
          running.forEach((id) => {
            setAgent(id, { status: "installed", startedAt: undefined });
            get().log(agentById(id).cmd, "process exited (SIGTERM)");
          });
          set((s) => {
            const sessions = { ...s.sessions };
            running.forEach((id) => delete sessions[id]);
            return {
              sessions,
              stack: s.stack.filter((r) => r.name !== "session"),
              runtime: { status: "stopped" },
            };
          });
          get().log("runtime", "proot exited (0)");
        },

        restartRuntime: async () => {
          await get().stopRuntime();
          await sleep(250);
          await get().startRuntime();
          get().showSnack("运行时已重启");
        },

        ensureRuntime: async () => {
          const st = get().runtime.status;
          if (st === "running") return;
          if (st === "stopping") await waitFor(() => get().runtime.status === "stopped");
          await get().startRuntime();
        },

        /* ───── Agents ───── */
        installAgent: async (id) => {
          const def = agentById(id);
          if (get().agents[id].status !== "not_installed") return;
          setAgent(id, { status: "installing", progress: 0 });
          await get().ensureRuntime();
          get().log(def.cmd, `npm install -g ${def.pkg}`);
          await progressLoop(2600 + def.sizeMB * 22, (p) => setAgent(id, { progress: p }));
          get().log(def.cmd, `added ${18 + Math.floor(def.sizeMB / 3)} packages · ${def.name} ${def.version}`);
          setAgent(id, { status: "installed", progress: 0 });
          set((s) => ({ cacheMB: s.cacheMB + Math.round(def.sizeMB * 0.35) }));
          get().showSnack(`${def.name} 已安装`, { label: "启动", run: () => void get().launchAgent(id) });
        },

        uninstallAgent: async (id) => {
          const def = agentById(id);
          const st = get().agents[id].status;
          if (st === "running" || st === "starting") await get().stopAgent(id);
          set((s) => ({ stack: s.stack.filter((r) => !("agentId" in r && r.agentId === id)) }));
          setAgent(id, { status: "uninstalling" });
          get().log(def.cmd, `npm uninstall -g ${def.pkg}`);
          await sleep(1200);
          setAgent(id, { status: "not_installed", progress: 0 });
          get().showSnack(`已卸载 ${def.name}`);
        },

        launchAgent: async (id, open = false) => {
          const def = agentById(id);
          const st = get().agents[id].status;
          if (st === "running") {
            if (open) get().openAgent(id);
            return;
          }
          if (st !== "installed") return;
          setAgent(id, { status: "starting" });
          await get().ensureRuntime();
          get().log(
            def.cmd,
            def.view === "webui"
              ? `${def.cmd} web --port ${def.port} · cwd=/root/projects/demo`
              : `${def.cmd} · pty /dev/pts/${1 + Math.floor(Math.random() * 6)} · cwd=/root/projects/demo`,
          );
          await sleep(650);
          if (get().agents[id].status !== "starting") return;
          const prov = activeProvider(id);
          setAgent(id, { status: "running", startedAt: now() });
          get().ensureSession(id);
          get().log(def.cmd, `session ready · provider=${prov.name} model=${prov.model || "default"}`);
          if (!prov.apiKey && prov.presetId !== "ollama")
            get().log(def.cmd, `${prov.name} API key not set, requests will fail`, "W");
          if (def.view === "webui") get().log(def.cmd, `listening on http://127.0.0.1:${def.port}`);
          if (open) get().openAgent(id);
        },

        stopAgent: async (id) => {
          const def = agentById(id);
          if (!["running", "starting"].includes(get().agents[id].status)) return;
          setAgent(id, { status: "stopping" });
          if (get().sessions[id]) get().patchSession(id, (s) => ({ ...s, busy: false, runId: s.runId + 1 }));
          await sleep(480);
          setAgent(id, { status: "installed", startedAt: undefined });
          set((s) => {
            const sessions = { ...s.sessions };
            delete sessions[id];
            return { sessions, stack: s.stack.filter((r) => !(r.name === "session" && r.agentId === id)) };
          });
          get().log(def.cmd, "process exited (SIGTERM)");
        },

        openAgent: (id) => {
          const def = agentById(id);
          get().ensureSession(id);
          set((s) => ({
            stack: [...s.stack.filter((r) => r.name !== "session"), { name: "session", agentId: id, view: def.view }],
          }));
        },

        /* ───── Providers ───── */
        setActiveProvider: (id, pid) =>
          set((s) => ({ configs: { ...s.configs, [id]: { ...s.configs[id], activeId: pid } } })),
        upsertProvider: (id, p) =>
          set((s) => {
            const c = s.configs[id];
            const exists = c.providers.some((x) => x.id === p.id);
            const providers = exists ? c.providers.map((x) => (x.id === p.id ? p : x)) : [...c.providers, p];
            return { configs: { ...s.configs, [id]: { activeId: exists ? c.activeId : p.id, providers } } };
          }),
        deleteProvider: (id, pid) =>
          set((s) => {
            const c = s.configs[id];
            if (c.providers.length <= 1) return {};
            const providers = c.providers.filter((x) => x.id !== pid);
            return {
              configs: {
                ...s.configs,
                [id]: { activeId: c.activeId === pid ? providers[0].id : c.activeId, providers },
              },
            };
          }),
        setModel: (id, pid, model) =>
          set((s) => {
            const c = s.configs[id];
            return {
              configs: {
                ...s.configs,
                [id]: { ...c, providers: c.providers.map((p) => (p.id === pid ? { ...p, model } : p)) },
              },
            };
          }),

        /* ───── Environment ───── */
        checkSystem: async () => {
          if (get().system.status !== "ok") return;
          set((s) => ({ system: { ...s.system, status: "checking", checkStep: 0 } }));
          for (let i = 0; i < SYSTEM_CHECKS.length; i++) {
            set((s) => ({ system: { ...s.system, checkStep: i } }));
            await sleep(560);
            get().log("debian", `check ${SYSTEM_CHECKS_EN[i]} … ok`);
          }
          set((s) => ({ system: { ...s.system, status: "ok", checkStep: -1, checkedAt: now() } }));
          get().showSnack("系统检查完成，未发现问题");
        },

        reinstallSystem: async () => {
          if (get().system.status !== "ok") return;
          await get().stopRuntime();
          set((s) => ({ system: { ...s.system, status: "reinstalling", progress: 0 } }));
          get().log("debian", "reinstall: fetch debian-noble-arm64.tar.xz");
          let stage = 0;
          const stages = ["verify sha256 … ok", "extract rootfs", "configure apt / locale / users", "restore /root"];
          await progressLoop(5600, (p) => {
            set((s) => ({ system: { ...s.system, progress: p } }));
            const next = Math.floor(p * (stages.length + 1)) - 1;
            while (stage <= next && stage < stages.length) get().log("debian", `reinstall: ${stages[stage++]}`);
          });
          set((s) => ({ system: { ...s.system, status: "ok", progress: 0, checkedAt: now() } }));
          await get().startRuntime();
          get().showSnack("系统已重装，用户数据已保留");
        },

        checkRuntimes: async () => {
          const ids = RUNTIMES.map((r) => r.id).filter((id) => get().runtimes[id].status === "ok");
          ids.forEach((id) => setRt(id, { status: "checking" }));
          for (const id of ids) {
            await sleep(420);
            const r = RUNTIMES.find((x) => x.id === id)!;
            setRt(id, { status: "ok" });
            get().log("runtime", `verify ${r.name} ${r.version} … ok`);
          }
          get().showSnack("运行时检查完成");
        },

        reinstallRuntime: async (rid) => {
          const r = RUNTIMES.find((x) => x.id === rid)!;
          if (get().runtimes[rid].status !== "ok") return;
          setRt(rid, { status: "reinstalling", progress: 0 });
          get().log("runtime", `reinstall ${r.name} (${r.version})`);
          await progressLoop(2400, (p) => setRt(rid, { progress: p }));
          setRt(rid, { status: "ok", progress: 0 });
          get().log("runtime", `${r.name} ${r.version} installed`);
          get().showSnack(`${r.name} 已重装`);
        },

        clearCache: () => {
          const freed = get().cacheMB;
          set({ cacheMB: 0 });
          get().log("runtime", `apt clean · npm cache clean · freed ${fmtMB(freed)}`);
          get().showSnack(freed > 0 ? `已释放 ${fmtMB(freed)}` : "缓存已是空的");
        },

        /* ───── Settings ───── */
        setThemeMode: (themeMode) => set((s) => ({ settings: { ...s.settings, themeMode } })),
        setSeed: (seed) => set((s) => ({ settings: { ...s.settings, seed } })),
        setAutoStart: (autoStartRuntime) => set((s) => ({ settings: { ...s.settings, autoStartRuntime } })),
        setPermission: (p, v) => {
          set((s) => ({ permissions: { ...s.permissions, [p]: v } }));
          get().log("app", `permission ${p} ${v ? "granted" : "denied"}`, v ? "I" : "W");
        },

        /* ───── Sessions ───── */
        ensureSession: (id) => {
          if (get().sessions[id]) return;
          set((s) => ({ sessions: { ...s.sessions, [id]: newSession() } }));
        },
        patchSession: (id, fn) =>
          set((s) => ({ sessions: { ...s.sessions, [id]: fn(s.sessions[id] ?? newSession()) } })),
        ...(isNative ? nativeActions(set, get) : {}),
      };
    },
    {
      name: isNative ? "agentm-native-ui-v1" : "agentm-preview-v1",
      partialize: (s) => isNative ? { settings: s.settings, onboarded: s.onboarded } : ({
        onboarded: s.onboarded,
        agents: Object.fromEntries(
          AGENT_IDS.map((id) => {
            const st = s.agents[id].status;
            const installed = st !== "not_installed" && st !== "installing";
            return [id, { status: installed ? "installed" : "not_installed", progress: 0 }];
          }),
        ) as Record<AgentId, AgentRt>,
        configs: Object.fromEntries(AGENT_IDS.map((id) => [id, { ...s.configs[id], providers: s.configs[id].providers.map((p) => ({ ...p, apiKey: "" })) }])),
        settings: s.settings,
        permissions: s.permissions,
        mirror: s.mirror,
        cacheMB: s.cacheMB,
      }),
    },
  ),
);

export const activeProviderOf = (s: AppState, id: AgentId) => {
  const c = s.configs[id];
  return c.providers.find((p) => p.id === c.activeId) ?? c.providers[0];
};
