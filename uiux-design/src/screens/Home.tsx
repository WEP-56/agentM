import { useRef, useState, type ReactNode } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { MdOutlineFileDownload, MdPlayArrow, MdPowerSettingsNew, MdRestartAlt, MdStop } from "react-icons/md";
import { AGENTS, APP_NAME, type AgentDef } from "@/data/agents";
import { useApp } from "@/store/useApp";
import { Button, IconButton } from "@/components/md/Button";
import { StatusDot } from "@/components/md/Controls";
import { TabPage } from "@/components/md/Layout";
import { Menu } from "@/components/md/Overlay";
import { CircularProgress } from "@/components/md/Progress";
import { Ripple } from "@/components/md/Ripple";
import { AgentIcon } from "@/components/Brand";
import { cn } from "@/utils/cn";
import { fmtUptime } from "@/utils/format";
import { useNow } from "@/utils/hooks";
import { isNative } from "@/platform/native";
import { openDeviceTerminal, openLinuxTerminal } from "./NativeEnvironment";
import { NativeAgentCard } from './NativePackages';
import { WorkingDirectoryPicker } from './WorkingDirectoryPicker';

export function HomeScreen() {
  const now = useNow(1000);
  const native = useApp((s) => s.native);
  const setTab = useApp((s) => s.setTab);
  return (
    <TabPage title={APP_NAME} actions={<RuntimeChip />}>
      {isNative && <div className="mb-4 rounded-[24px] bg-primary-container p-5 text-on-primary-container">
        <h2 className="type-title-medium">{native?.environment.linuxReady ? 'Ubuntu 已就绪' : '准备你的 Linux 环境'}</h2>
        <p className="mt-2 type-body-medium">{native?.environment.reason ?? '正在检查设备…'}</p>
        <WorkingDirectoryPicker />
        <div className="mt-4 flex flex-wrap gap-2">
          <Button variant="tonal" onClick={() => native?.environment.linuxReady ? void openLinuxTerminal() : setTab('env')}>{native?.environment.linuxReady ? '打开 Linux 终端' : '准备环境'}</Button>
          <Button variant="text" onClick={() => void openDeviceTerminal()}>设备终端</Button>
        </div>
      </div>}
      <div className="flex flex-col gap-3">
        {AGENTS.map((a, i) => (
          <motion.div
            key={a.id}
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.04 * i, duration: 0.4, ease: [0.05, 0.7, 0.1, 1] }}
          >
            {isNative ? <NativeAgentCard def={a} /> : <AgentCard def={a} now={now} />}
          </motion.div>
        ))}
      </div>
    </TabPage>
  );
}

function RuntimeChip() {
  const rt = useApp((s) => s.runtime.status);
  const start = useApp((s) => s.startRuntime);
  const stop = useApp((s) => s.stopRuntime);
  const restart = useApp((s) => s.restartRuntime);
  const ref = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const native = useApp((s) => s.native);
  const setTab = useApp((s) => s.setTab);
  if (isNative) return <button className="h-9 rounded-full bg-surface-container-high px-3 type-label-large text-on-surface-variant" onClick={() => setTab('env')}>
    Linux {native?.environment.linuxReady ? '就绪' : native?.environment.busy ? '安装中' : '未就绪'}
  </button>;
  const label = { running: "运行中", starting: "启动中", stopping: "停止中", stopped: "已停止" }[rt];

  return (
    <>
      <button
        ref={ref}
        onClick={() => setOpen(true)}
        className="relative flex h-8 items-center gap-2 rounded-full bg-surface-container-high pl-3 pr-3.5 type-label-large text-on-surface-variant outline-none"
      >
        <Ripple />
        {rt === "running" ? (
          <StatusDot tone="ok" pulse />
        ) : rt === "stopped" ? (
          <StatusDot tone="idle" />
        ) : (
          <CircularProgress size={12} stroke={2} />
        )}
        <span>Linux {label}</span>
      </button>
      <Menu
        open={open}
        onClose={() => setOpen(false)}
        anchor={ref}
        items={
          rt === "running"
            ? [
                { label: "重启运行时", icon: <MdRestartAlt />, onClick: () => void restart() },
                { label: "停止运行时", icon: <MdPowerSettingsNew />, onClick: () => void stop(), danger: true },
              ]
            : [{ label: "启动运行时", icon: <MdPlayArrow />, onClick: () => void start(), disabled: rt !== "stopped" }]
        }
      />
    </>
  );
}

/** One agent row: install → start (runs in background) → tap to open terminal / WebUI. */
function AgentCard({ def, now }: { def: AgentDef; now: number }) {
  const st = useApp((s) => s.agents[def.id]);
  const install = useApp((s) => s.installAgent);
  const launch = useApp((s) => s.launchAgent);
  const stop = useApp((s) => s.stopAgent);
  const open = useApp((s) => s.openAgent);
  const s = st.status;
  const running = s === "running";
  const clickable = running || s === "installed";

  const onCard = () => {
    if (running) open(def.id);
    else if (s === "installed") void launch(def.id, true);
  };

  let sub: ReactNode = def.tagline;
  if (s === "installing") sub = `正在安装 · ${Math.round(st.progress * 100)}%`;
  else if (s === "uninstalling") sub = "正在卸载…";
  else if (s === "starting") sub = "正在启动…";
  else if (s === "stopping") sub = "正在停止…";
  else if (running)
    sub = (
      <span className="flex items-center gap-2">
        <StatusDot tone="ok" pulse />
        <span>
          运行中 · <span className="tabular-nums">{fmtUptime(now - (st.startedAt ?? now))}</span>
        </span>
      </span>
    );

  const busy = s === "starting" || s === "stopping" || s === "uninstalling";
  const actionKey = busy ? "busy" : s;

  let action: ReactNode = null;
  if (s === "not_installed")
    action = (
      <Button variant="outlined" icon={<MdOutlineFileDownload />} onClick={() => void install(def.id)}>
        安装
      </Button>
    );
  else if (s === "installing")
    action = (
      <span className="grid size-10 place-items-center">
        <CircularProgress value={st.progress} size={28} stroke={3} />
      </span>
    );
  else if (busy)
    action = (
      <span className="grid size-10 place-items-center">
        <CircularProgress size={24} stroke={3} />
      </span>
    );
  else if (s === "installed")
    action = (
      <Button variant="tonal" icon={<MdPlayArrow />} onClick={() => void launch(def.id)}>
        启动
      </Button>
    );
  else if (running)
    action = (
      <>
        <IconButton aria-label="停止" onClick={() => void stop(def.id)}>
          <MdStop />
        </IconButton>
        <Button onClick={() => open(def.id)} className="px-5">
          打开
        </Button>
      </>
    );

  return (
    <div
      role={clickable ? "button" : undefined}
      tabIndex={clickable ? 0 : undefined}
      onClick={clickable ? onCard : undefined}
      className={cn(
        "relative flex items-center gap-4 rounded-[28px] py-4 pl-4 pr-3 outline-none transition-colors duration-300",
        running ? "bg-secondary-container/70" : "bg-surface-container-low",
        clickable && "cursor-pointer",
      )}
    >
      {clickable && <Ripple />}
      <AgentIcon agent={def} size={48} />
      <div className="min-w-0 flex-1">
        <div className="truncate type-title-medium text-on-surface">{def.name}</div>
        <div className="mt-0.5 truncate type-body-medium text-on-surface-variant">{sub}</div>
      </div>
      <div className="flex shrink-0 items-center" onClick={(e) => e.stopPropagation()}>
        <AnimatePresence mode="popLayout" initial={false}>
          <motion.div
            key={actionKey}
            initial={{ opacity: 0, scale: 0.85 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.85 }}
            transition={{ duration: 0.18, ease: [0.2, 0, 0, 1] }}
            className="flex items-center gap-1"
          >
            {action}
          </motion.div>
        </AnimatePresence>
      </div>
    </div>
  );
}
