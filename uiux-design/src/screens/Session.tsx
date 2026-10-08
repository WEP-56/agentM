import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  MdArrowBack,
  MdArrowUpward,
  MdCheck,
  MdDeleteOutline,
  MdMoreVert,
  MdOpenInBrowser,
  MdOutlineLanguage,
  MdOutlineTerminal,
  MdRefresh,
  MdStop,
} from "react-icons/md";
import { agentById, PROJECT_DIR, type AgentId, type ViewKind } from "@/data/agents";
import { activeProviderOf, nextId, useApp, type ChatMsg, type LineTone, type TermLine } from "@/store/useApp";
import { interrupt, runTerminalInput, runWebInput } from "@/sim/agentSim";
import { IconButton } from "@/components/md/Button";
import { StatusDot } from "@/components/md/Controls";
import { TopBar } from "@/components/md/Layout";
import { Menu, type MenuItem } from "@/components/md/Overlay";
import { LinearProgress } from "@/components/md/Progress";
import { Ripple } from "@/components/md/Ripple";
import { AgentIcon, GlyphIcon } from "@/components/Brand";
import { cn } from "@/utils/cn";

export function SessionScreen({ agentId, view }: { agentId: AgentId; view: ViewKind }) {
  return (
    <div className="relative h-full">
      <AnimatePresence mode="wait" initial={false}>
        <motion.div
          key={view}
          className="absolute inset-0"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.16 }}
        >
          {view === "terminal" ? <TerminalView agentId={agentId} /> : <WebView agentId={agentId} />}
        </motion.div>
      </AnimatePresence>
    </div>
  );
}

function useSessionMenu(agentId: AgentId, current: ViewKind): MenuItem[] {
  const def = agentById(agentId);
  const setView = useApp((s) => s.setSessionView);
  const stop = useApp((s) => s.stopAgent);
  const patchSession = useApp((s) => s.patchSession);
  const items: MenuItem[] = [];
  if (def.altView || def.view !== current) {
    const other: ViewKind = current === "terminal" ? "webui" : "terminal";
    items.push({
      label: other === "webui" ? "切换到 WebUI" : "切换到终端",
      icon: other === "webui" ? <MdOutlineLanguage /> : <MdOutlineTerminal />,
      onClick: () => setView(agentId, other),
    });
  }
  items.push({
    label: "清空会话",
    icon: <MdDeleteOutline />,
    onClick: () =>
      patchSession(agentId, (s) => ({ ...s, busy: false, runId: s.runId + 1, lines: [{ id: nextId(), kind: "banner" }], msgs: [] })),
  });
  items.push({ label: "停止 Agent", icon: <MdStop />, onClick: () => void stop(agentId), danger: true });
  return items;
}

/* ═════════════════════════ Terminal (immersive, always dark) ═════════════════════════ */
const TONE: Record<LineTone, string> = {
  fg: "text-term-fg",
  dim: "text-term-dim",
  faint: "text-term-faint",
  accent: "text-term-accent",
  ok: "text-term-ok",
  err: "text-term-err",
  warn: "text-term-warn",
  add: "text-term-ok bg-term-ok/10",
  del: "text-term-err bg-term-err/10",
};

const KEYS = ["ESC", "TAB", "CTRL", "ALT", "/", "-", "↑", "↓", "←", "→"] as const;
const SLASH = ["/help", "/model", "/status", "/clear", "/exit"];

function TerminalView({ agentId }: { agentId: AgentId }) {
  const def = agentById(agentId);
  const session = useApp((s) => s.sessions[agentId]);
  const pop = useApp((s) => s.pop);
  const setView = useApp((s) => s.setSessionView);
  const patchSession = useApp((s) => s.patchSession);
  const [input, setInput] = useState("");
  const [focused, setFocused] = useState(false);
  const [mods, setMods] = useState({ ctrl: false, alt: false });
  const [histIdx, setHistIdx] = useState<number | null>(null);
  const [menu, setMenu] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const moreRef = useRef<HTMLButtonElement>(null);
  const menuItems = useSessionMenu(agentId, "terminal");
  const lines = session?.lines ?? [];
  const busy = session?.busy ?? false;
  const history = session?.history ?? [];

  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [lines, input]);

  useEffect(() => {
    if (!window.matchMedia("(pointer: fine)").matches) return;
    const t = setTimeout(() => inputRef.current?.focus({ preventScroll: true }), 380);
    return () => clearTimeout(t);
  }, []);

  const submit = () => {
    if (busy) return;
    const v = input;
    setInput("");
    setHistIdx(null);
    void runTerminalInput(agentId, v);
  };
  const clearScreen = () => patchSession(agentId, (s) => ({ ...s, lines: [{ id: nextId(), kind: "banner" }] }));
  const ctrlC = () => {
    if (!interrupt(agentId)) setInput("");
  };
  const histUp = () => {
    if (!history.length) return;
    const i = histIdx === null ? history.length - 1 : Math.max(0, histIdx - 1);
    setHistIdx(i);
    setInput(history[i]);
  };
  const histDown = () => {
    if (histIdx === null) return;
    const i = histIdx + 1;
    if (i >= history.length) {
      setHistIdx(null);
      setInput("");
    } else {
      setHistIdx(i);
      setInput(history[i]);
    }
  };
  const complete = () => {
    if (!input.startsWith("/")) return;
    const hit = SLASH.find((c) => c.startsWith(input) && c !== input);
    if (hit) setInput(hit);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.ctrlKey || mods.ctrl) {
      const k = e.key.toLowerCase();
      if (k === "c" || k === "l") {
        e.preventDefault();
        if (k === "c") ctrlC();
        else clearScreen();
        setMods({ ctrl: false, alt: false });
        return;
      }
    }
    if (e.key === "Enter") {
      e.preventDefault();
      submit();
    } else if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      interrupt(agentId);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      histUp();
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      histDown();
    } else if (e.key === "Tab") {
      e.preventDefault();
      complete();
    }
  };

  const onExtra = (k: (typeof KEYS)[number]) => {
    inputRef.current?.focus({ preventScroll: true });
    switch (k) {
      case "ESC":
        interrupt(agentId);
        break;
      case "TAB":
        complete();
        break;
      case "CTRL":
        setMods((m) => ({ ...m, ctrl: !m.ctrl }));
        break;
      case "ALT":
        setMods((m) => ({ ...m, alt: !m.alt }));
        break;
      case "↑":
        histUp();
        break;
      case "↓":
        histDown();
        break;
      case "/":
      case "-":
        setInput((v) => v + k);
        break;
      default:
        break;
    }
  };

  return (
    <div className="relative flex h-full flex-col bg-term-bg text-term-fg">
      <header className="shrink-0 bg-term-bar pt-[var(--sat)]">
        <div className="flex h-14 items-center gap-1 px-1">
          <IconButton aria-label="返回" className="text-term-fg" onClick={pop}>
            <MdArrowBack />
          </IconButton>
          <AgentIcon agent={def} size={30} className="ml-1" />
          <div className="ml-2 min-w-0 flex-1">
            <div className="truncate type-title-medium">{def.name}</div>
            <div className="flex items-center gap-1.5 font-mono text-[11px] text-term-dim">
              <StatusDot tone={busy ? "busy" : "ok"} pulse={busy} className="size-1.5" />
              {busy ? "工作中" : "就绪"} · {PROJECT_DIR}
            </div>
          </div>
          {def.altView && (
            <IconButton aria-label="切换到 WebUI" className="text-term-fg" onClick={() => setView(agentId, "webui")}>
              <MdOutlineLanguage />
            </IconButton>
          )}
          <IconButton ref={moreRef} aria-label="更多" className="text-term-fg" onClick={() => setMenu(true)}>
            <MdMoreVert />
          </IconButton>
        </div>
      </header>

      <div
        ref={scrollRef}
        onClick={() => inputRef.current?.focus({ preventScroll: true })}
        className="min-h-0 flex-1 cursor-text overflow-y-auto px-3 pb-4 pt-2 font-mono text-[12.5px] leading-[1.62] no-scrollbar"
      >
        {lines.map((l) => (
          <TermLineView key={l.id} line={l} agentId={agentId} />
        ))}
        <Prompt agentId={agentId} input={input} focused={focused} busy={busy} />
      </div>

      <div className="shrink-0 border-t border-term-border/60 bg-term-bar pb-[var(--sab)]">
        <div className="flex h-11 items-stretch overflow-x-auto no-scrollbar">
          {KEYS.map((k) => {
            const on = (k === "CTRL" && mods.ctrl) || (k === "ALT" && mods.alt);
            return (
              <button
                key={k}
                onPointerDown={(e) => e.preventDefault()}
                onClick={() => onExtra(k)}
                className={cn(
                  "relative min-w-[38px] flex-1 font-mono text-[12px] outline-none",
                  on ? "text-term-accent underline underline-offset-4" : "text-term-fg",
                )}
              >
                <Ripple />
                {k}
              </button>
            );
          })}
        </div>
      </div>

      <input
        ref={inputRef}
        value={input}
        onChange={(e) => {
          setInput(e.target.value);
          if (mods.ctrl || mods.alt) setMods({ ctrl: false, alt: false });
        }}
        onKeyDown={onKeyDown}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        autoCapitalize="off"
        autoCorrect="off"
        autoComplete="off"
        spellCheck={false}
        enterKeyHint="send"
        aria-label="终端输入"
        className="absolute bottom-0 left-0 h-px w-px text-[16px] opacity-0"
      />
      <Menu open={menu} onClose={() => setMenu(false)} anchor={moreRef} items={menuItems} />
    </div>
  );
}

function TermLineView({ line, agentId }: { line: TermLine; agentId: AgentId }) {
  if (line.kind === "banner") return <Banner agentId={agentId} />;
  if (line.kind === "user") return <UserLine agentId={agentId} text={line.text ?? ""} />;
  if (line.kind === "spinner") return <SpinnerLine agentId={agentId} text={line.text ?? ""} />;
  const tone = TONE[line.tone ?? "fg"];
  const pad = { paddingLeft: `${line.indent ?? 0}ch` };
  if (line.bullet)
    return (
      <div className={cn("flex", line.bold && "font-bold")} style={pad}>
        <span className={cn("w-[2ch] shrink-0 font-normal", TONE[line.bulletTone ?? "fg"])}>{line.bullet}</span>
        <span className={cn("min-w-0 flex-1 whitespace-pre-wrap break-words", tone)}>{line.text || "\u00a0"}</span>
      </div>
    );
  return (
    <div className={cn("whitespace-pre-wrap break-words", tone, line.bold && "font-bold")} style={pad}>
      {line.text || "\u00a0"}
    </div>
  );
}

function Banner({ agentId }: { agentId: AgentId }) {
  const def = agentById(agentId);
  const prov = useApp((s) => activeProviderOf(s, agentId));
  const model = prov.model || "default";
  switch (agentId) {
    case "claude":
      return (
        <div className="mb-1 mt-1 rounded-lg border px-3 py-2.5" style={{ borderColor: def.color }}>
          <div>
            <span style={{ color: def.color }}>✻</span> <span className="font-bold">Welcome to Claude Code!</span>
          </div>
          <div className="mt-2 text-term-dim">/help 查看帮助，/status 查看当前配置</div>
          <div className="mt-2 text-term-faint">
            {model} · {prov.name}
          </div>
          <div className="text-term-faint">cwd: /root/projects/demo</div>
        </div>
      );
    case "codex":
      return (
        <div className="mb-1 mt-1">
          <div className="rounded-lg border border-term-border px-3 py-2.5">
            <div>
              <span className="font-bold">&gt;_ OpenAI Codex</span> <span className="text-term-dim">(v{def.version})</span>
            </div>
            <div className="mt-2 text-term-dim">
              model: <span className="text-term-fg">{model}</span>
            </div>
            <div className="text-term-dim">
              directory: <span className="text-term-fg">{PROJECT_DIR}</span>
            </div>
          </div>
          <div className="mt-2 text-term-faint">描述一个任务，或试试 /help /status /model</div>
        </div>
      );
    case "opencode":
      return (
        <div className="mb-1 mt-2">
          <div className="text-[22px] font-bold leading-none tracking-tight">
            <span className="text-term-dim">open</span>code
          </div>
          <div className="mt-1 text-term-faint">v{def.version}</div>
          <div className="mt-3 grid grid-cols-[9ch_1fr] text-term-dim">
            <span className="text-term-fg">/help</span>
            <span>查看命令</span>
            <span className="text-term-fg">/model</span>
            <span>当前模型</span>
            <span className="text-term-fg">/clear</span>
            <span>新会话</span>
          </div>
        </div>
      );
    case "pi":
      return (
        <div className="mb-1 mt-1">
          <div>
            <span className="font-bold text-term-accent">π</span> <span className="font-bold">pi</span>{" "}
            <span className="text-term-faint">v{def.version}</span>
          </div>
          <div className="text-term-faint">esc 中断 · ctrl+c 清空 · / 命令 · ! bash</div>
        </div>
      );
    case "dsh":
      return (
        <div className="mb-1 mt-1 rounded-lg border border-term-border px-3 py-2.5">
          <div className="font-bold text-term-accent">DSH-Code</div>
          <div className="text-term-dim">DeepSeek Harness · profile cli · standard</div>
          <div className="mt-1 text-term-faint">
            {model} · {PROJECT_DIR}
          </div>
        </div>
      );
  }
}

function UserLine({ agentId, text }: { agentId: AgentId; text: string }) {
  switch (agentId) {
    case "claude":
      return <div className="whitespace-pre-wrap break-words text-term-dim">&gt; {text}</div>;
    case "codex":
      return (
        <div className="whitespace-pre-wrap break-words">
          <span className="text-term-faint">› </span>
          <span className="font-bold">{text}</span>
        </div>
      );
    case "pi":
      return <div className="whitespace-pre-wrap break-words rounded-md bg-term-key px-2 py-1">{text}</div>;
    case "opencode":
      return <div className="whitespace-pre-wrap break-words border-l-2 border-term-accent bg-term-key/60 py-1 pl-2">{text}</div>;
    case "dsh":
      return (
        <div className="whitespace-pre-wrap break-words">
          <span className="text-term-accent">❯ </span>
          {text}
        </div>
      );
  }
}

const SPIN_FRAMES: Record<string, string[]> = {
  claude: ["·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢"],
  codex: ["◐", "◓", "◑", "◒"],
  default: ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"],
};

function SpinnerLine({ agentId, text }: { agentId: AgentId; text: string }) {
  const frames = SPIN_FRAMES[agentId] ?? SPIN_FRAMES.default;
  const [i, setI] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setI((x) => (x + 1) % frames.length), 110);
    return () => clearInterval(t);
  }, [frames.length]);
  const def = agentById(agentId);
  return (
    <div className="mt-2 flex">
      <span className="w-[2ch] shrink-0" style={{ color: agentId === "claude" ? def.color : "var(--term-accent)" }}>
        {frames[i]}
      </span>
      <span className="animate-shimmer bg-linear-to-r from-term-dim via-term-fg to-term-dim bg-clip-text text-transparent">
        {text}
      </span>
    </div>
  );
}

function Prompt({ agentId, input, focused, busy }: { agentId: AgentId; input: string; focused: boolean; busy: boolean }) {
  const prov = useApp((s) => activeProviderOf(s, agentId));
  const model = prov.model || "default";
  const cursor = (
    <span
      className={cn(
        "inline-block h-[1.2em] w-[0.6em] translate-y-[0.22em] bg-term-fg",
        focused ? "animate-blink" : "opacity-35",
      )}
    />
  );
  const placeholder = !input && !busy && <span className="text-term-faint"> 试试 “运行测试” 或 /help</span>;
  const body = (
    <>
      <span className="whitespace-pre-wrap break-all">{input}</span>
      {cursor}
      {placeholder}
    </>
  );
  const hint = (l: string, r?: string) => (
    <div className="mt-1 flex justify-between gap-3 px-1 text-[11px] text-term-faint">
      <span className="truncate">{l}</span>
      {r && <span className="truncate">{r}</span>}
    </div>
  );

  switch (agentId) {
    case "claude":
      return (
        <div className="mt-3">
          <div className="rounded-lg border border-term-border px-2 py-1.5">
            <span className="text-term-dim">&gt; </span>
            {body}
          </div>
          {hint("? 快捷键", model)}
        </div>
      );
    case "codex":
      return (
        <div className="mt-3">
          <div className="rounded-lg bg-term-key/70 px-2 py-1.5">
            <span className="text-term-accent">› </span>
            {body}
          </div>
          {hint("⏎ 发送 · ⌃C 中断", `${model} · 100% context left`)}
        </div>
      );
    case "pi":
      return (
        <div className="mt-3">
          <div className="border-y border-term-border py-1.5">{body}</div>
          {hint(PROJECT_DIR, `${prov.name.toLowerCase()}/${model}`)}
        </div>
      );
    case "opencode":
      return (
        <div className="mt-3">
          <div className="border-l-2 border-term-accent bg-term-key/70 px-2 py-1.5">{body}</div>
          {hint(`build · ${model}`, "tab 切换代理")}
        </div>
      );
    case "dsh":
      return (
        <div className="mt-3">
          <div>
            <span className="text-term-accent">dsh ❯ </span>
            {body}
          </div>
          {hint(`standard · ${model}`)}
        </div>
      );
  }
}

/* ═════════════════════════ Web UI ═════════════════════════ */
interface Skin {
  bg: string;
  panel: string;
  fg: string;
  muted: string;
  border: string;
  accent: string;
  onAccent: string;
  chip: string;
  user: string;
  ok: string;
  add: string;
  del: string;
  mode: string;
  placeholder: string;
}

const SKINS: Record<string, Skin> = {
  opencode: {
    bg: "#0b0b0b",
    panel: "#151515",
    fg: "#ececec",
    muted: "#8c8c8c",
    border: "#262626",
    accent: "#fab283",
    onAccent: "#111111",
    chip: "#1f1f1f",
    user: "#181818",
    ok: "#7fd88f",
    add: "#7fd88f",
    del: "#f47067",
    mode: "Build",
    placeholder: "问点什么… 例如 “解释这个项目”",
  },
  dsh: {
    bg: "#ffffff",
    panel: "#f6f7fb",
    fg: "#1d2129",
    muted: "#6b7280",
    border: "#e6e8ef",
    accent: "#4d6bfe",
    onAccent: "#ffffff",
    chip: "#eef1ff",
    user: "#eef1ff",
    ok: "#16a34a",
    add: "#15803d",
    del: "#dc2626",
    mode: "standard",
    placeholder: "给 DSH 发送消息",
  },
};

const SUGGESTIONS = ["解释一下这个项目", "运行测试", "创建 hello.ts"];

function Wordmark({ agentId, skin, big }: { agentId: AgentId; skin: Skin; big?: boolean }) {
  if (agentId === "opencode")
    return (
      <span className={cn("font-mono font-bold tracking-tight", big ? "text-[28px]" : "text-[15px]")}>
        <span style={{ opacity: 0.5 }}>open</span>code
      </span>
    );
  if (agentId === "dsh")
    return (
      <span className="flex items-center gap-2">
        <span
          className={cn("grid place-items-center rounded-lg", big ? "size-10" : "size-6")}
          style={{ background: skin.accent, color: skin.onAccent }}
        >
          <GlyphIcon kind="wave" size={big ? 24 : 14} />
        </span>
        <span className={cn("font-semibold", big ? "text-[20px]" : "text-[14px]")}>DeepSeek Harness</span>
      </span>
    );
  return <span className="font-semibold">{agentById(agentId).name}</span>;
}

function WebView({ agentId }: { agentId: AgentId }) {
  const def = agentById(agentId);
  const session = useApp((s) => s.sessions[agentId]);
  const prov = useApp((s) => activeProviderOf(s, agentId));
  const pop = useApp((s) => s.pop);
  const showSnack = useApp((s) => s.showSnack);
  const [loading, setLoading] = useState(true);
  const [text, setText] = useState("");
  const [menu, setMenu] = useState(false);
  const moreRef = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const baseItems = useSessionMenu(agentId, "webui");
  const skin = SKINS[agentId] ?? SKINS.dsh;
  const msgs = session?.msgs ?? [];
  const busy = session?.busy ?? false;
  const streaming = msgs.some((m) => m.streaming);
  const url = `127.0.0.1:${def.port ?? 8080}`;

  useEffect(() => {
    const t = setTimeout(() => setLoading(false), 650);
    return () => clearTimeout(t);
  }, []);
  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [msgs, busy]);

  const reload = () => {
    setLoading(true);
    setTimeout(() => setLoading(false), 800);
  };
  const send = (t?: string) => {
    const v = (t ?? text).trim();
    if (!v || busy) return;
    setText("");
    void runWebInput(agentId, v);
  };

  const items: MenuItem[] = [
    {
      label: "在浏览器中打开",
      icon: <MdOpenInBrowser />,
      onClick: () => {
        void navigator.clipboard?.writeText(`http://${url}`).catch(() => {});
        showSnack(`已复制 http://${url}`);
      },
    },
    ...baseItems,
  ];

  return (
    <div className="flex h-full flex-col bg-surface">
      <TopBar
        title={def.name}
        subtitle={<span className="font-mono">{url}</span>}
        onBack={pop}
        actions={
          <>
            <IconButton aria-label="刷新" onClick={reload}>
              <MdRefresh />
            </IconButton>
            <IconButton ref={moreRef} aria-label="更多" onClick={() => setMenu(true)}>
              <MdMoreVert />
            </IconButton>
          </>
        }
      />
      <div
        className="relative mx-2 mb-[calc(8px+var(--sab))] min-h-0 flex-1 overflow-hidden rounded-[24px]"
        style={{ background: skin.bg, color: skin.fg }}
      >
        {loading && <LinearProgress className="absolute inset-x-0 top-0 z-10 rounded-none" />}
        <motion.div
          className="flex h-full flex-col"
          initial={false}
          animate={{ opacity: loading ? 0 : 1 }}
          transition={{ duration: 0.25 }}
        >
          <div className="flex h-12 shrink-0 items-center gap-2 border-b px-4" style={{ borderColor: skin.border }}>
            <Wordmark agentId={agentId} skin={skin} />
            <span className="ml-auto truncate text-[12px]" style={{ color: skin.muted }}>
              {msgs.length ? "demo · 会话 1" : "新会话"}
            </span>
          </div>

          <div ref={listRef} className="min-h-0 flex-1 overflow-y-auto px-4 py-4 no-scrollbar">
            {msgs.length === 0 ? (
              <div className="flex h-full flex-col items-center justify-center text-center">
                <Wordmark agentId={agentId} skin={skin} big />
                <p className="mt-2 font-mono text-[12px]" style={{ color: skin.muted }}>
                  {PROJECT_DIR}
                </p>
                <div className="mt-7 flex w-full max-w-[260px] flex-col gap-2">
                  {SUGGESTIONS.map((s) => (
                    <button
                      key={s}
                      onClick={() => send(s)}
                      className="rounded-xl border px-3.5 py-2.5 text-left text-[13px] transition-opacity hover:opacity-80"
                      style={{ borderColor: skin.border, background: skin.panel }}
                    >
                      {s}
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              <div className="flex flex-col gap-3">
                {msgs.map((m) => (
                  <WebMsg key={m.id} m={m} skin={skin} agentId={agentId} />
                ))}
                {busy && !streaming && (
                  <div className="flex items-center gap-1.5 py-1" style={{ color: skin.muted }}>
                    {[0, 1, 2].map((i) => (
                      <motion.span
                        key={i}
                        className="size-1.5 rounded-full"
                        style={{ background: skin.accent }}
                        animate={{ opacity: [0.25, 1, 0.25] }}
                        transition={{ duration: 1, repeat: Infinity, delay: i * 0.18 }}
                      />
                    ))}
                    <span className="ml-1 text-[12px]">思考中</span>
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="shrink-0 px-3 pb-3 pt-1">
            <div className="rounded-2xl border p-2" style={{ borderColor: skin.border, background: skin.panel }}>
              <input
                value={text}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    send();
                  }
                }}
                placeholder={skin.placeholder}
                enterKeyHint="send"
                className="w-full bg-transparent px-2 py-1.5 text-[14px] outline-none placeholder:opacity-50"
                style={{ color: skin.fg }}
              />
              <div className="mt-1 flex items-center gap-2 px-1">
                <span className="rounded-md px-2 py-0.5 text-[11px] font-medium" style={{ background: skin.chip, color: skin.muted }}>
                  {skin.mode}
                </span>
                <span className="min-w-0 truncate font-mono text-[11px]" style={{ color: skin.muted }}>
                  {prov.model || "default"}
                </span>
                <button
                  aria-label={busy ? "停止" : "发送"}
                  onClick={() => (busy ? interrupt(agentId) : send())}
                  className="ml-auto grid size-8 shrink-0 place-items-center rounded-full text-[18px] transition-opacity disabled:opacity-40"
                  disabled={!busy && !text.trim()}
                  style={{ background: skin.accent, color: skin.onAccent }}
                >
                  {busy ? <MdStop /> : <MdArrowUpward />}
                </button>
              </div>
            </div>
          </div>
        </motion.div>
      </div>
      <Menu open={menu} onClose={() => setMenu(false)} anchor={moreRef} items={items} />
    </div>
  );
}

function WebMsg({ m, skin, agentId }: { m: ChatMsg; skin: Skin; agentId: AgentId }) {
  if (m.role === "user")
    return agentId === "opencode" ? (
      <div className="border-l-2 px-3 py-2 text-[14px] leading-relaxed" style={{ borderColor: skin.accent, background: skin.user }}>
        {m.text}
      </div>
    ) : (
      <div className="flex justify-end">
        <div className="max-w-[85%] rounded-2xl rounded-br-md px-3.5 py-2 text-[14px] leading-relaxed" style={{ background: skin.user }}>
          {m.text}
        </div>
      </div>
    );
  if (m.role === "assistant")
    return (
      <div className="text-[14px] leading-relaxed">
        {m.text}
        {m.streaming && (
          <span className="ml-0.5 inline-block h-[1em] w-[2px] translate-y-[0.15em] animate-blink" style={{ background: skin.fg }} />
        )}
      </div>
    );
  const t = m.tool!;
  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      className="rounded-xl border px-3 py-2 font-mono text-[12px]"
      style={{ borderColor: skin.border, background: skin.panel }}
    >
      <div className="flex items-center gap-2">
        <span className="font-bold" style={{ color: skin.accent }}>
          {t.name}
        </span>
        <span className="min-w-0 truncate" style={{ color: skin.muted }}>
          {t.arg}
        </span>
        <MdCheck className="ml-auto shrink-0 text-[14px]" style={{ color: skin.ok }} />
      </div>
      {(t.diff || t.out) && (
        <div className="mt-2 overflow-x-auto rounded-md px-2 py-1.5 leading-[1.6] no-scrollbar" style={{ background: skin.bg }}>
          {t.diff?.del.map((x, i) => (
            <div key={`d${i}`} className="whitespace-pre" style={{ color: skin.del }}>
              - {x}
            </div>
          ))}
          {t.diff?.add.map((x, i) => (
            <div key={`a${i}`} className="whitespace-pre" style={{ color: skin.add }}>
              + {x}
            </div>
          ))}
          {t.out?.map((x, i) => (
            <div key={`o${i}`} className="whitespace-pre" style={{ color: skin.muted }}>
              {x}
            </div>
          ))}
        </div>
      )}
      <div className="mt-1" style={{ color: skin.muted }}>
        {t.result}
      </div>
    </motion.div>
  );
}
