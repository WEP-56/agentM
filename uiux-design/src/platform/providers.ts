export type ProviderKind = 'claude' | 'pi' | 'opencode' | 'codex';
export interface ProviderRow { id: string; name: string; providerKey: string; official: boolean; active: boolean; baseUrl: string }
export interface ProviderLibrary { kind: ProviderKind; revision: string; nativeRevision: string; providers: ProviderRow[]; hasCurrent: boolean }
export interface ProviderDraft { id: string; kind: ProviderKind; name: string; providerKey: string; official: boolean; source: string; revision: string; nativeRevision: string; defaultModel: string }
export const providerTitle = (kind: ProviderKind) => kind === 'claude' ? 'Claude Code' : kind === 'opencode' ? 'OpenCode' : kind === 'codex' ? 'Codex' : 'Pi';
export const OPENCODE_SDKS = [
  ['@ai-sdk/openai-compatible', 'OpenAI Chat Completions'], ['@ai-sdk/openai', 'OpenAI Responses'],
  ['@ai-sdk/anthropic', 'Anthropic Messages'], ['@ai-sdk/google', 'Google Generative AI'], ['@ai-sdk/amazon-bedrock', 'Amazon Bedrock'],
] as const;
export const providersChanged = () => window.dispatchEvent(new Event('agentm:providers'));
export const PI_APIS = [
  ['openai-completions', 'OpenAI Chat Completions'], ['openai-responses', 'OpenAI Responses'], ['anthropic-messages', 'Anthropic Messages'],
  ['google-generative-ai', 'Google Generative AI'], ['bedrock-converse-stream', 'Amazon Bedrock'],
] as const;
export const CLAUDE_MODELS = [
  { role: 'Sonnet', key: 'ANTHROPIC_DEFAULT_SONNET_MODEL', name: 'ANTHROPIC_DEFAULT_SONNET_MODEL_NAME', oneM: true },
  { role: 'Opus', key: 'ANTHROPIC_DEFAULT_OPUS_MODEL', name: 'ANTHROPIC_DEFAULT_OPUS_MODEL_NAME', oneM: true },
  { role: 'Fable', key: 'ANTHROPIC_DEFAULT_FABLE_MODEL', name: 'ANTHROPIC_DEFAULT_FABLE_MODEL_NAME', oneM: true },
  { role: 'Haiku', key: 'ANTHROPIC_DEFAULT_HAIKU_MODEL', name: 'ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME', oneM: false },
  { role: 'Subagent', key: 'CLAUDE_CODE_SUBAGENT_MODEL', name: '', oneM: true },
  { role: '默认兜底模型', key: 'ANTHROPIC_MODEL', name: '', oneM: true },
] as const;
