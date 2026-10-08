import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  MdCheckCircle,
  MdChevronRight,
  MdContentCopy,
  MdDeleteOutline,
  MdOutlineCleaningServices,
  MdOutlineDescription,
  MdOutlineHealthAndSafety,
  MdRestartAlt,
} from "react-icons/md";
import { SiDebian, SiGit, SiLinux, SiNodedotjs, SiPython } from "react-icons/si";
import { AGENTS, RUNTIMES, SYSTEM, type RuntimeId } from "@/data/agents";
import { SYSTEM_CHECKS, useApp } from "@/store/useApp";
import { Button, IconButton } from "@/components/md/Button";
import { Chip, StatusDot } from "@/components/md/Controls";
import { ListGroup, ListItem, TabPage, TopBar } from "@/components/md/Layout";
import { Dialog } from "@/components/md/Overlay";
import { CircularProgress, WavyProgress } from "@/components/md/Progress";
import { Shape } from "@/components/Brand";
import { cn } from "@/utils/cn";
import { fmtClock, fmtMB } from "@/utils/format";
import { useNow } from "@/utils/hooks";
import { isInstalled } from "./Config";

const RT_ICONS: Record<RuntimeId, ReactNode> = {
  proot: <SiLinux className="text-[21px]" />,
  node: <SiNodedotjs className="text-[21px]" />,
  python: <SiPython className="text-[21px]" />,
  git: <SiGit className="text-[21px]" />,
};

const relTime = (t: number, now: number) => {
  const s = Math.floor((now - t) / 1000);
  if (s < 60) return "刚刚";
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`;
  return `${Math.floor(s / 3600)} 小时前`;
};

export function EnvScreen() {
  const logsCount = useApp((s) => s.logs.length);
  const push = useApp((s) => s.push);
  return (
    <TabPage title="环境">
      <SystemCard />
      <RuntimeGroup />
      <StorageGroup />
      <ListGroup title="日志">
        <ListItem
          icon={<MdOutlineDescription />}
          headline="运行日志"
          supporting={`${logsCount} 条记录`}
          onClick={() => push({ name: "logs" })}
          trailing={<MdChevronRight className="text-[24px] text-on-surface-variant" />}
        />
      </ListGroup>
    </TabPage>
  );
}

/* ───────────── System ───────────── */
const REINSTALL_STAGES = ["下载根文件系统", "校验文件", "解压系统", "配置系统", "恢复用户数据"];

function SystemCard() {
  const sys = useApp((s) => s.system);
  const rt = useApp((s) => s.runtime.status);
  const check = useApp((s) => s.checkSystem);
  const reinstall = useApp((s) => s.reinstallSystem);
  const [confirm, setConfirm] = useState(false);
  const now = useNow(15000);
  const checking = sys.status === "checking";
  const reinstalling = sys.status === "reinstalling";
  const busy = checking || reinstalling;

  const chip = reinstalling
    ? { text: "重装中", tone: "busy" as const }
    : checking
      ? { text: "检查中", tone: "busy" as const }
      : rt === "running"
        ? { text: "运行中", tone: "ok" as const }
        : rt === "stopped"
          ? { text: "已停止", tone: "idle" as const }
          : { text: rt === "starting" ? "启动中" : "停止中", tone: "busy" as const };

  return (
    <div className="rounded-[28px] bg-surface-container p-5">
      <div className="flex items-center gap-4">
        <Shape kind="sunny" spin={busy ? "med" : undefined} className="size-14 text-primary-container">
          <SiDebian className="text-[26px] text-on-primary-container" />
        </Shape>
        <div className="min-w-0 flex-1">
          <div className="type-title-large text-on-surface">{SYSTEM.name}</div>
          <div className="truncate type-body-medium text-on-surface-variant">
            {SYSTEM.codename} · {SYSTEM.arch} · {fmtMB(SYSTEM.sizeMB)}
          </div>
        </div>
        <span
          className={cn(
            "flex h-7 shrink-0 items-center gap-1.5 rounded-full px-2.5 type-label-medium",
            chip.tone === "ok" ? "bg-success-container text-on-success-container" : "bg-surface-container-highest text-on-surface-variant",
          )}
        >
          <StatusDot tone={chip.tone} pulse={chip.tone !== "idle"} />
          {chip.text}
        </span>
      </div>

      <AnimatePresence initial={false}>
        {checking && (
          <motion.div
            key="checks"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.3, ease: [0.2, 0, 0, 1] }}
            className="overflow-hidden"
          >
            <div className="flex flex-col gap-2.5 pl-1 pt-5">
              {SYSTEM_CHECKS.map((c, i) => (
                <div key={c} className="flex items-center gap-3 type-body-medium">
                  {i < sys.checkStep ? (
                    <MdCheckCircle className="text-[20px] text-success" />
                  ) : i === sys.checkStep ? (
                    <CircularProgress size={20} stroke={2.5} />
                  ) : (
                    <span className="grid size-5 place-items-center">
                      <span className="size-1.5 rounded-full bg-outline-variant" />
                    </span>
                  )}
                  <span className={i <= sys.checkStep ? "text-on-surface" : "text-on-surface-variant"}>{c}</span>
                </div>
              ))}
            </div>
          </motion.div>
        )}
        {reinstalling && (
          <motion.div
            key="reinstall"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.3, ease: [0.2, 0, 0, 1] }}
            className="overflow-hidden"
          >
            <div className="pt-5">
              <div className="mb-2 flex justify-between type-label-medium text-on-surface-variant">
                <span>{REINSTALL_STAGES[Math.min(REINSTALL_STAGES.length - 1, Math.floor(sys.progress * REINSTALL_STAGES.length))]}</span>
                <span className="tabular-nums">{Math.round(sys.progress * 100)}%</span>
              </div>
              <WavyProgress value={sys.progress} />
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <div className="mt-5 flex items-center gap-2">
        <Button variant="tonal" icon={<MdOutlineHealthAndSafety />} disabled={busy} onClick={() => void check()}>
          检查
        </Button>
        <Button variant="text" icon={<MdRestartAlt />} disabled={busy} onClick={() => setConfirm(true)}>
          重装
        </Button>
        {sys.checkedAt && !busy && (
          <span className="ml-auto pr-1 type-body-small text-on-surface-variant">{relTime(sys.checkedAt, now)}检查</span>
        )}
      </div>

      <Dialog
        open={confirm}
        onClose={() => setConfirm(false)}
        icon={<MdRestartAlt />}
        title="重装系统？"
        actions={
          <>
            <Button variant="text" onClick={() => setConfirm(false)}>
              取消
            </Button>
            <Button
              variant="dangerText"
              onClick={() => {
                setConfirm(false);
                void reinstall();
              }}
            >
              重装
            </Button>
          </>
        }
      >
        运行时将停止，系统文件会被重置。/root 下的项目与已安装的 Agent 会保留。
      </Dialog>
    </div>
  );
}

/* ───────────── Runtimes ───────────── */
function RuntimeGroup() {
  const rts = useApp((s) => s.runtimes);
  const checkAll = useApp((s) => s.checkRuntimes);
  const reinstall = useApp((s) => s.reinstallRuntime);
  const busy = Object.values(rts).some((r) => r.status !== "ok");

  return (
    <ListGroup
      title="运行时"
      action={
        <Button variant="text" disabled={busy} onClick={() => void checkAll()}>
          全部检查
        </Button>
      }
    >
      {RUNTIMES.map((r) => {
        const st = rts[r.id];
        return (
          <ListItem
            key={r.id}
            icon={RT_ICONS[r.id]}
            headline={r.name}
            supporting={
              st.status === "reinstalling"
                ? `正在重装 · ${Math.round(st.progress * 100)}%`
                : st.status === "checking"
                  ? "检查中…"
                  : r.version
            }
            trailing={
              <div className="flex items-center gap-1">
                {st.status === "ok" ? (
                  <MdCheckCircle className="text-[20px] text-success" />
                ) : (
                  <CircularProgress size={20} stroke={2.5} value={st.status === "reinstalling" ? st.progress : undefined} />
                )}
                <IconButton aria-label={`重装 ${r.name}`} disabled={st.status !== "ok"} onClick={() => void reinstall(r.id)}>
                  <MdRestartAlt />
                </IconButton>
              </div>
            }
          />
        );
      })}
    </ListGroup>
  );
}

/* ───────────── Storage ───────────── */
function StorageGroup() {
  const agents = useApp((s) => s.agents);
  const cache = useApp((s) => s.cacheMB);
  const clear = useApp((s) => s.clearCache);
  const agentMB = AGENTS.filter((a) => isInstalled(agents[a.id].status)).reduce((n, a) => n + a.sizeMB, 0);
  const rtMB = RUNTIMES.reduce((n, r) => n + r.sizeMB, 0);
  const segs = [
    { label: "系统", mb: SYSTEM.sizeMB, cls: "bg-primary" },
    { label: "运行时", mb: rtMB, cls: "bg-tertiary" },
    { label: "Agent", mb: agentMB, cls: "bg-secondary" },
    { label: "缓存", mb: cache, cls: "bg-outline-variant" },
  ];
  const total = segs.reduce((n, s) => n + s.mb, 0);

  return (
    <ListGroup title="存储">
      <div className="rounded-[4px] bg-surface-container-low p-5">
        <div className="flex items-end justify-between gap-3">
          <div className="min-w-0">
            <div className="type-headline-medium tabular-nums text-on-surface">{fmtMB(total)}</div>
            <div className="type-body-medium text-on-surface-variant">已使用</div>
          </div>
          <Button variant="tonal" icon={<MdOutlineCleaningServices />} disabled={cache === 0} onClick={clear}>
            清理缓存
          </Button>
        </div>
        <div className="mt-5 flex h-3 w-full gap-[3px]">
          {segs.map((s) => (
            <span
              key={s.label}
              className={cn("h-full rounded-full transition-[flex-grow,min-width] duration-700 ease-[cubic-bezier(0.2,0,0,1)]", s.cls)}
              style={{ flexGrow: s.mb, flexBasis: 0, minWidth: s.mb > 0 ? 6 : 0 }}
            />
          ))}
        </div>
        <div className="mt-4 grid grid-cols-2 gap-x-5 gap-y-2.5">
          {segs.map((s) => (
            <div key={s.label} className="flex items-center gap-2 type-body-medium">
              <span className={cn("size-2.5 shrink-0 rounded-full", s.cls)} />
              <span className="flex-1 text-on-surface-variant">{s.label}</span>
              <span className="tabular-nums text-on-surface">{fmtMB(s.mb)}</span>
            </div>
          ))}
        </div>
      </div>
    </ListGroup>
  );
}

/* ───────────── Logs (pushed) ───────────── */
export function LogsScreen() {
  const logs = useApp((s) => s.logs);
  const pop = useApp((s) => s.pop);
  const clearLogs = useApp((s) => s.clearLogs);
  const showSnack = useApp((s) => s.showSnack);
  const [filter, setFilter] = useState("all");
  const ref = useRef<HTMLDivElement>(null);
  const tags = useMemo(() => Array.from(new Set(logs.map((l) => l.tag))), [logs]);
  const shown = logs.filter((l) =>
    filter === "all" ? true : filter === "warn" ? l.level === "W" || l.level === "E" : l.tag === filter,
  );

  useEffect(() => {
    const el = ref.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [shown.length, filter]);

  const copy = () => {
    const text = shown.map((l) => `${fmtClock(l.t, true)} ${l.level} ${l.tag}: ${l.msg}`).join("\n");
    void navigator.clipboard?.writeText(text).catch(() => {});
    showSnack(`已复制 ${shown.length} 条日志`);
  };

  return (
    <div className="flex h-full flex-col bg-surface">
      <TopBar
        title="运行日志"
        onBack={pop}
        actions={
          <>
            <IconButton aria-label="复制" onClick={copy}>
              <MdContentCopy />
            </IconButton>
            <IconButton
              aria-label="清空"
              onClick={() => {
                clearLogs();
                showSnack("日志已清空");
              }}
            >
              <MdDeleteOutline />
            </IconButton>
          </>
        }
      />
      <div className="flex shrink-0 gap-2 overflow-x-auto px-4 pb-3 no-scrollbar">
        {["all", "warn", ...tags].map((f) => (
          <Chip key={f} selected={filter === f} onClick={() => setFilter(f)}>
            {f === "all" ? "全部" : f === "warn" ? "警告" : f}
          </Chip>
        ))}
      </div>
      <div
        ref={ref}
        className="mx-3 mb-[calc(12px+var(--sab))] min-h-0 flex-1 overflow-y-auto rounded-[20px] bg-surface-container-lowest px-3.5 py-3 font-mono text-[11.5px] leading-[1.75] no-scrollbar"
      >
        {shown.length === 0 ? (
          <div className="grid h-full place-items-center font-sans type-body-medium text-on-surface-variant">暂无日志</div>
        ) : (
          shown.map((l) => (
            <div key={l.id} className="flex gap-2">
              <span className="shrink-0 text-on-surface-variant/70">{fmtClock(l.t)}</span>
              <span
                className={cn(
                  "shrink-0 font-bold",
                  l.level === "E" ? "text-error" : l.level === "W" ? "text-warn" : "text-success",
                )}
              >
                {l.level}
              </span>
              <span className="min-w-0 whitespace-pre-wrap break-all text-on-surface">
                <span className="text-primary">{l.tag}</span> {l.msg}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
