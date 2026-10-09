export const APP_NAME = "agentM";
export const APP_VERSION = "0.6.0-dev";
export const GITHUB_URL = "";
export const PROJECT_DIR = "~/projects/demo";

export type AgentId = "claude" | "codex" | "opencode" | "pi" | "dsh";
export type ViewKind = "terminal" | "webui";
export type Glyph = "spark" | "prompt" | "block" | "pi" | "wave";

export interface ProviderPreset {
  id: string;
  name: string;
  baseUrl: string;
  models: string[];
  keyHint: string;
}

export interface AgentDef {
  id: AgentId;
  name: string;
  cmd: string;
  vendor: string;
  tagline: string;
  pkg: string;
  version: string;
  sizeMB: number;
  color: string;
  glyph: Glyph;
  view: ViewKind;
  altView?: ViewKind;
  port?: number;
  configPath: string;
  presets: ProviderPreset[];
}

const P = {
  anthropic: {
    id: "anthropic",
    name: "Anthropic",
    baseUrl: "https://api.anthropic.com",
    models: ["opus", "sonnet", "haiku"],
    keyHint: "sk-ant-…",
  },
  deepseekAnthropic: {
    id: "deepseek",
    name: "DeepSeek",
    baseUrl: "https://api.deepseek.com/anthropic",
    models: ["deepseek-v4-pro", "deepseek-v4-flash"],
    keyHint: "sk-…",
  },
  moonshotAnthropic: {
    id: "moonshot",
    name: "Moonshot",
    baseUrl: "https://api.moonshot.cn/anthropic",
    models: ["kimi-k2", "kimi-k2-thinking"],
    keyHint: "sk-…",
  },
  openai: {
    id: "openai",
    name: "OpenAI",
    baseUrl: "https://api.openai.com/v1",
    models: ["gpt-5.4", "gpt-5.3-codex", "gpt-5-codex"],
    keyHint: "sk-proj-…",
  },
  deepseek: {
    id: "deepseek",
    name: "DeepSeek",
    baseUrl: "https://api.deepseek.com",
    models: ["deepseek-v4-pro", "deepseek-v4-flash"],
    keyHint: "sk-…",
  },
  openrouter: {
    id: "openrouter",
    name: "OpenRouter",
    baseUrl: "https://openrouter.ai/api/v1",
    models: ["anthropic/claude-sonnet-4.5", "openai/gpt-5.4", "deepseek/deepseek-v4-pro"],
    keyHint: "sk-or-…",
  },
  ollama: {
    id: "ollama",
    name: "Ollama",
    baseUrl: "http://127.0.0.1:11434/v1",
    models: ["qwen3-coder", "gpt-oss:20b"],
    keyHint: "本地服务无需密钥",
  },
  google: {
    id: "google",
    name: "Google",
    baseUrl: "https://generativelanguage.googleapis.com",
    models: ["gemini-3-pro", "gemini-3-flash"],
    keyHint: "AIza…",
  },
} satisfies Record<string, ProviderPreset>;

export const CUSTOM_PRESET: ProviderPreset = {
  id: "custom",
  name: "自定义",
  baseUrl: "",
  models: [],
  keyHint: "",
};

export const AGENTS: AgentDef[] = [
  {
    id: "claude",
    name: "Claude Code",
    cmd: "claude",
    vendor: "Anthropic",
    tagline: "Anthropic 出品的编程智能体",
    pkg: "@anthropic-ai/claude-code",
    version: "2.1.42",
    sizeMB: 68,
    color: "#D97757",
    glyph: "spark",
    view: "terminal",
    configPath: "~/.claude/settings.json",
    presets: [P.anthropic, P.deepseekAnthropic, P.moonshotAnthropic],
  },
  {
    id: "codex",
    name: "Codex",
    cmd: "codex",
    vendor: "OpenAI",
    tagline: "OpenAI 轻量级编程智能体",
    pkg: "@openai/codex",
    version: "0.58.0",
    sizeMB: 46,
    color: "#10A37F",
    glyph: "prompt",
    view: "terminal",
    configPath: "~/.codex/config.toml",
    presets: [P.openai, P.openrouter, P.deepseek, P.ollama],
  },
  {
    id: "opencode",
    name: "OpenCode",
    cmd: "opencode",
    vendor: "Anomaly",
    tagline: "开源多模型编程智能体",
    pkg: "opencode-ai",
    version: "1.1.8",
    sizeMB: 94,
    color: "#6E6A64",
    glyph: "block",
    view: "webui",
    altView: "terminal",
    port: 4096,
    configPath: "~/.config/opencode/opencode.json",
    presets: [P.deepseek, P.anthropic, P.openai, P.openrouter],
  },
  {
    id: "pi",
    name: "Pi",
    cmd: "pi",
    vendor: "Earendil",
    tagline: "极简、可扩展的编程智能体",
    pkg: "@earendil-works/pi-coding-agent",
    version: "0.74.2",
    sizeMB: 22,
    color: "#7C5CE0",
    glyph: "pi",
    view: "terminal",
    configPath: "~/.pi/agent/settings.json",
    presets: [P.anthropic, P.openai, P.google, P.openrouter],
  },
  {
    id: "dsh",
    name: "DSH",
    cmd: "dsh",
    vendor: "DeepSeek",
    tagline: "DeepSeek Harness 智能体工作台",
    pkg: "@deepseek-ai/dsh",
    version: "0.2.0-rc.2",
    sizeMB: 58,
    color: "#4D6BFE",
    glyph: "wave",
    view: "webui",
    altView: "terminal",
    port: 8080,
    configPath: "~/.dsh/profiles/web",
    presets: [P.deepseek, P.openrouter, P.openai],
  },
];

export const AGENT_IDS = AGENTS.map((a) => a.id);
export const agentById = (id: AgentId) => AGENTS.find((a) => a.id === id)!;

/* ───────────── Environment ───────────── */
export const SYSTEM = {
  name: "Ubuntu 24.04",
  codename: "noble",
  arch: "arm64",
  sizeMB: 1270,
};

export type RuntimeId = "proot" | "node" | "python" | "git";
export const RUNTIMES: { id: RuntimeId; name: string; version: string; sizeMB: number }[] = [
  { id: "proot", name: "Linux 运行时", version: "proot 5.4.0", sizeMB: 14 },
  { id: "node", name: "Node.js", version: "v22.20.0", sizeMB: 186 },
  { id: "python", name: "Python", version: "3.11.2", sizeMB: 98 },
  { id: "git", name: "Git", version: "2.39.5", sizeMB: 41 },
];

export const MIRRORS = [
  { id: "auto", name: "自动", host: "deb.debian.org" },
  { id: "official", name: "官方", host: "deb.debian.org" },
  { id: "tuna", name: "清华", host: "mirrors.tuna.tsinghua.edu.cn" },
  { id: "ustc", name: "中科大", host: "mirrors.ustc.edu.cn" },
];
