import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { MdArrowBack, MdCheck, MdKeyboardArrowDown } from "react-icons/md";
import { SiUbuntu } from "react-icons/si";
import { AGENTS, agentById, APP_NAME, MIRRORS, type AgentId } from "@/data/agents";
import { useApp } from "@/store/useApp";
import { Button, IconButton } from "@/components/md/Button";
import { Checkbox, Chip, Radio } from "@/components/md/Controls";
import { WavyProgress } from "@/components/md/Progress";
import { Ripple } from "@/components/md/Ripple";
import { AgentIcon, GlyphIcon, Shape } from "@/components/Brand";
import { cn } from "@/utils/cn";
import { sleep } from "@/utils/format";

const slide = {
  enter: (d: number) => ({ opacity: 0, x: d * 56 }),
  center: { opacity: 1, x: 0, transition: { duration: 0.4, ease: [0.2, 0, 0, 1] as const } },
  exit: (d: number) => ({ opacity: 0, x: d * -56, transition: { duration: 0.2, ease: [0.3, 0, 0.8, 0.15] as const } }),
};

export function Onboarding() {
  const [step, setStep] = useState(0);
  const [dir, setDir] = useState(1);
  const [mirror, setMirror] = useState("auto");
  const [selected, setSelected] = useState<AgentId[]>(["claude"]);
  const go = (n: number) => {
    setDir(n > step ? 1 : -1);
    setStep(n);
  };
  const toggle = (id: AgentId) =>
    setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : AGENTS.map((a) => a.id).filter((x) => x === id || s.includes(x))));

  return (
    <motion.div
      className="absolute inset-0 flex flex-col bg-surface pb-[var(--sab)] pt-[var(--sat)]"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.3 }}
    >
      <div className="flex h-14 shrink-0 items-center px-2">
        <div className="w-10">
          {step > 0 && step < 3 && (
            <IconButton aria-label="上一步" onClick={() => go(step - 1)}>
              <MdArrowBack />
            </IconButton>
          )}
        </div>
        <div className="flex flex-1 justify-center gap-1.5">
          {[0, 1, 2, 3].map((i) => (
            <span
              key={i}
              className={cn(
                "h-1.5 rounded-full transition-all duration-500 ease-[cubic-bezier(0.2,0,0,1)]",
                i === step ? "w-6 bg-primary" : i < step ? "w-1.5 bg-primary/50" : "w-1.5 bg-outline-variant",
              )}
            />
          ))}
        </div>
        <div className="w-10" />
      </div>

      <div className="relative min-h-0 flex-1 overflow-hidden">
        <AnimatePresence initial={false} custom={dir}>
          <motion.div
            key={step}
            custom={dir}
            variants={slide}
            initial="enter"
            animate="center"
            exit="exit"
            className="absolute inset-0 flex flex-col"
          >
            {step === 0 && <Welcome onNext={() => go(1)} />}
            {step === 1 && <SystemStep mirror={mirror} setMirror={setMirror} onNext={() => go(2)} />}
            {step === 2 && <AgentStep selected={selected} toggle={toggle} onNext={() => go(3)} />}
            {step === 3 && <InstallStep selected={selected} mirror={mirror} />}
          </motion.div>
        </AnimatePresence>
      </div>
    </motion.div>
  );
}

/* ───────────── Step 0 · Welcome ─────────────  */
function Welcome({ onNext }: { onNext: () => void }) {
  return (
    <div className="flex h-full flex-col px-6">
      <div className="flex min-h-0 flex-1 flex-col items-center justify-center">
        <div className="relative size-[220px]">
          <Shape kind="cookie12" spin="slow" className="absolute inset-0 text-primary-container" />
          <Shape kind="clover" className="animate-float absolute -right-3 top-0 size-16 text-tertiary-container" />
          <span className="animate-float absolute -left-2 bottom-5 size-10 rounded-full bg-secondary-container [animation-delay:-3s]" />
          <div className="absolute inset-0 grid place-items-center text-on-primary-container">
            <GlyphIcon kind="app" size={84} />
          </div>
        </div>
        <motion.h1
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.15, duration: 0.5, ease: [0.05, 0.7, 0.1, 1] }}
          className="mt-12 type-display-small text-on-surface"
        >
          {APP_NAME}
        </motion.h1>
        <motion.p
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.25, duration: 0.5, ease: [0.05, 0.7, 0.1, 1] }}
          className="mt-3 text-center type-body-large text-on-surface-variant"
        >
          在口袋里运行真正的 AI Agent
        </motion.p>
      </div>
      <div className="pb-4">
        <Button size="lg" className="w-full" onClick={onNext}>
          开始使用
        </Button>
        <p className="mt-4 text-center type-body-small text-on-surface-variant/80">内置 Linux 运行时 · Ubuntu 子系统</p>
      </div>
    </div>
  );
}

/* ───────────── Step 1 · System ───────────── */
function SystemStep({ mirror, setMirror, onNext }: { mirror: string; setMirror: (m: string) => void; onNext: () => void }) {
  return (
    <div className="flex h-full flex-col px-6">
      <div className="min-h-0 flex-1 overflow-y-auto pt-4 no-scrollbar">
        <h1 className="type-headline-medium text-on-surface">安装系统</h1>
        <p className="mt-2 type-body-medium text-on-surface-variant">Agent 运行在独立的 Ubuntu 子系统中，提供标准 Linux 工具链。</p>

        <div className="mt-8 rounded-[28px] border-2 border-primary bg-primary-container/35 p-5">
          <div className="flex items-center gap-4">
            <Shape kind="sunny" className="size-14 text-primary-container">
              <SiUbuntu className="text-[26px] text-on-primary-container" />
            </Shape>
            <div className="min-w-0 flex-1">
              <div className="type-title-large text-on-surface">Ubuntu 24.04</div>
              <div className="type-body-medium text-on-surface-variant">noble · arm64</div>
            </div>
            <Radio checked />
          </div>
          <div className="mt-5 grid grid-cols-3 gap-2">
            {[
              ["32 MB", "下载"],
              ["1.2 GB", "占用"],
              ["免 Root", "proot"],
            ].map(([v, l]) => (
              <div key={l} className="rounded-2xl bg-surface-container-lowest/70 px-3 py-2.5">
                <div className="type-title-small text-on-surface">{v}</div>
                <div className="type-body-small text-on-surface-variant">{l}</div>
              </div>
            ))}
          </div>
        </div>

        <div className="mt-8 type-title-small text-on-surface">下载源</div>
        <div className="mt-3 flex flex-wrap gap-2">
          {MIRRORS.map((m) => (
            <Chip key={m.id} selected={mirror === m.id} onClick={() => setMirror(m.id)}>
              {m.name}
            </Chip>
          ))}
        </div>
      </div>
      <div className="pb-4 pt-4">
        <Button size="lg" className="w-full" onClick={onNext}>
          下一步
        </Button>
      </div>
    </div>
  );
}

/* ───────────── Step 2 · Agents ───────────── */
function AgentStep({ selected, toggle, onNext }: { selected: AgentId[]; toggle: (id: AgentId) => void; onNext: () => void }) {
  return (
    <div className="flex h-full flex-col px-6">
      <div className="min-h-0 flex-1 overflow-y-auto pt-4 no-scrollbar">
        <h1 className="type-headline-medium text-on-surface">选择 Agent</h1>
        <p className="mt-2 type-body-medium text-on-surface-variant">稍后也可以在首页安装或移除。</p>
        <div className="mt-6 flex flex-col gap-2 pb-2">
          {AGENTS.map((a) => {
            const on = selected.includes(a.id);
            return (
              <button
                key={a.id}
                onClick={() => toggle(a.id)}
                className={cn(
                  "relative flex items-center gap-4 rounded-[20px] p-3 pr-4 text-left outline-none transition-colors duration-200",
                  on ? "bg-secondary-container text-on-secondary-container" : "bg-surface-container-low text-on-surface",
                )}
              >
                <Ripple />
                <AgentIcon agent={a} size={44} />
                <div className="min-w-0 flex-1">
                  <div className="type-title-medium">{a.name}</div>
                  <div className="truncate type-body-medium opacity-75">{a.tagline}</div>
                </div>
                <Checkbox checked={on} />
              </button>
            );
          })}
        </div>
      </div>
      <div className="pb-4 pt-4">
        <Button size="lg" className="w-full" onClick={onNext}>
          {selected.length ? `安装 ${selected.length} 个 Agent` : "仅安装系统"}
        </Button>
      </div>
    </div>
  );
}

/* ───────────── Step 3 · Install ───────────── */
interface Task {
  label: string;
  tag: string;
  weight: number;
  lines: string[];
}

function buildTasks(selected: AgentId[], mirror: string): Task[] {
  const host = MIRRORS.find((m) => m.id === mirror)?.host ?? "deb.debian.org";
  return [
    {
      label: "下载 Ubuntu 根文件系统",
      tag: "debian",
      weight: 2.2,
      lines: [
        "$ agentbox bootstrap --distro debian:noble --arch arm64",
        `GET https://${host}/rootfs/noble-arm64.tar.xz`,
        "已下载 31.6 MB / 31.6 MB",
        "sha256 校验通过",
      ],
    },
    {
      label: "解压并配置系统",
      tag: "debian",
      weight: 1.7,
      lines: [
        "解压到 /data/data/dev.agentm.app/files/debian",
        `写入 /etc/apt/sources.list → ${host}`,
        "配置 locale zh_CN.UTF-8 · 时区 Asia/Shanghai",
        "启动 proot 运行时 … OK",
      ],
    },
    {
      label: "安装 Node.js 与基础工具",
      tag: "runtime",
      weight: 2.1,
      lines: [
        "$ apt-get update",
        `Hit:1 http://${host}/debian noble InRelease`,
        "$ apt-get install -y nodejs git python3 ripgrep",
        "Setting up nodejs (22.20.0-1nodesource1) …",
        "Setting up git (1:2.39.5-0+deb12u2) …",
        "Setting up python3 (3.11.2-1+b1) …",
      ],
    },
    ...selected.map((id): Task => {
      const a = agentById(id);
      return {
        label: `安装 ${a.name}`,
        tag: a.cmd,
        weight: 1.1,
        lines: [
          `$ npm install -g ${a.pkg}`,
          `added ${18 + Math.floor(a.sizeMB / 3)} packages in ${(a.sizeMB / 16).toFixed(1)}s`,
          `${a.cmd} --version → ${a.version}`,
        ],
      };
    }),
    { label: "完成初始化", tag: "app", weight: 0.6, lines: ["创建示例项目 ~/projects/demo", "✓ 完成"] },
  ];
}

function InstallStep({ selected, mirror }: { selected: AgentId[]; mirror: string }) {
  const complete = useApp((s) => s.completeOnboarding);
  const [progress, setProgress] = useState(0);
  const [label, setLabel] = useState("准备中…");
  const [logs, setLogs] = useState<string[]>([]);
  const [done, setDone] = useState(false);
  const [showLog, setShowLog] = useState(false);
  const logRef = useRef<HTMLDivElement>(null);
  const collected = useRef<{ tag: string; msg: string }[]>([]);

  useEffect(() => {
    const el = logRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [logs, showLog]);

  useEffect(() => {
    let cancelled = false;
    const tasks = buildTasks(selected, mirror);
    const total = tasks.reduce((a, t) => a + t.weight, 0);
    (async () => {
      await sleep(350);
      let acc = 0;
      for (const t of tasks) {
        if (cancelled) return;
        setLabel(t.label);
        const per = (t.weight * 1000) / t.lines.length;
        for (let i = 0; i < t.lines.length; i++) {
          await sleep(per);
          if (cancelled) return;
          const line = t.lines[i];
          setLogs((l) => [...l, line]);
          collected.current.push({ tag: t.tag, msg: line.replace(/^\$ /, "") });
          setProgress((acc + t.weight * ((i + 1) / t.lines.length)) / total);
        }
        acc += t.weight;
      }
      await sleep(350);
      if (!cancelled) setDone(true);
    })();
    return () => {
      cancelled = true;
    };
  }, [selected, mirror]);

  return (
    <div className="flex h-full flex-col px-6">
      <div className="flex min-h-0 flex-1 flex-col items-center justify-center">
        <div className="relative grid size-[196px] shrink-0 place-items-center">
          <Shape
            kind="cookie12"
            spin={done ? undefined : "med"}
            className={cn("absolute inset-0 transition-colors duration-700", done ? "text-primary" : "text-primary-container")}
          />
          <AnimatePresence mode="wait">
            {done ? (
              <motion.span
                key="ok"
                initial={{ scale: 0, rotate: -60 }}
                animate={{ scale: 1, rotate: 0 }}
                transition={{ type: "spring", stiffness: 260, damping: 16 }}
                className="relative flex text-on-primary"
              >
                <MdCheck size={84} />
              </motion.span>
            ) : (
              <motion.span
                key="pct"
                exit={{ opacity: 0, scale: 0.7 }}
                className="relative type-display-small tabular-nums text-on-primary-container"
              >
                {Math.round(progress * 100)}
                <span className="type-title-large">%</span>
              </motion.span>
            )}
          </AnimatePresence>
        </div>
        <h1 className="mt-10 type-headline-small text-on-surface">{done ? "安装完成" : "正在安装"}</h1>
        <p className="mt-2 h-5 type-body-medium text-on-surface-variant">{done ? "环境已就绪，开始构建吧" : label}</p>
        <div className="mt-8 w-full">
          <WavyProgress value={progress} />
        </div>
        <button
          onClick={() => setShowLog((v) => !v)}
          className="relative mt-5 flex h-10 items-center gap-1 rounded-full px-4 type-label-large text-primary outline-none"
        >
          <Ripple />
          {showLog ? "收起日志" : "查看日志"}
          <MdKeyboardArrowDown className={cn("text-[20px] transition-transform duration-300", showLog && "rotate-180")} />
        </button>
        <AnimatePresence initial={false}>
          {showLog && (
            <motion.div
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: "auto", opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.3, ease: [0.2, 0, 0, 1] }}
              className="w-full overflow-hidden"
            >
              <div
                ref={logRef}
                className="mt-2 h-36 overflow-y-auto rounded-2xl bg-term-bg px-3 py-2.5 font-mono text-[11px] leading-[1.65] text-term-dim no-scrollbar"
              >
                {logs.map((l, i) => (
                  <div
                    key={i}
                    className={cn(
                      "whitespace-pre-wrap break-all",
                      l.startsWith("$") && "text-term-accent",
                      l.startsWith("✓") && "text-term-ok",
                    )}
                  >
                    {l}
                  </div>
                ))}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
      <div className="h-[88px] shrink-0 pt-4">
        <AnimatePresence>
          {done && (
            <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4, ease: [0.05, 0.7, 0.1, 1] }}>
              <Button size="lg" className="w-full" onClick={() => complete(selected, mirror, collected.current)}>
                进入 {APP_NAME}
              </Button>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
  );
}
