import { agentById, PROJECT_DIR, RUNTIMES, type AgentId } from "@/data/agents";
import {
  activeProviderOf,
  nextId,
  useApp,
  type ChatMsg,
  type LineTone,
  type TermLine,
  type ToolCall,
} from "@/store/useApp";
import { hostOf, sleep } from "@/utils/format";

type Ev = { k: "think"; ms: number } | { k: "say"; text: string } | { k: "tool"; tool: ToolCall } | { k: "done"; secs: number };
type L = Omit<TermLine, "id">;

const has = (s: string, words: string[]) => words.some((w) => s.toLowerCase().includes(w));

/* ───────────── Reply scripts (agent-agnostic, keyword-driven) ───────────── */
function buildReply(agentId: AgentId, input: string): Ev[] {
  const def = agentById(agentId);
  const prov = activeProviderOf(useApp.getState(), agentId);
  const short = input.length > 14 ? input.slice(0, 14) + "…" : input;

  if (has(input, ["你好", "hello", "hi", "嗨", "在吗", "hey"]))
    return [
      { k: "think", ms: 700 },
      {
        k: "say",
        text: `你好！我是 ${def.name}，正通过 ${prov.name} 的 ${prov.model || "默认模型"} 运行在 Debian 12 (arm64) 上。当前目录 ${PROJECT_DIR} 是一个小型 TypeScript 项目，想让我做点什么？`,
      },
      { k: "done", secs: 2 },
    ];
  if (has(input, ["测试", "test"]))
    return [
      { k: "think", ms: 800 },
      { k: "say", text: "先运行一下测试套件。" },
      {
        k: "tool",
        tool: {
          name: "Bash",
          arg: "npm test",
          result: "3 passed · 0 failed (0.82s)",
          out: ["✓ greet() 返回默认问候", "✓ greet(name) 使用传入的名字", "✓ greet() 去除首尾空白"],
        },
      },
      { k: "say", text: "全部 3 个测试通过，没有发现回归问题。" },
      { k: "done", secs: 6 },
    ];
  if (has(input, ["创建", "新建", "写一个", "生成", "create", "add", "new"]))
    return [
      { k: "think", ms: 900 },
      { k: "say", text: "好的，我来新建文件并验证它能正常运行。" },
      {
        k: "tool",
        tool: {
          name: "Write",
          arg: "src/hello.ts",
          result: "写入 4 行",
          diff: {
            del: [],
            add: ['export function hello(name = "world") {', "  return `Hello, ${name}!`;", "}", "console.log(hello());"],
          },
        },
      },
      { k: "tool", tool: { name: "Bash", arg: "npx tsx src/hello.ts", result: "退出码 0", out: ["Hello, world!"] } },
      { k: "say", text: "已创建 src/hello.ts，运行输出 “Hello, world!”。" },
      { k: "done", secs: 9 },
    ];
  if (has(input, ["解释", "介绍", "是什么", "看看", "结构", "explain", "what"]))
    return [
      { k: "think", ms: 800 },
      { k: "tool", tool: { name: "Read", arg: "README.md", result: "读取 24 行" } },
      { k: "tool", tool: { name: "Read", arg: "src/index.ts", result: "读取 41 行" } },
      {
        k: "say",
        text: "这是一个命令行问候工具：src/index.ts 解析参数并调用 src/greet.ts 中的 greet()，测试位于 test/ 目录并使用 vitest 运行。结构清晰，适合继续扩展。",
      },
      { k: "done", secs: 7 },
    ];
  return [
    { k: "think", ms: 1000 },
    { k: "say", text: "我先定位相关代码。" },
    { k: "tool", tool: { name: "Search", arg: '"greet" in src/', result: "找到 3 处匹配" } },
    {
      k: "tool",
      tool: {
        name: "Edit",
        arg: "src/greet.ts",
        result: "1 处修改",
        diff: { del: ['  return "Hello " + name;'], add: ["  return `Hello, ${name.trim()}!`;"] },
      },
    },
    { k: "tool", tool: { name: "Bash", arg: "npm test", result: "3 passed · 0 failed" } },
    { k: "say", text: `已处理「${short}」：修改了 src/greet.ts，并确认测试全部通过。` },
    { k: "done", secs: 11 },
  ];
}

/* ───────────── Per-agent terminal styles ───────────── */
interface Style {
  say: { bullet?: string; bulletTone?: LineTone };
  tool: (t: ToolCall) => L[];
  done: (secs: number) => L[];
  spinner: () => string;
}

const diffLines = (t: ToolCall, indent: number): L[] => [
  ...(t.diff?.del ?? []).map((x): L => ({ kind: "line", indent, tone: "del", text: `- ${x}` })),
  ...(t.diff?.add ?? []).map((x): L => ({ kind: "line", indent, tone: "add", text: `+ ${x}` })),
  ...(t.out ?? []).map((x): L => ({ kind: "line", indent, tone: "dim", text: x })),
];

const pick = <T,>(arr: T[]) => arr[Math.floor(Math.random() * arr.length)];

const STYLES: Record<AgentId, Style> = {
  claude: {
    say: { bullet: "●", bulletTone: "fg" },
    tool: (t) => [
      { kind: "line", bullet: "●", bulletTone: "ok", bold: true, text: `${t.name}(${t.arg})` },
      { kind: "line", indent: 2, tone: "dim", text: `⎿  ${t.result}` },
      ...diffLines(t, 5),
    ],
    done: () => [],
    spinner: () => `${pick(["Thinking", "Pondering", "Cogitating", "Brewing", "Noodling"])}… (esc 中断)`,
  },
  codex: {
    say: {},
    tool: (t) => {
      const head =
        t.name === "Read" || t.name === "Search"
          ? "Explored"
          : t.name === "Bash"
            ? `Ran ${t.arg}`
            : `Edited ${t.arg} (+${t.diff?.add.length ?? 0} -${t.diff?.del.length ?? 0})`;
      const sub = t.name === "Read" ? `Read ${t.arg}` : t.name === "Search" ? `Search ${t.arg}` : t.result;
      return [
        { kind: "line", bullet: "•", bulletTone: "accent", bold: true, text: head },
        { kind: "line", indent: 2, tone: "dim", text: `└ ${sub}` },
        ...diffLines(t, 4),
      ];
    },
    done: (s) => [{ kind: "line", tone: "faint", text: `─ Worked for ${s}s ${"─".repeat(22)}` }],
    spinner: () => "Working (esc to interrupt)",
  },
  opencode: {
    say: {},
    tool: (t) => [
      { kind: "line", bullet: "┃", bulletTone: "accent", tone: "dim", text: `${t.name}  ${t.arg}` },
      { kind: "line", bullet: "┃", bulletTone: "accent", tone: "faint", text: t.result },
      ...diffLines(t, 2),
    ],
    done: (s) => [{ kind: "line", tone: "faint", text: `▣ build · ${s}s` }],
    spinner: () => "Generating…",
  },
  pi: {
    say: {},
    tool: (t) => [
      { kind: "line", tone: "accent", bold: true, text: `${t.name.toLowerCase()} ${t.arg}` },
      { kind: "line", indent: 2, tone: "dim", text: t.result },
      ...diffLines(t, 2),
    ],
    done: (s) => [{ kind: "line", tone: "faint", text: `↳ ${s}s · ${(s * 0.42 + 1.1).toFixed(1)}k tokens` }],
    spinner: () => "Working…",
  },
  dsh: {
    say: { bullet: "◆", bulletTone: "accent" },
    tool: (t) => [
      { kind: "line", bullet: "⏺", bulletTone: "ok", text: `${t.name} ${t.arg}` },
      { kind: "line", indent: 2, tone: "dim", text: `↳ ${t.result}` },
      ...diffLines(t, 4),
    ],
    done: (s) => [{ kind: "line", tone: "faint", text: `standard · ${s}s` }],
    spinner: () => "思考中…",
  },
};

/* ───────────── Session helpers ───────────── */
const patch = (id: AgentId, fn: Parameters<ReturnType<typeof useApp.getState>["patchSession"]>[1]) =>
  useApp.getState().patchSession(id, fn);

const addLines = (id: AgentId, lines: L[]) =>
  patch(id, (s) => ({ ...s, lines: [...s.lines, ...lines.map((l) => ({ id: nextId(), ...l }))].slice(-500) }));

function startRun(id: AgentId) {
  let runId = 0;
  patch(id, (s) => {
    runId = s.runId + 1;
    return { ...s, busy: true, runId };
  });
  return runId;
}
const alive = (id: AgentId, runId: number) => {
  const s = useApp.getState().sessions[id];
  return !!s && s.busy && s.runId === runId;
};
const endRun = (id: AgentId, runId: number) => {
  if (alive(id, runId)) patch(id, (s) => ({ ...s, busy: false }));
};

export function interrupt(id: AgentId) {
  const s = useApp.getState().sessions[id];
  if (!s?.busy) return false;
  patch(id, (x) => ({
    ...x,
    busy: false,
    runId: x.runId + 1,
    lines: [
      ...x.lines.filter((l) => l.kind !== "spinner"),
      { id: nextId(), kind: "line", indent: 2, tone: "warn", text: "⎿  已被用户中断" },
    ],
    msgs: x.msgs.map((m) => (m.streaming ? { ...m, streaming: false } : m)),
  }));
  return true;
}

async function streamTo(id: AgentId, runId: number, lineId: number, full: string, field: "lines" | "msgs") {
  for (let i = 0; i < full.length; ) {
    if (!alive(id, runId)) return false;
    i = Math.min(full.length, i + 2 + Math.floor(Math.random() * 3));
    const part = full.slice(0, i);
    patch(id, (s) =>
      field === "lines"
        ? { ...s, lines: s.lines.map((l) => (l.id === lineId ? { ...l, text: part } : l)) }
        : { ...s, msgs: s.msgs.map((m) => (m.id === lineId ? { ...m, text: part } : m)) },
    );
    await sleep(20);
  }
  return true;
}

/* ───────────── Terminal ───────────── */
const SHELL: Record<string, string[]> = {
  ls: ["README.md  package.json  src  test  tsconfig.json"],
  "ls -la": [
    "drwxr-xr-x 5 root root 4096 .",
    "-rw-r--r-- 1 root root  812 README.md",
    "-rw-r--r-- 1 root root  604 package.json",
    "drwxr-xr-x 2 root root 4096 src",
    "drwxr-xr-x 2 root root 4096 test",
  ],
  pwd: ["/root/projects/demo"],
  whoami: ["root"],
  "uname -a": ["Linux localhost 6.1.75-android15 #1 SMP PREEMPT aarch64 GNU/Linux"],
  "node -v": ["v22.20.0"],
  "python3 --version": ["Python 3.11.2"],
  "git status": ["On branch main", "nothing to commit, working tree clean"],
  "cat /etc/os-release": ['PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"', 'VERSION_ID="12"', "ID=debian"],
  "df -h": ["Filesystem  Size  Used Avail Use% Mounted on", "/data       112G   56G   56G  50% /"],
};

function handleSlash(id: AgentId, input: string) {
  const def = agentById(id);
  const prov = activeProviderOf(useApp.getState(), id);
  const cmd = input.split(/\s+/)[0];
  const row = (a: string, b: string, tone: LineTone = "dim"): L => ({ kind: "line", indent: 2, tone, text: `${a.padEnd(9)} ${b}` });
  switch (cmd) {
    case "/help":
      addLines(id, [
        { kind: "line", text: "" },
        row("/model", "查看当前模型", "fg"),
        row("/status", "运行状态与配置", "fg"),
        row("/clear", "清空会话", "fg"),
        row("/exit", "退出并停止 Agent", "fg"),
        row("!<cmd>", "执行 Shell 命令", "fg"),
      ]);
      break;
    case "/model":
      addLines(id, [
        { kind: "line", text: "" },
        row("model", prov.model || "default", "fg"),
        row("provider", `${prov.name} · ${hostOf(prov.baseUrl)}`),
        { kind: "line", indent: 2, tone: "faint", text: "在 AgentBox › 配置 中切换提供商" },
      ]);
      break;
    case "/status":
      addLines(id, [
        { kind: "line", text: "" },
        row("version", `${def.name} ${def.version}`, "fg"),
        row("cwd", "/root/projects/demo"),
        row("provider", `${prov.name} (${prov.model || "default"})`),
        row("api key", prov.apiKey ? "已设置" : "未设置", prov.apiKey ? "ok" : "warn"),
        row("system", `Debian 12 · aarch64 · ${RUNTIMES[1].name} ${RUNTIMES[1].version}`),
      ]);
      break;
    case "/clear":
      patch(id, (s) => ({ ...s, lines: [{ id: nextId(), kind: "banner" }] }));
      break;
    case "/exit":
    case "/quit":
      void useApp.getState().stopAgent(id);
      break;
    default:
      addLines(id, [{ kind: "line", indent: 2, tone: "err", text: `未知命令 ${cmd}，输入 /help 查看可用命令` }]);
  }
}

function handleShell(id: AgentId, cmd: string) {
  const out = SHELL[cmd] ?? (cmd ? [`(${cmd.split(" ")[0]} 已执行，无输出)`] : []);
  addLines(
    id,
    out.map((x, i): L => ({ kind: "line", indent: 2, tone: "dim", text: i === 0 ? `⎿  ${x}` : `   ${x}` })),
  );
}

export async function runTerminalInput(id: AgentId, raw: string) {
  const input = raw.trim();
  const sess = useApp.getState().sessions[id];
  if (!sess || sess.busy) return;
  if (!input) return;
  patch(id, (s) => ({ ...s, history: [...s.history.filter((h) => h !== input), input].slice(-40) }));
  addLines(id, [{ kind: "line", text: "" }, { kind: "user", text: input }]);
  if (input.startsWith("/")) return handleSlash(id, input);
  if (input.startsWith("!")) return handleShell(id, input.slice(1).trim());

  const style = STYLES[id];
  const runId = startRun(id);
  for (const ev of buildReply(id, input)) {
    if (!alive(id, runId)) return;
    if (ev.k === "think") {
      const sid = nextId();
      patch(id, (s) => ({ ...s, lines: [...s.lines, { id: sid, kind: "spinner", text: style.spinner() }] }));
      await sleep(ev.ms);
      patch(id, (s) => ({ ...s, lines: s.lines.filter((l) => l.id !== sid) }));
    } else if (ev.k === "say") {
      const lid = nextId();
      patch(id, (s) => ({
        ...s,
        lines: [...s.lines, { id: nextId(), kind: "line", text: "" }, { id: lid, kind: "line", text: "", ...style.say }],
      }));
      if (!(await streamTo(id, runId, lid, ev.text, "lines"))) return;
    } else if (ev.k === "tool") {
      await sleep(380);
      if (!alive(id, runId)) return;
      addLines(id, [{ kind: "line", text: "" }, ...style.tool(ev.tool)]);
      await sleep(260);
    } else if (ev.k === "done") {
      const tail = style.done(ev.secs);
      if (tail.length) addLines(id, [{ kind: "line", text: "" }, ...tail]);
    }
  }
  endRun(id, runId);
}

/* ───────────── Web UI ───────────── */
export async function runWebInput(id: AgentId, text: string) {
  const input = text.trim();
  const sess = useApp.getState().sessions[id];
  if (!input || !sess || sess.busy) return;
  const addMsg = (m: Omit<ChatMsg, "id">) => {
    const mid = nextId();
    patch(id, (s) => ({ ...s, msgs: [...s.msgs, { id: mid, ...m }] }));
    return mid;
  };
  addMsg({ role: "user", text: input });
  const runId = startRun(id);
  for (const ev of buildReply(id, input)) {
    if (!alive(id, runId)) return;
    if (ev.k === "think") await sleep(ev.ms);
    else if (ev.k === "say") {
      const mid = addMsg({ role: "assistant", text: "", streaming: true });
      const ok = await streamTo(id, runId, mid, ev.text, "msgs");
      patch(id, (s) => ({ ...s, msgs: s.msgs.map((m) => (m.id === mid ? { ...m, streaming: false } : m)) }));
      if (!ok) return;
    } else if (ev.k === "tool") {
      await sleep(360);
      if (!alive(id, runId)) return;
      addMsg({ role: "tool", text: "", tool: ev.tool });
      await sleep(240);
    }
  }
  endRun(id, runId);
}
