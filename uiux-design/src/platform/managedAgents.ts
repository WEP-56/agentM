export const managedAgents = [
  { id: 'claude', name: 'Claude Code', install: 'installClaude', remove: 'removeClaude', ready: 'claudeReady', version: 'claudeVersion' },
  { id: 'codex', name: 'Codex', install: 'installCodex', remove: 'removeCodex', ready: 'codexReady', version: 'codexVersion' },
  { id: 'opencode', name: 'OpenCode', install: 'installOpenCode', remove: 'removeOpenCode', ready: 'openCodeReady', version: 'opencodeVersion' },
  { id: 'pi', name: 'Pi', install: 'installPi', remove: 'removePi', ready: 'piReady', version: 'piVersion' },
  { id: 'dsh', name: 'DSH', install: 'installDsh', remove: 'removeDsh', ready: 'dshReady', version: 'dshVersion' },
] as const;

export type ManagedAgentId = typeof managedAgents[number]['id'];
export function managedAgent(id: string) { return managedAgents.find(agent => agent.id === id); }
export const packageAction = (agent: typeof managedAgents[number], action: 'check' | 'checkUpdate' | 'update') => agent.install.replace('install', action);
